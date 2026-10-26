package com.waypoint.service;

import com.waypoint.dto.auth.OrganizationRegistrationRequest;
import com.waypoint.dto.auth.OrganizationRegistrationResponse;
import com.waypoint.dto.auth.OrganizationRegistrationStartResponse;
import com.waypoint.dto.auth.OrganizationRegistrationVerificationRequest;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.entity.OtpVerification;
import com.waypoint.entity.PendingOrganizationRegistration;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.OrganizationRegistrationStatus;
import com.waypoint.enums.OrganizationStatus;
import com.waypoint.enums.OtpPurpose;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.PendingOrganizationRegistrationRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OrganizationRegistrationService {

    private static final long REGISTRATION_VALIDITY_MINUTES = 30;
    private static final String INVALID_REGISTRATION = "Invalid or expired organization registration";
    // Same folder OrganizationSettingsService uses, so Settings can later replace or remove it.
    private static final String LOGO_SUBDIRECTORY = "company-logos";
    private static final Set<String> LOGO_CONTENT_TYPES = Set.of("image/png", "image/jpeg");
    private static final long LOGO_MAX_BYTES = 2 * 1024 * 1024;
    private static final String LOGO_INVALID_MESSAGE = "Use a PNG or JPEG logo no larger than 2 MB";

    private final PendingOrganizationRegistrationRepository pendingRepository;
    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final OrganizationSettingsRepository organizationSettingsRepository;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final TemporaryPasswordGenerator temporaryPasswordGenerator;
    private final OrganizationRegistrationCredentialDelivery credentialDelivery;
    private final FileStorageService fileStorageService;

    public OrganizationRegistrationStartResponse start(OrganizationRegistrationRequest request) {
        return start(request, null);
    }

    /**
     * The optional logo is only stored after every identity check has passed,
     * so a caller cannot overwrite another in-progress registration's logo.
     */
    public OrganizationRegistrationStartResponse start(OrganizationRegistrationRequest request, MultipartFile logo) {
        credentialDelivery.requireAvailable();
        boolean hasLogo = logo != null && !logo.isEmpty();
        if (hasLogo) validateLogo(logo);

        String adminEmail = normalizeEmail(request.getAdminEmail());
        String organizationEmail = normalizeEmail(request.getOrganizationEmail());
        validateAvailableIdentities(organizationEmail, adminEmail, null, null, null);

        String registrationId = registrationId(adminEmail);
        PendingOrganizationRegistration existing = pendingRepository.findById(registrationId).orElse(null);
        LocalDateTime now = LocalDateTime.now();
        if (existing != null && existing.getExpiresAt() != null && existing.getExpiresAt().isAfter(now)) {
            throw new BusinessException("An organization registration is already in progress for this Admin email");
        }

        String organizationCode = generateOrganizationCode(request.getOrganizationName(), now);
        String adminLoginId = adminLoginId(organizationCode);
        PendingOrganizationRegistration pending = PendingOrganizationRegistration.builder()
                .id(registrationId)
                .organizationId("selfreg-org-" + registrationId.substring(0, 24))
                .organizationCode(organizationCode)
                .organizationName(request.getOrganizationName().trim())
                .organizationEmail(organizationEmail)
                .phoneNumber(request.getPhoneNumber().trim())
                .state(request.getState().trim())
                .city(request.getCity().trim())
                .streetAddress(request.getStreetAddress().trim())
                .zipCode(request.getZipCode().trim())
                .organizationSize(request.getOrganizationSize())
                .industry(trimToNull(request.getIndustry()))
                .website(trimToNull(request.getWebsite()))
                .description(trimToNull(request.getDescription()))
                .einNumber(normalizeEin(request.getEinNumber()))
                .eVerifyNumber(trimToNull(request.getEVerifyNumber()))
                .payrollPeriod(request.getPayrollPeriod())
                .adminName(request.getAdminName().trim())
                .adminPhoneNumber(request.getAdminPhoneNumber().trim())
                .adminLoginId(adminLoginId)
                .adminEmail(adminEmail)
                .status(OrganizationRegistrationStatus.PENDING_VERIFICATION)
                .adminUserId("selfreg-admin-" + registrationId.substring(0, 24))
                .createdAt(now)
                .updatedAt(now)
                .expiresAt(now.plusMinutes(REGISTRATION_VALIDITY_MINUTES))
                .build();

        if (hasLogo) {
            pending.setOrganizationLogoDocumentId(
                    fileStorageService.store(logo, LOGO_SUBDIRECTORY, pending.getOrganizationId()));
        }
        try {
            pendingRepository.save(pending);
            otpService.generateOrganizationRegistrationOtpIfAllowed(adminEmail);
        } catch (RuntimeException exception) {
            pendingRepository.deleteById(registrationId);
            if (hasLogo) safelyRemoveLogo(pending.getOrganizationId());
            throw exception;
        }
        return startResponse(pending);
    }

    public OrganizationRegistrationStartResponse resend(String registrationId) {
        PendingOrganizationRegistration pending = requirePending(registrationId);
        requireUsable(pending);
        otpService.generateOrganizationRegistrationOtpIfAllowed(pending.getAdminEmail());
        return startResponse(pending);
    }

    public OrganizationRegistrationResponse complete(OrganizationRegistrationVerificationRequest request) {
        PendingOrganizationRegistration pending = requirePending(request.getRegistrationId());
        if (pending.getStatus() == OrganizationRegistrationStatus.COMPLETED) {
            return completedResponse(pending);
        }
        requireUsable(pending);

        Organization recoveredOrganization = organizationRepository.findById(pending.getOrganizationId()).orElse(null);
        User recoveredAdmin = userRepository.findById(pending.getAdminUserId()).orElse(null);
        if (matches(recoveredOrganization, pending) && matches(recoveredAdmin, pending)) {
            ensurePayrollSettings(pending);
            markCompleted(pending);
            return completedResponse(pending);
        }

        credentialDelivery.requireAvailable();

        validateAvailableIdentities(pending.getOrganizationEmail(), pending.getAdminEmail(),
                pending.getAdminLoginId(), pending.getOrganizationId(), pending.getAdminUserId());
        boolean recoveringVerifiedWrite = matches(recoveredOrganization, pending) || matches(recoveredAdmin, pending);
        OtpVerification verifiedOtp = recoveringVerifiedWrite ? null : otpService.verifyOtp(
                pending.getAdminEmail(), OtpPurpose.ORGANIZATION_REGISTRATION, request.getOtp());

        String generatedPasswordHash = null;
        String temporaryPassword = null;
        if (recoveredAdmin == null) {
            temporaryPassword = temporaryPasswordGenerator.generate();
            generatedPasswordHash = passwordEncoder.encode(temporaryPassword);
        }

        boolean organizationCreated = false;
        try {
            Organization organization = recoveredOrganization;
            if (organization == null) {
                organization = organizationRepository.insert(toOrganization(pending));
                organizationCreated = true;
            } else if (!matches(organization, pending)) {
                throw new BusinessException("Organization registration conflicts with an existing organization");
            }

            User admin = recoveredAdmin;
            if (admin == null) {
                admin = userRepository.insert(toAdmin(pending, generatedPasswordHash));
                // Only the insert that wins creates credentials, so a retry or a losing
                // concurrent request never emails a password that is not the live one.
                emailCredentialsOrRollBack(pending, temporaryPassword);
                temporaryPassword = null;
            } else if (!matches(admin, pending)) {
                throw new BusinessException("Organization registration conflicts with an existing account");
            }

            pending.setAdminUserId(admin.getId());
            ensurePayrollSettings(pending);
            markCompleted(pending);
            return completedResponse(pending);
        } catch (RuntimeException exception) {
            boolean adminExists = userRepository.existsById(pending.getAdminUserId());
            if (organizationCreated && !adminExists) {
                safelyDeleteOrganization(pending.getOrganizationId());
            }
            if (!adminExists && verifiedOtp != null) {
                otpService.revertVerification(verifiedOtp);
            }
            if (exception instanceof DuplicateKeyException) {
                throw new BusinessException("Organization or Admin identity already exists");
            }
            throw exception;
        }
    }

    /**
     * Sends to the verified Admin email stored on the registration. If the email cannot be
     * sent, the just-created Admin is removed so the caller's catch block can also remove the
     * new organization and restore the OTP; the user can then retry with the same code.
     */
    private void emailCredentialsOrRollBack(PendingOrganizationRegistration pending, String temporaryPassword) {
        try {
            credentialDelivery.deliver(pending.getAdminEmail(), pending.getOrganizationName(),
                    pending.getAdminName(), pending.getAdminLoginId(), temporaryPassword);
        } catch (RuntimeException exception) {
            try {
                userRepository.deleteById(pending.getAdminUserId());
            } catch (RuntimeException ignored) {
                // The original delivery failure is what the caller needs to see.
            }
            throw exception;
        }
    }

    private void validateAvailableIdentities(String organizationEmail, String adminEmail, String adminLoginId,
            String allowedOrganizationId, String allowedUserId) {
        organizationRepository.findByOrganizationEmail(organizationEmail).ifPresent(existing -> {
            if (!existing.getId().equals(allowedOrganizationId)) {
                throw new BusinessException("Company contact email already belongs to an organization");
            }
        });
        userRepository.findByEmail(adminEmail).ifPresent(existing -> {
            if (!existing.getId().equals(allowedUserId)) {
                throw new BusinessException("Admin email already belongs to an account");
            }
        });
        if (adminLoginId != null) {
            userRepository.findByLoginId(adminLoginId).ifPresent(existing -> {
                if (!existing.getId().equals(allowedUserId)) {
                    throw new BusinessException("Admin login ID already belongs to an account");
                }
            });
        }
    }

    private Organization toOrganization(PendingOrganizationRegistration pending) {
        return Organization.builder()
                .id(pending.getOrganizationId())
                .organizationCode(pending.getOrganizationCode())
                .organizationName(pending.getOrganizationName())
                .organizationEmail(pending.getOrganizationEmail())
                .phoneNumber(pending.getPhoneNumber())
                .state(pending.getState())
                .city(pending.getCity())
                .streetAddress(pending.getStreetAddress())
                .zipCode(pending.getZipCode())
                .organizationSize(pending.getOrganizationSize())
                .industry(pending.getIndustry())
                .website(pending.getWebsite())
                .description(pending.getDescription())
                .organizationLogoDocumentId(pending.getOrganizationLogoDocumentId())
                .einNumber(pending.getEinNumber())
                .eVerifyNumber(pending.getEVerifyNumber())
                .adminName(pending.getAdminName())
                .adminEmail(pending.getAdminEmail())
                .adminPhoneNumber(pending.getAdminPhoneNumber())
                .nextEmployeeSequence(1L)
                .status(OrganizationStatus.ACTIVE)
                .createdAt(pending.getCreatedAt())
                .updatedAt(pending.getCreatedAt())
                .build();
    }

    /**
     * Idempotent: a retried completion (recovery path) finds the row it already
     * wrote instead of failing on the unique organizationId index.
     */
    private void ensurePayrollSettings(PendingOrganizationRegistration pending) {
        if (pending.getPayrollPeriod() == null
                || organizationSettingsRepository.findByOrganizationId(pending.getOrganizationId()).isPresent()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        organizationSettingsRepository.save(OrganizationSettings.builder()
                .organizationId(pending.getOrganizationId())
                .payrollPeriod(pending.getPayrollPeriod())
                .createdAt(now)
                .updatedAt(now)
                .build());
    }

    /**
     * Public endpoint, so check the real file header, not just the declared
     * type: PNG (89 50 4E 47) or JPEG (FF D8 FF), at most 2 MB.
     */
    private void validateLogo(MultipartFile logo) {
        if (logo.getSize() > LOGO_MAX_BYTES || !LOGO_CONTENT_TYPES.contains(logo.getContentType())) {
            throw new BusinessException(LOGO_INVALID_MESSAGE);
        }
        byte[] header = new byte[4];
        int read;
        try (InputStream input = logo.getInputStream()) {
            read = input.readNBytes(header, 0, header.length);
        } catch (IOException exception) {
            throw new BusinessException(LOGO_INVALID_MESSAGE);
        }
        boolean png = read == 4 && (header[0] & 0xFF) == 0x89 && header[1] == 'P' && header[2] == 'N' && header[3] == 'G';
        boolean jpeg = read >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF;
        if (!png && !jpeg) throw new BusinessException(LOGO_INVALID_MESSAGE);
    }

    private void safelyRemoveLogo(String organizationId) {
        try {
            fileStorageService.remove(LOGO_SUBDIRECTORY, organizationId);
        } catch (RuntimeException ignored) {
            // A later attempt for the same Admin email stores under the same owner and replaces it.
        }
    }

    /** Stores EINs in the conventional 12-3456789 form. */
    private String normalizeEin(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        return digits.length() == 9 ? digits.substring(0, 2) + "-" + digits.substring(2) : trimToNull(value);
    }

    private User toAdmin(PendingOrganizationRegistration pending, String passwordHash) {
        return User.builder()
                .id(pending.getAdminUserId())
                .loginId(pending.getAdminLoginId())
                .email(pending.getAdminEmail())
                .organizationId(pending.getOrganizationId())
                .passwordHash(passwordHash)
                .role(UserRole.COMPANY_ADMIN)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .mustChangePassword(true)
                .createdAt(pending.getCreatedAt())
                .updatedAt(pending.getCreatedAt())
                .build();
    }

    private boolean matches(Organization organization, PendingOrganizationRegistration pending) {
        return organization != null
                && pending.getOrganizationId().equals(organization.getId())
                && pending.getOrganizationCode().equals(organization.getOrganizationCode())
                && pending.getOrganizationEmail().equals(organization.getOrganizationEmail());
    }

    private boolean matches(User user, PendingOrganizationRegistration pending) {
        return user != null
                && pending.getAdminUserId().equals(user.getId())
                && pending.getOrganizationId().equals(user.getOrganizationId())
                && pending.getAdminLoginId().equals(user.getLoginId())
                && pending.getAdminEmail().equals(user.getEmail())
                && user.getRole() == UserRole.COMPANY_ADMIN;
    }

    private PendingOrganizationRegistration requirePending(String registrationId) {
        if (registrationId == null || registrationId.isBlank()) {
            throw new BusinessException(INVALID_REGISTRATION);
        }
        return pendingRepository.findById(registrationId.trim())
                .orElseThrow(() -> new ResourceNotFoundException(INVALID_REGISTRATION));
    }

    private void requireUsable(PendingOrganizationRegistration pending) {
        if (pending.getStatus() != OrganizationRegistrationStatus.PENDING_VERIFICATION
                || pending.getExpiresAt() == null
                || !pending.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(INVALID_REGISTRATION);
        }
    }

    private void markCompleted(PendingOrganizationRegistration pending) {
        pending.setStatus(OrganizationRegistrationStatus.COMPLETED);
        pending.setUpdatedAt(LocalDateTime.now());
        pending.setExpiresAt(LocalDateTime.now().plusHours(24));
        pendingRepository.save(pending);
    }

    private OrganizationRegistrationStartResponse startResponse(PendingOrganizationRegistration pending) {
        return new OrganizationRegistrationStartResponse(
                pending.getId(), pending.getAdminEmail(), pending.getExpiresAt());
    }

    private OrganizationRegistrationResponse completedResponse(PendingOrganizationRegistration pending) {
        return new OrganizationRegistrationResponse(
                pending.getOrganizationId(), pending.getOrganizationCode(), pending.getAdminLoginId());
    }

    private String registrationId(String email) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(("waypoint-organization-registration:" + email).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    /**
     * Short, readable code like Tardus's "TD": the first five letters/digits of
     * the name (ITAP), numbered when taken (ITAP2). Login IDs are built from it
     * (ITAPADMIN, ITAP001), so the admin ID must be free as well.
     */
    private String generateOrganizationCode(String organizationName, LocalDateTime now) {
        String prefix = organizationName.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        prefix = prefix.substring(0, Math.min(prefix.length(), 5));
        if (prefix.length() < 2) prefix = "ORG";
        for (int number = 1; number <= 100; number++) {
            String code = number == 1 ? prefix : prefix + number;
            if (organizationRepository.findByOrganizationCode(code).isEmpty()
                    && !pendingRepository.existsByOrganizationCodeAndStatusAndExpiresAtAfter(
                            code, OrganizationRegistrationStatus.PENDING_VERIFICATION, now)
                    && userRepository.findByLoginId(adminLoginId(code)).isEmpty()) {
                return code;
            }
        }
        throw new BusinessException("Unable to generate a unique organization code");
    }

    /** Same shape OrganizationEmployeeIdService uses for Company Admins (e.g. TDADMIN). */
    private String adminLoginId(String organizationCode) {
        return organizationCode + "ADMIN";
    }

    private String normalizeEmail(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void safelyDeleteOrganization(String organizationId) {
        try {
            organizationRepository.deleteById(organizationId);
        } catch (RuntimeException ignored) {
            // A retry can recognize and safely resume this deterministic organization record.
        }
    }
}
