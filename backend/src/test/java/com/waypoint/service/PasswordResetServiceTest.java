package com.waypoint.service;

import com.waypoint.dto.auth.ForgotPasswordRequest;
import com.waypoint.dto.auth.ResetPasswordRequest;
import com.waypoint.dto.auth.VerifyResetOtpRequest;
import com.waypoint.entity.OtpVerification;
import com.waypoint.entity.User;
import com.waypoint.enums.OtpPurpose;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock UserRepository userRepository;
    @Mock EmployerProfileRepository employerProfileRepository;
    @Mock JobSeekerProfileRepository jobSeekerProfileRepository;
    @Mock OtpService otpService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock JwtService jwtService;
    @Mock DevPasswordResetOtpLogger devPasswordResetOtpLogger;
    @InjectMocks AuthService authService;

    @Test
    void forgotPassword_normalizesKnownEmailAndSendsResetOtp() {
        User user = user("user@example.com");
        ForgotPasswordRequest request = forgotRequest(" User@Example.com ");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));

        when(otpService.generatePasswordResetOtpIfAllowed("user@example.com"))
                .thenReturn(Optional.of(generatedOtp()));

        authService.forgotPassword(request);

        verify(otpService).generatePasswordResetOtpIfAllowed("user@example.com");
        verify(devPasswordResetOtpLogger).output(
                "user@example.com", "123456", generatedOtp().getExpiresAt());
    }

    @Test
    void companyAdminUsesCanonicalAccountEmailForPasswordRecovery() {
        User admin = user("admin@example.com");
        admin.setRole(UserRole.COMPANY_ADMIN);
        when(userRepository.findByEmail("admin@example.com")).thenReturn(Optional.of(admin));
        when(otpService.generatePasswordResetOtpIfAllowed("admin@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword(forgotRequest(" Admin@Example.com "));

        verify(otpService).generatePasswordResetOtpIfAllowed("admin@example.com");
    }

    @Test
    void forgotPassword_unknownEmailDoesNotRevealAccountOrSendOtp() {
        ForgotPasswordRequest request = forgotRequest("missing@example.com");
        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword(request);

        verify(otpService, never()).generatePasswordResetOtpIfAllowed(any());
        verify(devPasswordResetOtpLogger, never()).output(any(), any(), any());
    }

    @Test
    void forgotPasswordWithinCooldownDoesNotExposeAnotherOtp() {
        User user = user("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(otpService.generatePasswordResetOtpIfAllowed("user@example.com")).thenReturn(Optional.empty());

        authService.forgotPassword(forgotRequest("user@example.com"));

        verify(devPasswordResetOtpLogger, never()).output(any(), any(), any());
    }

    @Test
    void forgotPasswordDeliveryFailureStillReturnsWithoutRevealingKnownAccount() {
        User user = user("user@example.com");
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(otpService.generatePasswordResetOtpIfAllowed("user@example.com"))
                .thenThrow(new IllegalStateException("mail unavailable"));

        authService.forgotPassword(forgotRequest("user@example.com"));

        verify(devPasswordResetOtpLogger, never()).output(any(), any(), any());
    }

    @Test
    void verifyResetOtpChecksPasswordResetPurposeWithoutConsumingCode() {
        VerifyResetOtpRequest request = new VerifyResetOtpRequest();
        request.setEmail(" User@Example.com ");
        request.setOtp("123456");

        authService.verifyResetOtp(request);

        verify(otpService).checkOtpValidity("user@example.com", OtpPurpose.PASSWORD_RESET, "123456");
    }

    @Test
    void resetPasswordConsumesOtpAndStoresEncodedPassword() {
        User user = user("user@example.com");
        ResetPasswordRequest request = resetRequest("NewPassword1", "NewPassword1");
        OtpVerification otp = OtpVerification.builder().id("otp-1").build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("NewPassword1", "old-hash")).thenReturn(false);
        when(otpService.verifyOtp("user@example.com", OtpPurpose.PASSWORD_RESET, "123456")).thenReturn(otp);
        when(passwordEncoder.encode("NewPassword1")).thenReturn("new-hash");

        authService.resetPassword(request);

        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(otpService).verifyOtp("user@example.com", OtpPurpose.PASSWORD_RESET, "123456");
    }

    @Test
    void resetPasswordRejectsMismatchedPasswordsBeforeUsingOtp() {
        ResetPasswordRequest request = resetRequest("NewPassword1", "DifferentPassword1");

        assertThatThrownBy(() -> authService.resetPassword(request))
                .hasMessage("New password and confirm password do not match");

        verify(otpService, never()).verifyOtp(any(), any(), any());
    }

    @Test
    void resetPasswordPersistenceFailureRestoresOtpForSafeRetry() {
        User user = user("user@example.com");
        ResetPasswordRequest request = resetRequest("NewPassword1", "NewPassword1");
        OtpVerification otp = OtpVerification.builder().id("otp-1").isVerified(true).build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("NewPassword1", "old-hash")).thenReturn(false);
        when(otpService.verifyOtp("user@example.com", OtpPurpose.PASSWORD_RESET, "123456")).thenReturn(otp);
        when(passwordEncoder.encode("NewPassword1")).thenReturn("new-hash");
        doThrow(new IllegalStateException("save failed")).when(userRepository).save(user);

        assertThatThrownBy(() -> authService.resetPassword(request))
                .isInstanceOf(IllegalStateException.class);

        verify(otpService).revertVerification(otp);
    }

    private ForgotPasswordRequest forgotRequest(String email) {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail(email);
        return request;
    }

    private ResetPasswordRequest resetRequest(String password, String confirmation) {
        ResetPasswordRequest request = new ResetPasswordRequest();
        request.setEmail("user@example.com");
        request.setOtp("123456");
        request.setNewPassword(password);
        request.setConfirmPassword(confirmation);
        return request;
    }

    private User user(String email) {
        return User.builder()
                .id("user-1")
                .email(email)
                .passwordHash("old-hash")
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private OtpService.GeneratedOtp generatedOtp() {
        OtpVerification stored = OtpVerification.builder()
                .id("otp-1")
                .email("user@example.com")
                .otpHash("otp-hash")
                .purpose(OtpPurpose.PASSWORD_RESET)
                .expiresAt(LocalDateTime.of(2026, 9, 10, 12, 10))
                .isVerified(false)
                .createdAt(LocalDateTime.of(2026, 9, 10, 12, 0))
                .build();
        return new OtpService.GeneratedOtp(stored, "123456");
    }
}
