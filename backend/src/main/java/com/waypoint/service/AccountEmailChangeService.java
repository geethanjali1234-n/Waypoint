package com.waypoint.service;

import com.waypoint.dto.auth.AccountEmailChangeRequestedResponse;
import com.waypoint.dto.auth.ChangeAccountEmailRequest;
import com.waypoint.dto.auth.ConfirmAccountEmailChangeRequest;
import com.waypoint.dto.auth.LoginResponse;
import com.waypoint.entity.Organization;
import com.waypoint.entity.PendingAccountEmailChange;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.PendingAccountEmailChangeRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.JwtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Changes a Company Admin's login email only after the new address has been proven with a one-time code.
 *
 * <p>Two steps:</p>
 * <ol>
 *   <li><b>Request</b> (current password required): stores a pending change bound to the authenticated user and
 *       organization and emails a six-digit code to the NEW address. Nothing about the account changes; the existing
 *       login email stays active.</li>
 *   <li><b>Confirm</b> (code required): the new address is read from the stored pending change, never from the request.
 *       Only then are {@code User.email}, {@code isEmailVerified} and {@code Organization.adminEmail} updated.</li>
 * </ol>
 *
 * <p>MongoDB writes here are not one transaction. The single-use claim is atomic, but the two document updates are not:
 * a failure part-way is compensated (best effort) rather than guaranteed to be all-or-nothing.</p>
 */
@Service
public class AccountEmailChangeService {

    private static final Logger log = LoggerFactory.getLogger(AccountEmailChangeService.class);

    static final int CODE_VALIDITY_MINUTES = 10;
    static final int RESEND_COOLDOWN_SECONDS = 60;
    static final int MAX_FAILED_ATTEMPTS = 5;
    static final int MAX_SENDS_PER_WINDOW = 5;
    static final int SEND_WINDOW_MINUTES = 30;
    static final String INVALID_CODE_MESSAGE = "Invalid or expired verification code";
    static final String ALREADY_REGISTERED_MESSAGE = "Email already registered";

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PendingAccountEmailChangeRepository pendingRepository;
    private final MongoOperations mongoOperations;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final OtpService otpService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final AuditService auditService;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AccountEmailChangeService(UserRepository userRepository, OrganizationRepository organizationRepository,
                                     PendingAccountEmailChangeRepository pendingRepository, MongoOperations mongoOperations,
                                     PasswordEncoder passwordEncoder, JwtService jwtService, OtpService otpService,
                                     EmailService emailService, EmailTemplateService emailTemplateService,
                                     AuditService auditService) {
        this(userRepository, organizationRepository, pendingRepository, mongoOperations, passwordEncoder, jwtService,
                otpService, emailService, emailTemplateService, auditService, Clock.systemDefaultZone());
    }

    /** For tests, which control the clock. */
    AccountEmailChangeService(UserRepository userRepository, OrganizationRepository organizationRepository,
                              PendingAccountEmailChangeRepository pendingRepository, MongoOperations mongoOperations,
                              PasswordEncoder passwordEncoder, JwtService jwtService, OtpService otpService,
                              EmailService emailService, EmailTemplateService emailTemplateService,
                              AuditService auditService, Clock clock) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.pendingRepository = pendingRepository;
        this.mongoOperations = mongoOperations;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.otpService = otpService;
        this.emailService = emailService;
        this.emailTemplateService = emailTemplateService;
        this.auditService = auditService;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ step 1: request

    public AccountEmailChangeRequestedResponse requestChange(String authenticatedUserId, ChangeAccountEmailRequest request) {
        User user = requireActiveCompanyAdmin(authenticatedUserId);
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Current password is incorrect");
        }
        String newEmail = request.getNewEmail().trim().toLowerCase();
        if (newEmail.equalsIgnoreCase(user.getEmail())) {
            throw new BusinessException("New email must be different from the current email");
        }
        if (userRepository.existsByEmail(newEmail)) {
            throw new BusinessException(ALREADY_REGISTERED_MESSAGE);
        }

        LocalDateTime now = now();
        PendingAccountEmailChange previous = pendingRepository.findById(user.getId()).orElse(null);
        LocalDateTime windowStart = now;
        int sendCount = 0;
        if (previous != null) {
            if (previous.getLastSentAt() != null && previous.getLastSentAt().plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(now)) {
                throw new BusinessException("Please wait before requesting another verification code");
            }
            if (previous.getSendWindowStartedAt() != null
                    && previous.getSendWindowStartedAt().plusMinutes(SEND_WINDOW_MINUTES).isAfter(now)) {
                windowStart = previous.getSendWindowStartedAt();
                sendCount = previous.getSendCount() == null ? 0 : previous.getSendCount();
                if (sendCount >= MAX_SENDS_PER_WINDOW) {
                    throw new BusinessException("Verification code request limit reached. Please try again later");
                }
            }
        }

        String code = String.format("%06d", secureRandom.nextInt(1_000_000));
        PendingAccountEmailChange pending = PendingAccountEmailChange.builder()
                .id(user.getId())
                .organizationId(user.getOrganizationId())
                .newEmail(newEmail)
                .otpHash(passwordEncoder.encode(code))
                .expiresAt(now.plusMinutes(CODE_VALIDITY_MINUTES))
                .failedAttempts(0)
                .lastSentAt(now)
                .sendWindowStartedAt(windowStart)
                .sendCount(sendCount + 1)
                .createdAt(now)
                .build();
        pendingRepository.save(pending);
        try {
            otpService.sendAccountEmailChangeCode(newEmail, code);
        } catch (RuntimeException notSent) {
            // A code that could not be delivered must not stay usable.
            discardPending(user.getId());
            throw notSent;
        }
        return new AccountEmailChangeRequestedResponse(newEmail, CODE_VALIDITY_MINUTES * 60, RESEND_COOLDOWN_SECONDS);
    }

    // ------------------------------------------------------------------ step 2: confirm

    public LoginResponse confirmChange(String authenticatedUserId, ConfirmAccountEmailChangeRequest request) {
        User user = requireActiveCompanyAdmin(authenticatedUserId);
        PendingAccountEmailChange pending = pendingRepository.findById(user.getId())
                .orElseThrow(() -> new BusinessException(INVALID_CODE_MESSAGE));
        LocalDateTime now = now();
        if (pending.getClaimedAt() != null
                || pending.getExpiresAt() == null || !pending.getExpiresAt().isAfter(now)
                || !user.getOrganizationId().equals(pending.getOrganizationId())
                || attempts(pending) >= MAX_FAILED_ATTEMPTS) {
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }
        if (!passwordEncoder.matches(request.getOtp(), pending.getOtpHash())) {
            mongoOperations.updateFirst(new Query(Criteria.where("_id").is(user.getId())),
                    new Update().inc("failedAttempts", 1), PendingAccountEmailChange.class);
            throw new BusinessException(INVALID_CODE_MESSAGE);
        }

        // Single use: only the request that wins this update goes on. A replay, or a second concurrent request, gets null.
        PendingAccountEmailChange claimed = mongoOperations.findAndModify(
                new Query(new Criteria().andOperator(
                        Criteria.where("_id").is(user.getId()),
                        Criteria.where("claimedAt").is(null),
                        Criteria.where("expiresAt").gt(now),
                        new Criteria().orOperator(Criteria.where("failedAttempts").lt(MAX_FAILED_ATTEMPTS),
                                Criteria.where("failedAttempts").exists(false)))),
                new Update().set("claimedAt", now),
                FindAndModifyOptions.options().returnNew(true), PendingAccountEmailChange.class);
        if (claimed == null) throw new BusinessException(INVALID_CODE_MESSAGE);

        String newEmail = claimed.getNewEmail();
        User holder = userRepository.findByEmail(newEmail).orElse(null);
        if (holder != null && !holder.getId().equals(user.getId())) {
            discardPending(user.getId());
            throw new BusinessException(ALREADY_REGISTERED_MESSAGE);
        }

        Organization organization = organizationRepository.findById(user.getOrganizationId()).orElse(null);
        if (organization == null) {
            discardPending(user.getId());
            throw new ResourceNotFoundException("Organization not found");
        }

        String previousEmail = user.getEmail();
        String previousAdminEmail = organization.getAdminEmail();
        LocalDateTime previousUserUpdatedAt = user.getUpdatedAt();
        LocalDateTime previousOrganizationUpdatedAt = organization.getUpdatedAt();
        Boolean previousVerified = user.getIsEmailVerified();

        boolean organizationSaved = false;
        try {
            organization.setAdminEmail(newEmail);
            organization.setUpdatedAt(now);
            organizationRepository.save(organization);
            organizationSaved = true;
            user.setEmail(newEmail);
            user.setIsEmailVerified(true); // true only because the code was just entered
            user.setUpdatedAt(now);
            userRepository.save(user);
        } catch (RuntimeException failure) {
            // Not one transaction: put the organization back (best effort) and let the admin retry with the same code.
            if (organizationSaved) {
                try {
                    organization.setAdminEmail(previousAdminEmail);
                    organization.setUpdatedAt(previousOrganizationUpdatedAt);
                    organizationRepository.save(organization);
                } catch (RuntimeException restoreFailed) {
                    log.error("Account email change could not be rolled back [organizationId={}, userId={}]",
                            organization.getId(), user.getId());
                }
            }
            user.setEmail(previousEmail);
            user.setIsEmailVerified(previousVerified);
            user.setUpdatedAt(previousUserUpdatedAt);
            if (failure instanceof DuplicateKeyException) {
                discardPending(user.getId());
                throw new BusinessException(ALREADY_REGISTERED_MESSAGE);
            }
            releaseClaim(user.getId());
            throw failure;
        }

        discardPending(user.getId());
        auditService.record(AuditEvent.builder()
                .organizationId(user.getOrganizationId())
                .actorUserId(user.getId())
                .actorLoginId(user.getLoginId())
                .actorRole(user.getRole())
                .actionType(AuditActionType.ACCOUNT_EMAIL_CHANGED)
                .entityType("USER")
                .entityId(user.getId())
                .entityDisplayName(user.getLoginId())
                .description("Changed account email after verifying the new address")
                .source("MY_HR")
                .build());
        notifyPreviousAddress(previousEmail, newEmail);

        return LoginResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .loginId(user.getLoginId())
                .organizationId(user.getOrganizationId())
                .role(user.getRole())
                .accountStatus(user.getAccountStatus())
                .isEmailVerified(user.getIsEmailVerified())
                .mustChangePassword(user.getMustChangePassword())
                .accessToken(jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name()))
                .tokenType("Bearer")
                .build();
    }

    // ------------------------------------------------------------------ helpers

    private User requireActiveCompanyAdmin(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
        if (user.getRole() != UserRole.COMPANY_ADMIN || user.getAccountStatus() != AccountStatus.ACTIVE
                || user.getOrganizationId() == null || user.getOrganizationId().isBlank()) {
            throw new BusinessException("Only an active Company Admin can change this account email");
        }
        return user;
    }

    private void discardPending(String userId) {
        try {
            pendingRepository.deleteById(userId);
        } catch (RuntimeException ignored) {
            // The claim already makes the code unusable, and expiry is checked in code.
        }
    }

    private void releaseClaim(String userId) {
        try {
            mongoOperations.updateFirst(new Query(Criteria.where("_id").is(userId)),
                    new Update().unset("claimedAt"), PendingAccountEmailChange.class);
        } catch (RuntimeException ignored) {
            // The admin can request a new code.
        }
    }

    /** Tells the previous address about the change. Best effort: a mail problem never undoes a completed change. */
    private void notifyPreviousAddress(String previousEmail, String newEmail) {
        if (previousEmail == null || previousEmail.isBlank()) return;
        try {
            emailService.sendHtmlEmail(previousEmail, "Your Waypoint login email was changed", emailTemplateService.build(
                    EmailTemplateData.builder()
                            .preheader("Your Waypoint login email was changed")
                            .title("Your login email was changed")
                            .message("The login email for your Waypoint account was changed to " + mask(newEmail)
                                    + ". From now on you sign in with the new address.")
                            .secondaryInformation("If you did not make this change, contact your organization's administrator or Waypoint support immediately.")
                            .build()));
        } catch (RuntimeException notSent) {
            log.warn("Account email change notice not sent [exceptionType={}]", notSent.getClass().getSimpleName());
        }
    }

    /** a***@example.com: recognizable to its owner without publishing the whole address in a message to the old one. */
    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        if (at <= 0) return "a new address";
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static int attempts(PendingAccountEmailChange pending) {
        return pending.getFailedAttempts() == null ? 0 : pending.getFailedAttempts();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
