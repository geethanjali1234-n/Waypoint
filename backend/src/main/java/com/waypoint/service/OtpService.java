package com.waypoint.service;

import com.waypoint.entity.OtpVerification;
import com.waypoint.enums.OtpPurpose;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.OtpVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class OtpService {

    private static final Logger logger = LoggerFactory.getLogger(OtpService.class);
    private static final int OTP_LENGTH = 6;
    private static final long OTP_VALIDITY_MINUTES = 10;
    private static final long OTP_RESEND_COOLDOWN_SECONDS = 60;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final int MAX_REGISTRATION_SENDS_PER_WINDOW = 5;
    private static final long REGISTRATION_SEND_WINDOW_MINUTES = 30;
    private static final String INVALID_OTP_MESSAGE = "Invalid or expired verification code";
    private static final String VERIFICATION_EMAIL_SUBJECT = "Your Waypoint verification code";
    private static final String RESET_EMAIL_SUBJECT = "Reset your Waypoint password";
    private static final String ORGANIZATION_REGISTRATION_EMAIL_SUBJECT = "Verify your Waypoint organization account";
    private static final String MAIL_FAILURE_MESSAGE =
            "Unable to send verification email. Please try again.";

    private final OtpVerificationRepository otpVerificationRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;
    private final MongoOperations mongoOperations;
    private final DevOrganizationRegistrationOtpLogger devOrganizationRegistrationOtpLogger;
    private final SecureRandom secureRandom = new SecureRandom();

    public GeneratedOtp generateAndStoreOtp(String email, OtpPurpose purpose) {
        String otp = generateNumericOtp();
        LocalDateTime now = LocalDateTime.now();

        OtpVerification otpVerification = OtpVerification.builder()
                .email(email)
                .otpHash(passwordEncoder.encode(otp))
                .purpose(purpose)
                .expiresAt(now.plusMinutes(OTP_VALIDITY_MINUTES))
                .isVerified(false)
                .failedAttempts(0)
                .createdAt(now)
                .build();

        OtpVerification saved = otpVerificationRepository.save(otpVerification);

        try {
            emailService.sendHtmlEmail(email, emailSubject(purpose), buildOtpEmail(otp, purpose));
            devOrganizationRegistrationOtpLogger.output(purpose, otp, saved.getExpiresAt());
        } catch (RuntimeException exception) {
            // The client only sees the generic message; keep the real cause
            // (e.g. SMTP connection or authentication failure) in the server log.
            logger.error("Failed to send {} verification email", purpose, exception);
            safelyDeleteUnsentOtp(saved);
            throw new BusinessException(MAIL_FAILURE_MESSAGE);
        }

        return new GeneratedOtp(saved, otp);
    }

    public Optional<GeneratedOtp> generatePasswordResetOtpIfAllowed(String email) {
        LocalDateTime now = LocalDateTime.now();
        Optional<OtpVerification> previous = otpVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, OtpPurpose.PASSWORD_RESET);

        if (previous.filter(value -> value.getCreatedAt() != null
                && value.getCreatedAt().plusSeconds(OTP_RESEND_COOLDOWN_SECONDS).isAfter(now)).isPresent()) {
            return Optional.empty();
        }

        GeneratedOtp generated = generateAndStoreOtp(email, OtpPurpose.PASSWORD_RESET);
        previous.ifPresent(value -> {
            value.setInvalidatedAt(now);
            otpVerificationRepository.save(value);
        });
        return Optional.of(generated);
    }

    public synchronized GeneratedOtp generateOrganizationRegistrationOtpIfAllowed(String email) {
        LocalDateTime now = LocalDateTime.now();
        OtpPurpose purpose = OtpPurpose.ORGANIZATION_REGISTRATION;
        Optional<OtpVerification> previous = otpVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose);

        if (previous.filter(value -> value.getCreatedAt() != null
                && value.getCreatedAt().plusSeconds(OTP_RESEND_COOLDOWN_SECONDS).isAfter(now)).isPresent()) {
            throw new BusinessException("Please wait before requesting another verification code");
        }
        long sendsInWindow = otpVerificationRepository.countByEmailAndPurposeAndCreatedAtAfter(
                email, purpose, now.minusMinutes(REGISTRATION_SEND_WINDOW_MINUTES));
        if (sendsInWindow >= MAX_REGISTRATION_SENDS_PER_WINDOW) {
            throw new BusinessException("Verification code request limit reached. Please try again later");
        }

        GeneratedOtp generated = generateAndStoreOtp(email, purpose);
        previous.ifPresent(value -> {
            value.setInvalidatedAt(now);
            otpVerificationRepository.save(value);
        });
        return generated;
    }

    public OtpVerification verifyOtp(String email, OtpPurpose purpose, String rawOtp) {
        OtpVerification otpVerification = validateOtp(email, purpose, rawOtp);
        LocalDateTime now = LocalDateTime.now();
        Query query = usableOtpQuery(otpVerification.getId(), now);
        Update update = new Update().set("isVerified", true).set("verifiedAt", now);
        OtpVerification consumed = mongoOperations.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), OtpVerification.class);
        if (consumed == null) {
            throw new BusinessException(INVALID_OTP_MESSAGE);
        }
        return consumed;
    }

    public void checkOtpValidity(String email, OtpPurpose purpose, String rawOtp) {
        validateOtp(email, purpose, rawOtp);
    }

    public void revertVerification(OtpVerification otpVerification) {
        otpVerification.setIsVerified(false);
        otpVerification.setVerifiedAt(null);
        otpVerificationRepository.save(otpVerification);
    }

    private String generateNumericOtp() {
        int bound = (int) Math.pow(10, OTP_LENGTH);
        int value = secureRandom.nextInt(bound);
        return String.format("%0" + OTP_LENGTH + "d", value);
    }

    private OtpVerification validateOtp(String email, OtpPurpose purpose, String rawOtp) {
        OtpVerification otpVerification = otpVerificationRepository
                .findTopByEmailAndPurposeOrderByCreatedAtDesc(email, purpose)
                .orElseThrow(() -> new BusinessException(INVALID_OTP_MESSAGE));

        if (otpVerification.getExpiresAt().isBefore(LocalDateTime.now())
                || Boolean.TRUE.equals(otpVerification.getIsVerified())
                || otpVerification.getInvalidatedAt() != null
                || attempts(otpVerification) >= MAX_FAILED_ATTEMPTS) {
            throw new BusinessException(INVALID_OTP_MESSAGE);
        }
        if (!passwordEncoder.matches(rawOtp, otpVerification.getOtpHash())) {
            incrementFailedAttempts(otpVerification.getId());
            throw new BusinessException(INVALID_OTP_MESSAGE);
        }
        return otpVerification;
    }

    private void incrementFailedAttempts(String otpId) {
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("_id").is(otpId),
                new Criteria().orOperator(
                        Criteria.where("failedAttempts").lt(MAX_FAILED_ATTEMPTS),
                        Criteria.where("failedAttempts").exists(false))));
        mongoOperations.updateFirst(query, new Update().inc("failedAttempts", 1), OtpVerification.class);
    }

    private Query usableOtpQuery(String otpId, LocalDateTime now) {
        return new Query(new Criteria().andOperator(
                Criteria.where("_id").is(otpId),
                Criteria.where("expiresAt").gt(now),
                Criteria.where("isVerified").ne(true),
                Criteria.where("invalidatedAt").is(null),
                new Criteria().orOperator(
                        Criteria.where("failedAttempts").lt(MAX_FAILED_ATTEMPTS),
                        Criteria.where("failedAttempts").exists(false))));
    }

    private int attempts(OtpVerification otpVerification) {
        return otpVerification.getFailedAttempts() == null ? 0 : otpVerification.getFailedAttempts();
    }

    private String emailSubject(OtpPurpose purpose) {
        if (purpose == OtpPurpose.PASSWORD_RESET) return RESET_EMAIL_SUBJECT;
        if (purpose == OtpPurpose.ORGANIZATION_REGISTRATION) return ORGANIZATION_REGISTRATION_EMAIL_SUBJECT;
        return VERIFICATION_EMAIL_SUBJECT;
    }

    private String buildOtpEmail(String otp, OtpPurpose purpose) {
        boolean passwordReset = purpose == OtpPurpose.PASSWORD_RESET;
        boolean organizationRegistration = purpose == OtpPurpose.ORGANIZATION_REGISTRATION;
        return emailTemplateService.build(EmailTemplateData.builder()
                .preheader(passwordReset ? "Your Waypoint password reset code" : "Your Waypoint verification code")
                .title(passwordReset ? "Reset your password"
                        : (organizationRegistration ? "Verify your organization account" : "Verify your email"))
                .message(passwordReset ? "Use the code below to reset your password."
                        : (organizationRegistration
                                ? "Use the code below to finish creating your organization account."
                                : "Use the verification code below."))
                .highlightedCode(otp)
                .secondaryInformation("This code expires in " + OTP_VALIDITY_MINUTES
                        + " minutes.\n\nIf you did not request this "
                        + (passwordReset ? "password reset" : "verification code")
                        + ", you can safely ignore this email.")
                .build());
    }

    private void safelyDeleteUnsentOtp(OtpVerification otpVerification) {
        try {
            otpVerificationRepository.delete(otpVerification);
        } catch (RuntimeException ignored) {
            // Preserve the safe mail failure response without exposing infrastructure details.
        }
    }

    public static final class GeneratedOtp extends OtpVerification {

        private final String rawOtp;

        GeneratedOtp(OtpVerification verification, String rawOtp) {
            super(
                    verification.getId(),
                    verification.getEmail(),
                    verification.getOtpHash(),
                    verification.getPurpose(),
                    verification.getExpiresAt(),
                    verification.getIsVerified(),
                    verification.getFailedAttempts(),
                    verification.getInvalidatedAt(),
                    verification.getCreatedAt(),
                    verification.getVerifiedAt());
            this.rawOtp = rawOtp;
        }

        public String rawOtp() {
            return rawOtp;
        }
    }
}
