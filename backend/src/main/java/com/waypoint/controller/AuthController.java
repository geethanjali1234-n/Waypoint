package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.auth.ChangePasswordRequest;
import com.waypoint.dto.auth.ChangeAccountEmailRequest;
import com.waypoint.dto.auth.JobSeekerRegistrationRequest;
import com.waypoint.dto.auth.JobSeekerRegistrationResponse;
import com.waypoint.dto.auth.EmployerRegistrationRequest;
import com.waypoint.dto.auth.EmployerRegistrationResponse;
import com.waypoint.dto.auth.ForgotPasswordRequest;
import com.waypoint.dto.auth.LoginRequest;
import com.waypoint.dto.auth.LoginResponse;
import com.waypoint.dto.auth.OrganizationRegistrationRequest;
import com.waypoint.dto.auth.OrganizationRegistrationResponse;
import com.waypoint.dto.auth.OrganizationRegistrationStartResponse;
import com.waypoint.dto.auth.OrganizationRegistrationVerificationRequest;
import com.waypoint.dto.auth.ResetPasswordRequest;
import com.waypoint.dto.auth.VerifyOtpRequest;
import com.waypoint.dto.auth.VerifyOtpResponse;
import com.waypoint.dto.auth.VerifyResetOtpRequest;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.AuthService;
import com.waypoint.service.OrganizationRegistrationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final OrganizationRegistrationService organizationRegistrationService;
    private final CurrentUserResolver currentUserResolver;

    @PostMapping("/register/employer")
    public ResponseEntity<ApiResponse<EmployerRegistrationResponse>> registerEmployer(
            @Valid @RequestBody EmployerRegistrationRequest request) {

        EmployerRegistrationResponse response = authService.registerEmployer(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Employer registered successfully", response));
    }

    @PostMapping("/register/job-seeker")
    public ResponseEntity<ApiResponse<JobSeekerRegistrationResponse>> registerJobSeeker(
            @Valid @RequestBody JobSeekerRegistrationRequest request) {

        JobSeekerRegistrationResponse response = authService.registerJobSeeker(request);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Job Seeker registered successfully", response));
    }

    @PostMapping(value = "/organization-registration", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<OrganizationRegistrationStartResponse>> startOrganizationRegistration(
            @Valid @RequestBody OrganizationRegistrationRequest request) {
        var response = organizationRegistrationService.start(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(true, "Verification code sent", response));
    }

    /** Same registration with an optional organization logo ("request" JSON part + "logo" file). */
    @PostMapping(value = "/organization-registration", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<OrganizationRegistrationStartResponse>> startOrganizationRegistrationWithLogo(
            @Valid @RequestPart("request") OrganizationRegistrationRequest request,
            @RequestPart(value = "logo", required = false) MultipartFile logo) {
        var response = organizationRegistrationService.start(request, logo);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new ApiResponse<>(true, "Verification code sent", response));
    }

    @PostMapping("/organization-registration/resend/{registrationId}")
    public ResponseEntity<ApiResponse<OrganizationRegistrationStartResponse>> resendOrganizationRegistrationOtp(
            @org.springframework.web.bind.annotation.PathVariable String registrationId) {
        var response = organizationRegistrationService.resend(registrationId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Verification code sent", response));
    }

    @PostMapping("/organization-registration/verify")
    public ResponseEntity<ApiResponse<OrganizationRegistrationResponse>> completeOrganizationRegistration(
            @Valid @RequestBody OrganizationRegistrationVerificationRequest request) {
        var response = organizationRegistrationService.complete(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Organization account created", response));
    }

    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<VerifyOtpResponse>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request) {

        VerifyOtpResponse response = authService.verifyOtp(request);

        return ResponseEntity.ok(new ApiResponse<>(true, "Email verified successfully", response));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {

        LoginResponse response = authService.login(request);

        return ResponseEntity.ok(new ApiResponse<>(true, "Login successful", response));
    }

    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(new ApiResponse<>(true,
                "If an account exists for that email, a reset code has been sent.", null));
    }

    @PostMapping("/verify-reset-otp")
    public ResponseEntity<ApiResponse<Void>> verifyResetOtp(@Valid @RequestBody VerifyResetOtpRequest request) {
        authService.verifyResetOtp(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Reset code verified successfully", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(new ApiResponse<>(true, "Password reset successfully", null));
    }

    @PatchMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            HttpServletRequest request,
            @Valid @RequestBody ChangePasswordRequest changePasswordRequest) {

        String userId = currentUserResolver.resolveUserId(request);
        authService.changePassword(userId, changePasswordRequest);

        return ResponseEntity.ok(new ApiResponse<>(true, "Password changed successfully", null));
    }

    @PatchMapping("/change-email")
    public ResponseEntity<ApiResponse<LoginResponse>> changeEmail(
            HttpServletRequest request,
            @Valid @RequestBody ChangeAccountEmailRequest changeEmailRequest) {

        String userId = currentUserResolver.resolveUserId(request);
        LoginResponse response = authService.changeCompanyAdminEmail(userId, changeEmailRequest);
        return ResponseEntity.ok(new ApiResponse<>(true, "Account email changed successfully", response));
    }

    @PatchMapping("/first-login/change-password")
    public ResponseEntity<ApiResponse<Void>> changeFirstLoginPassword(
            HttpServletRequest request,
            @Valid @RequestBody ChangePasswordRequest changePasswordRequest) {

        String userId = currentUserResolver.resolveUserId(request);
        authService.changeFirstLoginPassword(userId, changePasswordRequest);

        return ResponseEntity.ok(new ApiResponse<>(true, "First-login password changed successfully", null));
    }
}
