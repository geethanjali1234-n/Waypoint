package com.waypoint.service;

import com.waypoint.entity.OtpVerification;
import com.waypoint.enums.OtpPurpose;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.OtpVerificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.data.mongodb.core.MongoOperations;
import com.mongodb.client.result.UpdateResult;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class OtpServiceTest {

    private static final String EMAIL = "jobSeeker@example.com";
    private static final String SUBJECT = "Your Waypoint verification code";
    private static final Pattern OTP_PATTERN = Pattern.compile(">(\\d{6})</span>");

    @Mock
    private OtpVerificationRepository otpVerificationRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private EmailService emailService;

    @Mock
    private MongoOperations mongoOperations;

    @Mock
    private DevOrganizationRegistrationOtpLogger devOrganizationRegistrationOtpLogger;

    private OtpService otpService;

    @BeforeEach
    void setUp() {
        otpService = new OtpService(
                otpVerificationRepository, passwordEncoder, emailService, new EmailTemplateService(), mongoOperations,
                devOrganizationRegistrationOtpLogger);
    }

    @Test
    void generateAndStoreOtp_persistsHashedOtpThenEmailsRecipient() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        when(otpVerificationRepository.save(any(OtpVerification.class)))
                .thenAnswer(invocation -> {
                    OtpVerification saved = invocation.getArgument(0);
                    saved.setId("otp-1");
                    return saved;
                });

        OtpVerification result = otpService.generateAndStoreOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION);

        ArgumentCaptor<OtpVerification> storedCaptor = ArgumentCaptor.forClass(OtpVerification.class);
        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        InOrder order = inOrder(otpVerificationRepository, emailService);
        order.verify(otpVerificationRepository).save(storedCaptor.capture());
        order.verify(emailService).sendHtmlEmail(eq(EMAIL), eq(SUBJECT), bodyCaptor.capture());

        String rawOtp = extractOtp(bodyCaptor.getValue());
        verify(passwordEncoder).encode(rawOtp);
        assertThat(bodyCaptor.getValue())
                .contains("WAYPOINT")
                .contains("Verify your email")
                .contains("Use the verification code below.")
                .contains("expires in 10 minutes")
                .contains("safely ignore this email");
        assertThat(storedCaptor.getValue().getOtpHash()).isEqualTo("hashed-otp");
        assertThat(storedCaptor.getValue().getOtpHash()).isNotEqualTo(rawOtp);
        assertThat(result.getOtpHash()).isEqualTo("hashed-otp");
    }

    @Test
    void generateAndStoreOtp_persistenceFails_doesNotSendEmail() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        when(otpVerificationRepository.save(any(OtpVerification.class)))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThatThrownBy(() -> otpService.generateAndStoreOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION))
                .isInstanceOf(IllegalStateException.class);

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void generateAndStoreOtp_mailFails_deletesOtpAndReturnsSafeFailure() {
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        when(otpVerificationRepository.save(any(OtpVerification.class)))
                .thenAnswer(invocation -> {
                    OtpVerification saved = invocation.getArgument(0);
                    saved.setId("otp-1");
                    return saved;
                });
        doThrow(new MailSendException("SMTP details"))
                .when(emailService).sendHtmlEmail(eq(EMAIL), eq(SUBJECT), anyString());

        assertThatThrownBy(() -> otpService.generateAndStoreOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Unable to send verification email. Please try again.")
                .hasMessageNotContaining("SMTP");

        ArgumentCaptor<OtpVerification> deletedCaptor = ArgumentCaptor.forClass(OtpVerification.class);
        verify(otpVerificationRepository).delete(deletedCaptor.capture());
        assertThat(deletedCaptor.getValue().getOtpHash()).isEqualTo("hashed-otp");
    }

    @Test
    void verifyOtp_validCode_marksOtpVerified() {
        OtpVerification stored = storedOtp(LocalDateTime.now().plusMinutes(5), false);
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.EMAIL_VERIFICATION)).thenReturn(Optional.of(stored));
        when(passwordEncoder.matches("123456", "hashed-otp")).thenReturn(true);
        OtpVerification consumed = storedOtp(stored.getExpiresAt(), true);
        consumed.setVerifiedAt(LocalDateTime.now());
        when(mongoOperations.findAndModify(any(), any(), any(), eq(OtpVerification.class))).thenReturn(consumed);

        OtpVerification result = otpService.verifyOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION, "123456");

        assertThat(result.getIsVerified()).isTrue();
        assertThat(result.getVerifiedAt()).isNotNull();
        verify(mongoOperations).findAndModify(any(), any(), any(), eq(OtpVerification.class));
    }

    @Test
    void verifyOtp_expiredCode_isRejectedWithoutSaving() {
        OtpVerification stored = storedOtp(LocalDateTime.now().minusSeconds(1), false);
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.EMAIL_VERIFICATION)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() ->
                otpService.verifyOtp(EMAIL, OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        verify(mongoOperations, never()).findAndModify(any(), any(), any(), eq(OtpVerification.class));
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void verifyOtpThrowsWhenNoOtpRequestFound() {
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                "jordan@example.com", OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                otpService.verifyOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");
    }

    @Test
    void verifyOtpThrowsWhenAlreadyVerified() {
        OtpVerification alreadyVerified =
                otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), true);

        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                "jordan@example.com", OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(alreadyVerified));

        assertThatThrownBy(() ->
                otpService.verifyOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");
    }

    @Test
    void verifyOtpThrowsWhenCodeDoesNotMatch() {
        OtpVerification pending = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), false);

        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                "jordan@example.com", OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(pending));
        when(passwordEncoder.matches("000000", "hashed-otp")).thenReturn(false);

        assertThatThrownBy(() ->
                otpService.verifyOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION, "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");
    }

    @Test
    void verifyOtpMarksVerifiedAndSavesOnSuccess() {
        OtpVerification pending = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), false);

        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                "jordan@example.com", OtpPurpose.EMAIL_VERIFICATION))
                .thenReturn(Optional.of(pending));
        when(passwordEncoder.matches("123456", "hashed-otp")).thenReturn(true);
        OtpVerification consumed = otpVerification("hashed-otp", pending.getExpiresAt(), true);
        consumed.setVerifiedAt(LocalDateTime.now());
        when(mongoOperations.findAndModify(any(), any(), any(), eq(OtpVerification.class))).thenReturn(consumed);

        OtpVerification result =
                otpService.verifyOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION, "123456");

        assertThat(result.getIsVerified()).isTrue();
        assertThat(result.getVerifiedAt()).isNotNull();
    }

    @Test
    void revertVerificationClearsVerifiedFlagAndTimestamp() {
        OtpVerification verified = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), true);
        verified.setVerifiedAt(LocalDateTime.now());

        otpService.revertVerification(verified);

        ArgumentCaptor<OtpVerification> captor = ArgumentCaptor.forClass(OtpVerification.class);
        verify(otpVerificationRepository).save(captor.capture());
        assertThat(captor.getValue().getIsVerified()).isFalse();
        assertThat(captor.getValue().getVerifiedAt()).isNull();
    }

    @Test
    void incorrectOtpAtomicallyIncrementsFailedAttempts() {
        OtpVerification pending = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), false);
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(pending));
        when(passwordEncoder.matches("000000", "hashed-otp")).thenReturn(false);

        assertThatThrownBy(() -> otpService.checkOtpValidity(EMAIL, OtpPurpose.PASSWORD_RESET, "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        verify(mongoOperations).updateFirst(any(), any(), eq(OtpVerification.class));
    }

    @Test
    void otpAtFiveFailedAttemptsIsBlockedBeforePasswordComparison() {
        OtpVerification blocked = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), false);
        blocked.setFailedAttempts(5);
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(blocked));

        assertThatThrownBy(() -> otpService.checkOtpValidity(EMAIL, OtpPurpose.PASSWORD_RESET, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @Test
    void fiveIncorrectAttemptsMakeOtpUnusable() {
        OtpVerification pending = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), false);
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(pending));
        when(passwordEncoder.matches("000000", "hashed-otp")).thenReturn(false);
        when(mongoOperations.updateFirst(any(), any(), eq(OtpVerification.class))).thenAnswer(invocation -> {
            pending.setFailedAttempts(pending.getFailedAttempts() + 1);
            return UpdateResult.acknowledged(1, 1L, null);
        });

        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> otpService.checkOtpValidity(EMAIL, OtpPurpose.PASSWORD_RESET, "000000"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("Invalid or expired verification code");
        }
        assertThatThrownBy(() -> otpService.checkOtpValidity(EMAIL, OtpPurpose.PASSWORD_RESET, "000000"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        verify(mongoOperations, times(5)).updateFirst(any(), any(), eq(OtpVerification.class));
        verify(passwordEncoder, times(5)).matches("000000", "hashed-otp");
    }

    @Test
    void consumedOtpCannotBeReused() {
        OtpVerification consumed = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), true);
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(consumed));

        assertThatThrownBy(() -> otpService.verifyOtp(EMAIL, OtpPurpose.PASSWORD_RESET, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        verify(mongoOperations, never()).findAndModify(any(), any(), any(), eq(OtpVerification.class));
    }

    @Test
    void resendWithinCooldownDoesNotCreateAnotherOtp() {
        OtpVerification recent = otpVerification("old-hash", LocalDateTime.now().plusMinutes(9), false);
        recent.setCreatedAt(LocalDateTime.now().minusSeconds(30));
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(recent));

        assertThat(otpService.generatePasswordResetOtpIfAllowed(EMAIL)).isEmpty();

        verify(otpVerificationRepository, never()).save(any());
        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void resendAfterCooldownCreatesReplacementAndInvalidatesPreviousOtp() {
        OtpVerification previous = otpVerification("old-hash", LocalDateTime.now().plusMinutes(5), false);
        previous.setCreatedAt(LocalDateTime.now().minusSeconds(61));
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(previous));
        when(passwordEncoder.encode(anyString())).thenReturn("new-hash");
        when(otpVerificationRepository.save(any(OtpVerification.class)))
                .thenAnswer(invocation -> {
                    OtpVerification value = invocation.getArgument(0);
                    if (value.getId() == null) value.setId("new-otp");
                    return value;
                });

        assertThat(otpService.generatePasswordResetOtpIfAllowed(EMAIL)).isPresent();

        assertThat(previous.getInvalidatedAt()).isNotNull();
        verify(emailService).sendHtmlEmail(eq(EMAIL), eq("Reset your Waypoint password"), anyString());
        verify(otpVerificationRepository, org.mockito.Mockito.times(2)).save(any(OtpVerification.class));
    }

    @Test
    void organizationRegistrationOtpUsesCooldownAndSendLimitWithoutExposingCode() {
        OtpVerification recent = otpVerification("old-hash", LocalDateTime.now().plusMinutes(9), false);
        recent.setCreatedAt(LocalDateTime.now().minusSeconds(30));
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.ORGANIZATION_REGISTRATION)).thenReturn(Optional.of(recent));

        assertThatThrownBy(() -> otpService.generateOrganizationRegistrationOtpIfAllowed(EMAIL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("wait");

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void organizationRegistrationOtpRejectsSixthSendWithinWindow() {
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.ORGANIZATION_REGISTRATION)).thenReturn(Optional.empty());
        when(otpVerificationRepository.countByEmailAndPurposeAndCreatedAtAfter(
                eq(EMAIL), eq(OtpPurpose.ORGANIZATION_REGISTRATION), any())).thenReturn(5L);

        assertThatThrownBy(() -> otpService.generateOrganizationRegistrationOtpIfAllowed(EMAIL))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("limit");

        verify(emailService, never()).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void organizationRegistrationOtpIsOfferedToDevelopmentTerminalWithoutChangingResponseStorage() {
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.ORGANIZATION_REGISTRATION)).thenReturn(Optional.empty());
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-otp");
        when(otpVerificationRepository.save(any(OtpVerification.class)))
                .thenAnswer(invocation -> {
                    OtpVerification value = invocation.getArgument(0);
                    value.setId("registration-otp");
                    return value;
                });

        OtpService.GeneratedOtp result = otpService.generateOrganizationRegistrationOtpIfAllowed(EMAIL);

        verify(devOrganizationRegistrationOtpLogger).output(
                eq(OtpPurpose.ORGANIZATION_REGISTRATION), eq(result.rawOtp()), eq(result.getExpiresAt()));
        assertThat(result.getOtpHash()).isEqualTo("hashed-otp").isNotEqualTo(result.rawOtp());
    }

    @Test
    void invalidatedOtpCannotBeUsedAndPurposesRemainIsolated() {
        OtpVerification invalidated = otpVerification("hashed-otp", LocalDateTime.now().plusMinutes(5), false);
        invalidated.setInvalidatedAt(LocalDateTime.now());
        when(otpVerificationRepository.findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.PASSWORD_RESET)).thenReturn(Optional.of(invalidated));

        assertThatThrownBy(() -> otpService.verifyOtp(EMAIL, OtpPurpose.PASSWORD_RESET, "123456"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired verification code");

        verify(otpVerificationRepository, never()).findTopByEmailAndPurposeOrderByCreatedAtDesc(
                EMAIL, OtpPurpose.EMAIL_VERIFICATION);
        verify(mongoOperations, never()).findAndModify(any(), any(), any(), eq(OtpVerification.class));
    }

    private OtpVerification storedOtp(LocalDateTime expiresAt, boolean verified) {
        return OtpVerification.builder()
                .id("otp-1")
                .email(EMAIL)
                .otpHash("hashed-otp")
                .purpose(OtpPurpose.EMAIL_VERIFICATION)
                .expiresAt(expiresAt)
                .isVerified(verified)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private OtpVerification otpVerification(String otpHash, LocalDateTime expiresAt, boolean verified) {
        return OtpVerification.builder()
                .id("otp-1")
                .email("jordan@example.com")
                .otpHash(otpHash)
                .purpose(OtpPurpose.EMAIL_VERIFICATION)
                .expiresAt(expiresAt)
                .isVerified(verified)
                .createdAt(LocalDateTime.now())
                .build();
    }

    private String extractOtp(String htmlBody) {
        Matcher matcher = OTP_PATTERN.matcher(htmlBody);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }
}
