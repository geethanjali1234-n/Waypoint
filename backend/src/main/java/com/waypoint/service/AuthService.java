package com.waypoint.service;

import com.waypoint.dto.auth.ChangePasswordRequest;
import com.waypoint.dto.auth.ChangeAccountEmailRequest;
import com.waypoint.dto.auth.JobSeekerRegistrationRequest;
import com.waypoint.dto.auth.JobSeekerRegistrationResponse;
import com.waypoint.dto.auth.EmployerRegistrationRequest;
import com.waypoint.dto.auth.EmployerRegistrationResponse;
import com.waypoint.dto.auth.ForgotPasswordRequest;
import com.waypoint.dto.auth.LoginRequest;
import com.waypoint.dto.auth.LoginResponse;
import com.waypoint.dto.auth.ResetPasswordRequest;
import com.waypoint.dto.auth.VerifyOtpRequest;
import com.waypoint.dto.auth.VerifyOtpResponse;
import com.waypoint.dto.auth.VerifyResetOtpRequest;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.OtpVerification;
import com.waypoint.entity.User;
import com.waypoint.entity.Organization;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.OtpPurpose;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final OtpService otpService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final DevPasswordResetOtpLogger devPasswordResetOtpLogger;
    private final AuditService auditService;

    @Value("${app.auth.expose-dev-otp:false}")
    private boolean exposeDevOtp;

    public EmployerRegistrationResponse registerEmployer(EmployerRegistrationRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Password and confirm password do not match");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException("Email already registered");
        }

        LocalDateTime now = LocalDateTime.now();

        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.EMPLOYER)
                .isEmailVerified(false)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        User savedUser = userRepository.save(user);

        // NOTE: MongoDB writes below are not wrapped in a multi-document transaction.
        // These catch blocks are compensating (manual) rollbacks only, acceptable for
        // this phase but should be replaced with proper transactional handling once a
        // replica-set-backed transaction manager is available.

        EmployerProfile employerProfile;
        try {
            employerProfile = EmployerProfile.builder()
                    .userId(savedUser.getId())
                    .companyName(request.getCompanyName())
                    .companyAddress(request.getCompanyAddress())
                    .phoneNumber(request.getPhoneNumber())
                    .companySize(request.getCompanySize())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            employerProfile = employerProfileRepository.save(employerProfile);
        } catch (RuntimeException exception) {
            userRepository.deleteById(savedUser.getId());
            throw exception;
        }

        OtpService.GeneratedOtp generatedOtp;
        try {
            generatedOtp = otpService.generateAndStoreOtp(savedUser.getEmail(), OtpPurpose.EMAIL_VERIFICATION);
        } catch (RuntimeException exception) {
            employerProfileRepository.deleteById(employerProfile.getId());
            userRepository.deleteById(savedUser.getId());
            throw exception;
        }

        return EmployerRegistrationResponse.builder()
                .userId(savedUser.getId())
                .employerProfileId(employerProfile.getId())
                .email(savedUser.getEmail())
                .companyName(employerProfile.getCompanyName())
                .companySize(employerProfile.getCompanySize())
                .isEmailVerified(savedUser.getIsEmailVerified())
                .accountStatus(savedUser.getAccountStatus())
                .devOtp(exposeDevOtp ? generatedOtp.rawOtp() : null)
                .createdAt(savedUser.getCreatedAt())
                .build();
    }

    public JobSeekerRegistrationResponse registerJobSeeker(JobSeekerRegistrationRequest request) {
        if (!request.getPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("Password and confirm password do not match");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmail(normalizedEmail)) {
            throw new BusinessException("Email already registered");
        }

        LocalDateTime now = LocalDateTime.now();

        User user = User.builder()
                .email(normalizedEmail)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .role(UserRole.JOB_SEEKER)
                .isEmailVerified(false)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();

        User savedUser = userRepository.save(user);

        // NOTE: same manual compensating-rollback caveat as registerEmployer above -
        // not a real multi-document transaction, acceptable for this phase.

        JobSeekerProfile jobSeekerProfile;
        try {
            jobSeekerProfile = JobSeekerProfile.builder()
                    .userId(savedUser.getId())
                    .firstName(request.getFirstName())
                    .lastName(request.getLastName())
                    .address(request.getAddress())
                    .phoneNumber(request.getPhoneNumber())
                    .skills(request.getSkills())
                    .preferredJobRoles(request.getPreferredJobRoles())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            jobSeekerProfile = jobSeekerProfileRepository.save(jobSeekerProfile);
        } catch (RuntimeException exception) {
            userRepository.deleteById(savedUser.getId());
            throw exception;
        }

        OtpService.GeneratedOtp generatedOtp;
        try {
            generatedOtp = otpService.generateAndStoreOtp(savedUser.getEmail(), OtpPurpose.EMAIL_VERIFICATION);
        } catch (RuntimeException exception) {
            jobSeekerProfileRepository.deleteById(jobSeekerProfile.getId());
            userRepository.deleteById(savedUser.getId());
            throw exception;
        }

        return JobSeekerRegistrationResponse.builder()
                .userId(savedUser.getId())
                .jobSeekerProfileId(jobSeekerProfile.getId())
                .email(savedUser.getEmail())
                .firstName(jobSeekerProfile.getFirstName())
                .lastName(jobSeekerProfile.getLastName())
                .isEmailVerified(savedUser.getIsEmailVerified())
                .accountStatus(savedUser.getAccountStatus())
                .devOtp(exposeDevOtp ? generatedOtp.rawOtp() : null)
                .createdAt(savedUser.getCreatedAt())
                .build();
    }

    public VerifyOtpResponse verifyOtp(VerifyOtpRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new BusinessException("User not found"));

        OtpVerification otpVerification =
                otpService.verifyOtp(request.getEmail(), OtpPurpose.EMAIL_VERIFICATION, request.getOtp());

        // NOTE: same manual compensating-rollback caveat as registerEmployer above -
        // not a real multi-document transaction. If the User update fails after the
        // OTP was already marked verified, revert the OTP so the same code can still
        // be retried before it expires.
        try {
            user.setIsEmailVerified(true);
            user.setUpdatedAt(LocalDateTime.now());
            user = userRepository.save(user);
        } catch (RuntimeException exception) {
            otpService.revertVerification(otpVerification);
            throw exception;
        }

        return VerifyOtpResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .isEmailVerified(user.getIsEmailVerified())
                .accountStatus(user.getAccountStatus())
                .verifiedAt(otpVerification.getVerifiedAt())
                .build();
    }

    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.getEmail() == null ? null : request.getEmail().trim().toLowerCase();
        String normalizedLoginId = request.getLoginId() == null ? null : request.getLoginId().trim().toUpperCase();

        User user = normalizedLoginId != null && !normalizedLoginId.isBlank()
                ? userRepository.findByLoginId(normalizedLoginId).orElse(null)
                : userRepository.findByEmail(normalizedEmail).orElse(null);

        // Same generic message and status regardless of whether the email exists
        // or the password is wrong, so a login attempt can't be used to discover
        // which registered emails exist.
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            throw new BusinessException("Invalid email or password");
        }

        if (!Boolean.TRUE.equals(user.getIsEmailVerified())) {
            throw new BusinessException("Please verify your email before logging in.");
        }

        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new BusinessException("Your account is not active. Please contact support.");
        }

        String accessToken = jwtService.generateToken(user.getId(), user.getEmail(), user.getRole().name());

        return LoginResponse.builder()
                .userId(user.getId())
                .email(user.getEmail())
                .loginId(user.getLoginId())
                .organizationId(user.getOrganizationId())
                .role(user.getRole())
                .accountStatus(user.getAccountStatus())
                .isEmailVerified(user.getIsEmailVerified())
                .mustChangePassword(Boolean.TRUE.equals(user.getMustChangePassword()))
                .accessToken(accessToken)
                .tokenType("Bearer")
                .build();
    }

    public void forgotPassword(ForgotPasswordRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();

        userRepository.findByEmail(normalizedEmail).ifPresent(user -> {
            try {
                otpService.generatePasswordResetOtpIfAllowed(user.getEmail())
                        .ifPresent(generated -> devPasswordResetOtpLogger.output(
                                user.getEmail(), generated.rawOtp(), generated.getExpiresAt()));
            } catch (RuntimeException ignored) {
                // Keep the public response identical for known and unknown accounts,
                // including when OTP persistence or delivery is temporarily unavailable.
            }
        });
    }

    public void verifyResetOtp(VerifyResetOtpRequest request) {
        String normalizedEmail = request.getEmail().trim().toLowerCase();
        otpService.checkOtpValidity(normalizedEmail, OtpPurpose.PASSWORD_RESET, request.getOtp());
    }

    public void resetPassword(ResetPasswordRequest request) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("New password and confirm password do not match");
        }

        String normalizedEmail = request.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(normalizedEmail)
                .orElseThrow(() -> new BusinessException("Invalid or expired reset request"));

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("New password must be different from the current password");
        }

        OtpVerification otpVerification =
                otpService.verifyOtp(normalizedEmail, OtpPurpose.PASSWORD_RESET, request.getOtp());

        try {
            user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
            user.setUpdatedAt(LocalDateTime.now());
            userRepository.save(user);
        } catch (RuntimeException exception) {
            otpService.revertVerification(otpVerification);
            throw exception;
        }
    }

    public void changePassword(String authenticatedUserId, ChangePasswordRequest request) {
        changePassword(authenticatedUserId, request, false);
    }

    public void changeFirstLoginPassword(String authenticatedUserId, ChangePasswordRequest request) {
        changePassword(authenticatedUserId, request, true);
    }

    /**
     * Changes an active Company Admin's login email and keeps the organization signatory address in sync.
     * The current password is required because the new address is intentionally not OTP-verified in this flow.
     */
    public LoginResponse changeCompanyAdminEmail(String authenticatedUserId, ChangeAccountEmailRequest request) {
        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        if (user.getRole() != UserRole.COMPANY_ADMIN || user.getAccountStatus() != AccountStatus.ACTIVE
                || user.getOrganizationId() == null || user.getOrganizationId().isBlank()) {
            throw new BusinessException("Only an active Company Admin can change this account email");
        }
        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Current password is incorrect");
        }

        String nextEmail = request.getNewEmail().trim().toLowerCase();
        if (nextEmail.equalsIgnoreCase(user.getEmail())) {
            throw new BusinessException("New email must be different from the current email");
        }
        if (userRepository.existsByEmail(nextEmail)) {
            throw new BusinessException("Email already registered");
        }

        Organization organization = organizationRepository.findById(user.getOrganizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        String previousEmail = user.getEmail();
        String previousAdminEmail = organization.getAdminEmail();
        LocalDateTime now = LocalDateTime.now();
        organization.setAdminEmail(nextEmail);
        organization.setUpdatedAt(now);
        boolean userSaved = false;

        try {
            organizationRepository.save(organization);
            user.setEmail(nextEmail);
            user.setUpdatedAt(now);
            userRepository.save(user);
            userSaved = true;
        } catch (RuntimeException failure) {
            // Mongo writes are not transactional here. Restore the organization setting if the user save fails.
            if (!userSaved) {
                user.setEmail(previousEmail);
                organization.setAdminEmail(previousAdminEmail);
                organization.setUpdatedAt(LocalDateTime.now());
                try {
                    organizationRepository.save(organization);
                } catch (RuntimeException ignored) {
                    // The original persistence failure is more useful to the caller.
                }
            }
            throw failure;
        }

        auditService.record(AuditEvent.builder()
                .organizationId(user.getOrganizationId())
                .actorUserId(user.getId())
                .actorLoginId(user.getLoginId())
                .actorRole(user.getRole())
                .actionType(AuditActionType.ACCOUNT_EMAIL_CHANGED)
                .entityType("USER")
                .entityId(user.getId())
                .entityDisplayName(user.getLoginId())
                .description("Changed account email")
                .source("MY_HR")
                .build());

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

    private void changePassword(
            String authenticatedUserId, ChangePasswordRequest request, boolean requireTemporaryPasswordState) {
        if (!request.getNewPassword().equals(request.getConfirmPassword())) {
            throw new BusinessException("New password and confirm password do not match");
        }

        User user = userRepository.findById(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (requireTemporaryPasswordState && !Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw new BusinessException("First-login password change is not required");
        }

        if (!passwordEncoder.matches(request.getCurrentPassword(), user.getPasswordHash())) {
            throw new BusinessException("Current password is incorrect");
        }

        if (passwordEncoder.matches(request.getNewPassword(), user.getPasswordHash())) {
            throw new BusinessException("New password must be different from the current password");
        }

        boolean wasFirstLogin = Boolean.TRUE.equals(user.getMustChangePassword());
        user.setPasswordHash(passwordEncoder.encode(request.getNewPassword()));
        user.setMustChangePassword(false);
        user.setUpdatedAt(LocalDateTime.now());
        userRepository.save(user);

        recordPasswordChange(user, wasFirstLogin);
    }

    private void recordPasswordChange(User user, boolean firstLogin) {
        if (user.getOrganizationId() == null || user.getOrganizationId().isBlank()) {
            return;
        }
        auditService.record(AuditEvent.builder()
                .organizationId(user.getOrganizationId())
                .actorUserId(user.getId())
                .actorLoginId(user.getLoginId())
                .actorRole(user.getRole())
                .actionType(AuditActionType.PASSWORD_CHANGED)
                .entityType("USER")
                .entityId(user.getId())
                .entityDisplayName(user.getLoginId())
                .description(firstLogin ? "Set password on first login" : "Changed account password")
                .updatedValue(java.util.Map.of("firstLogin", firstLogin))
                .source("MY_HR")
                .build());
    }
}
