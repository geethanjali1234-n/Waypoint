package com.waypoint.service;

import com.waypoint.dto.auth.OrganizationRegistrationRequest;
import com.waypoint.dto.auth.OrganizationRegistrationVerificationRequest;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.entity.OtpVerification;
import com.waypoint.entity.PendingOrganizationRegistration;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OrganizationRegistrationStatus;
import com.waypoint.enums.OrganizationStatus;
import com.waypoint.enums.PayrollPeriod;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.PendingOrganizationRegistrationRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationRegistrationServiceTest {

    @Mock PendingOrganizationRegistrationRepository pendingRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @Mock OrganizationSettingsRepository organizationSettingsRepository;
    @Mock OtpService otpService;
    @Mock PasswordEncoder passwordEncoder;
    @Mock TemporaryPasswordGenerator temporaryPasswordGenerator;
    @Mock OrganizationRegistrationCredentialDelivery credentialDelivery;
    @Mock FileStorageService fileStorageService;
    private OrganizationRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new OrganizationRegistrationService(
                pendingRepository, organizationRepository, userRepository, organizationSettingsRepository, otpService, passwordEncoder,
                temporaryPasswordGenerator, credentialDelivery, fileStorageService);
    }

    @Test
    void startsRegistrationWithGeneratedLoginAndNoStoredCredentials() {
        OrganizationRegistrationRequest request = request();
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.start(request);

        ArgumentCaptor<PendingOrganizationRegistration> captor =
                ArgumentCaptor.forClass(PendingOrganizationRegistration.class);
        verify(pendingRepository).save(captor.capture());
        PendingOrganizationRegistration pending = captor.getValue();
        assertThat(pending.getOrganizationEmail()).isEqualTo("contact@acme.example");
        assertThat(pending.getAdminEmail()).isEqualTo("admin@acme.example");
        assertThat(pending.getOrganizationCode()).isEqualTo("ACMEH");
        assertThat(pending.getAdminLoginId()).isEqualTo("ACMEHADMIN");
        assertThat(PendingOrganizationRegistration.class.getDeclaredFields())
                .extracting(java.lang.reflect.Field::getName)
                .doesNotContain("password", "passwordHash", "temporaryPassword");
        assertThat(response.registrationId()).isEqualTo(pending.getId());
        verify(credentialDelivery).requireAvailable();
        verify(otpService).generateOrganizationRegistrationOtpIfAllowed("admin@acme.example");
        verify(userRepository, never()).insert(any(User.class));
        verify(organizationRepository, never()).insert(any(Organization.class));
    }

    @Test
    void generatedAdminLoginRetriesWhenInitialCandidateAlreadyExists() {
        User existing = User.builder().id("existing").build();
        when(userRepository.findByLoginId(anyString()))
                .thenReturn(Optional.of(existing), Optional.empty());
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.start(request());

        ArgumentCaptor<String> candidates = ArgumentCaptor.forClass(String.class);
        verify(userRepository, atLeast(2)).findByLoginId(candidates.capture());
        ArgumentCaptor<PendingOrganizationRegistration> pending =
                ArgumentCaptor.forClass(PendingOrganizationRegistration.class);
        verify(pendingRepository).save(pending.capture());
        assertThat(pending.getValue().getAdminLoginId())
                .isNotEqualTo(candidates.getAllValues().get(0))
                .isEqualTo(candidates.getAllValues().get(1))
                .isEqualTo("ACMEH2ADMIN");
        assertThat(pending.getValue().getOrganizationCode()).isEqualTo("ACMEH2");
        assertThat(candidates.getAllValues().get(0)).isEqualTo("ACMEHADMIN");
    }

    @Test
    void generatedOrganizationCodeIsNumberedWhenAnOrganizationAlreadyUsesIt() {
        when(organizationRepository.findByOrganizationCode("ACMEH"))
                .thenReturn(Optional.of(Organization.builder().id("other-org").organizationCode("ACMEH").build()));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.start(request());

        ArgumentCaptor<PendingOrganizationRegistration> pending =
                ArgumentCaptor.forClass(PendingOrganizationRegistration.class);
        verify(pendingRepository).save(pending.capture());
        assertThat(pending.getValue().getOrganizationCode()).isEqualTo("ACMEH2");
        assertThat(pending.getValue().getAdminLoginId()).isEqualTo("ACMEH2ADMIN");
    }

    @Test
    void generatedOrganizationCodeSkipsCodesReservedByAnotherPendingRegistration() {
        when(pendingRepository.existsByOrganizationCodeAndStatusAndExpiresAtAfter(
                eq("ACMEH"), eq(OrganizationRegistrationStatus.PENDING_VERIFICATION), any()))
                .thenReturn(true);
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.start(request());

        ArgumentCaptor<PendingOrganizationRegistration> pending =
                ArgumentCaptor.forClass(PendingOrganizationRegistration.class);
        verify(pendingRepository).save(pending.capture());
        assertThat(pending.getValue().getOrganizationCode()).isEqualTo("ACMEH2");
    }

    @Test
    void unavailableCredentialDeliveryPreventsPendingRegistrationAndOtp() {
        org.mockito.Mockito.doThrow(new BusinessException("not configured"))
                .when(credentialDelivery).requireAvailable();

        assertThatThrownBy(() -> service.start(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not configured");

        verifyNoInteractions(otpService);
        verify(pendingRepository, never()).save(any());
    }

    @Test
    void rejectsDuplicateAdminIdentityBeforeSendingOtp() {
        User existing = User.builder().id("existing").email("admin@acme.example").build();
        when(userRepository.findByEmail("admin@acme.example")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.start(request()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Admin email");

        verifyNoInteractions(otpService);
        verify(pendingRepository, never()).save(any());
    }

    @Test
    void completesVerifiedRegistrationWithOneActiveOrganizationAndOneCompanyAdmin() {
        PendingOrganizationRegistration pending = pending();
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(otpService.verifyOtp(any(), any(), any())).thenReturn(OtpVerification.builder().id("otp-1").build());
        when(temporaryPasswordGenerator.generate()).thenReturn("TempPassword9!");
        when(passwordEncoder.encode("TempPassword9!")).thenReturn("bcrypt-hash");
        when(organizationRepository.insert(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.insert(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.complete(verification());

        ArgumentCaptor<Organization> organization = ArgumentCaptor.forClass(Organization.class);
        ArgumentCaptor<User> admin = ArgumentCaptor.forClass(User.class);
        verify(organizationRepository).insert(organization.capture());
        verify(userRepository).insert(admin.capture());
        assertThat(organization.getValue().getStatus()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(admin.getValue().getRole()).isEqualTo(UserRole.COMPANY_ADMIN);
        assertThat(admin.getValue().getOrganizationId()).isEqualTo(organization.getValue().getId());
        assertThat(admin.getValue().getIsEmailVerified()).isTrue();
        assertThat(admin.getValue().getMustChangePassword()).isTrue();
        assertThat(admin.getValue().getPasswordHash()).isEqualTo("bcrypt-hash");
        assertThat(admin.getValue().getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.adminLoginId()).isEqualTo("ACMEADMIN");
        assertThat(pending.getStatus()).isEqualTo(OrganizationRegistrationStatus.COMPLETED);
        // Recipient is the verified Admin email stored on the registration, sent once, after the Admin exists.
        verify(credentialDelivery).deliver(
                "admin@acme.example", "Acme Health", "Jane Admin", "ACMEADMIN", "TempPassword9!");

        var ordered = inOrder(credentialDelivery, organizationRepository, userRepository);
        ordered.verify(organizationRepository).insert(any(Organization.class));
        ordered.verify(userRepository).insert(any(User.class));
        ordered.verify(credentialDelivery).deliver(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void startKeepsTheFullOrganizationProfileOnThePendingRegistration() {
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.start(request());

        ArgumentCaptor<PendingOrganizationRegistration> captor =
                ArgumentCaptor.forClass(PendingOrganizationRegistration.class);
        verify(pendingRepository).save(captor.capture());
        PendingOrganizationRegistration pending = captor.getValue();
        assertThat(pending.getEinNumber()).isEqualTo("12-3456789");
        assertThat(pending.getEVerifyNumber()).isEqualTo("EV-1001");
        assertThat(pending.getDescription()).isEqualTo("Regional clinics");
        assertThat(pending.getPayrollPeriod()).isEqualTo(PayrollPeriod.MONTHLY);
        assertThat(pending.getAdminName()).isEqualTo("Jane Admin");
        assertThat(pending.getAdminPhoneNumber()).isEqualTo("555-0101");
        verify(organizationSettingsRepository, never()).save(any());
    }

    @Test
    void completionWritesTheOrganizationProfileAndItsPayrollSettings() {
        PendingOrganizationRegistration pending = pending();
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(otpService.verifyOtp(any(), any(), any())).thenReturn(OtpVerification.builder().id("otp-1").build());
        when(temporaryPasswordGenerator.generate()).thenReturn("TempPassword9!");
        when(passwordEncoder.encode("TempPassword9!")).thenReturn("bcrypt-hash");
        when(organizationRepository.insert(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.insert(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(verification());

        ArgumentCaptor<Organization> organization = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).insert(organization.capture());
        assertThat(organization.getValue().getEinNumber()).isEqualTo("12-3456789");
        assertThat(organization.getValue().getEVerifyNumber()).isEqualTo("EV-1001");
        assertThat(organization.getValue().getDescription()).isEqualTo("Regional clinics");
        assertThat(organization.getValue().getAdminName()).isEqualTo("Jane Admin");
        assertThat(organization.getValue().getAdminEmail()).isEqualTo("admin@acme.example");
        assertThat(organization.getValue().getAdminPhoneNumber()).isEqualTo("555-0101");
        ArgumentCaptor<OrganizationSettings> settings = ArgumentCaptor.forClass(OrganizationSettings.class);
        verify(organizationSettingsRepository).save(settings.capture());
        assertThat(settings.getValue().getOrganizationId()).isEqualTo("selfreg-org-registration");
        assertThat(settings.getValue().getPayrollPeriod()).isEqualTo(PayrollPeriod.MONTHLY);
    }

    @Test
    void recoveredCompletionDoesNotDuplicateExistingPayrollSettings() {
        PendingOrganizationRegistration pending = pending();
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(organizationRepository.findById(pending.getOrganizationId()))
                .thenReturn(Optional.of(matchingOrganization(pending)));
        when(userRepository.findById(pending.getAdminUserId())).thenReturn(Optional.of(User.builder()
                .id(pending.getAdminUserId()).organizationId(pending.getOrganizationId())
                .loginId(pending.getAdminLoginId()).email(pending.getAdminEmail()).role(UserRole.COMPANY_ADMIN).build()));
        when(organizationSettingsRepository.findByOrganizationId(pending.getOrganizationId()))
                .thenReturn(Optional.of(OrganizationSettings.builder().organizationId(pending.getOrganizationId()).build()));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(verification());

        verify(organizationSettingsRepository, never()).save(any());
        assertThat(pending.getStatus()).isEqualTo(OrganizationRegistrationStatus.COMPLETED);
    }

    private static final byte[] PNG_BYTES = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    @Test
    void validLogoIsStoredUnderTheNewOrganizationAndKeptOnThePendingRegistration() {
        var logo = new org.springframework.mock.web.MockMultipartFile("logo", "logo.png", "image/png", PNG_BYTES);
        when(fileStorageService.store(eq(logo), eq("company-logos"), anyString())).thenReturn("gridfs/logo-1/logo.png");
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.start(request(), logo);

        ArgumentCaptor<PendingOrganizationRegistration> captor =
                ArgumentCaptor.forClass(PendingOrganizationRegistration.class);
        verify(pendingRepository).save(captor.capture());
        assertThat(captor.getValue().getOrganizationLogoDocumentId()).isEqualTo("gridfs/logo-1/logo.png");
        verify(fileStorageService).store(logo, "company-logos", captor.getValue().getOrganizationId());
    }

    @Test
    void logoWhoseBytesAreNotAnImageIsRejectedBeforeAnythingIsSaved() {
        var fake = new org.springframework.mock.web.MockMultipartFile(
                "logo", "logo.png", "image/png", "<script>".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.start(request(), fake))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("PNG or JPEG");

        verifyNoInteractions(fileStorageService, otpService);
        verify(pendingRepository, never()).save(any());
    }

    @Test
    void oversizedOrNonImageLogoTypesAreRejected() {
        var gif = new org.springframework.mock.web.MockMultipartFile("logo", "logo.gif", "image/gif", PNG_BYTES);
        var huge = new org.springframework.mock.web.MockMultipartFile(
                "logo", "logo.png", "image/png", new byte[2 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> service.start(request(), gif)).hasMessageContaining("PNG or JPEG");
        assertThatThrownBy(() -> service.start(request(), huge)).hasMessageContaining("2 MB");
        verifyNoInteractions(fileStorageService);
    }

    @Test
    void logoIsRemovedWhenTheVerificationEmailCannotBeSent() {
        var logo = new org.springframework.mock.web.MockMultipartFile("logo", "logo.png", "image/png", PNG_BYTES);
        when(fileStorageService.store(any(), anyString(), anyString())).thenReturn("gridfs/logo-1/logo.png");
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(otpService.generateOrganizationRegistrationOtpIfAllowed(anyString()))
                .thenThrow(new BusinessException("Unable to send verification email. Please try again."));

        assertThatThrownBy(() -> service.start(request(), logo)).isInstanceOf(BusinessException.class);

        ArgumentCaptor<PendingOrganizationRegistration> captor =
                ArgumentCaptor.forClass(PendingOrganizationRegistration.class);
        verify(pendingRepository).save(captor.capture());
        verify(pendingRepository).deleteById(captor.getValue().getId());
        verify(fileStorageService).remove("company-logos", captor.getValue().getOrganizationId());
    }

    @Test
    void rejectedRegistrationNeverStoresTheLogo() {
        var logo = new org.springframework.mock.web.MockMultipartFile("logo", "logo.png", "image/png", PNG_BYTES);
        when(userRepository.findByEmail("admin@acme.example"))
                .thenReturn(Optional.of(User.builder().id("existing").email("admin@acme.example").build()));

        assertThatThrownBy(() -> service.start(request(), logo)).hasMessageContaining("Admin email");

        verify(fileStorageService, never()).store(any(), anyString(), anyString());
    }

    @Test
    void completionCarriesTheLogoOntoTheOrganization() {
        PendingOrganizationRegistration pending = pending();
        pending.setOrganizationLogoDocumentId("gridfs/logo-1/logo.png");
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(otpService.verifyOtp(any(), any(), any())).thenReturn(OtpVerification.builder().id("otp-1").build());
        when(temporaryPasswordGenerator.generate()).thenReturn("TempPassword9!");
        when(passwordEncoder.encode("TempPassword9!")).thenReturn("bcrypt-hash");
        when(organizationRepository.insert(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.insert(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(verification());

        ArgumentCaptor<Organization> organization = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).insert(organization.capture());
        assertThat(organization.getValue().getOrganizationLogoDocumentId()).isEqualTo("gridfs/logo-1/logo.png");
    }

    @Test
    void completedRetryReturnsSameResultWithoutReusingOtpOrCreatingRecords() {
        PendingOrganizationRegistration pending = pending();
        pending.setStatus(OrganizationRegistrationStatus.COMPLETED);
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));

        var response = service.complete(verification());

        assertThat(response.organizationId()).isEqualTo("selfreg-org-registration");
        verifyNoInteractions(otpService);
        verify(organizationRepository, never()).insert(any(Organization.class));
        verify(userRepository, never()).insert(any(User.class));
        verify(temporaryPasswordGenerator, never()).generate();
        verify(credentialDelivery, never()).deliver(any(), any(), any(), any(), any());
    }

    @Test
    void retryAfterOrganizationWriteResumesAdminCreationWithoutReusingConsumedOtp() {
        PendingOrganizationRegistration pending = pending();
        Organization existingOrganization = Organization.builder()
                .id(pending.getOrganizationId())
                .organizationCode(pending.getOrganizationCode())
                .organizationEmail(pending.getOrganizationEmail())
                .build();
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(organizationRepository.findById(pending.getOrganizationId()))
                .thenReturn(Optional.of(existingOrganization));
        when(organizationRepository.findByOrganizationEmail(pending.getOrganizationEmail()))
                .thenReturn(Optional.of(existingOrganization));
        when(temporaryPasswordGenerator.generate()).thenReturn("TempPassword9!");
        when(passwordEncoder.encode("TempPassword9!")).thenReturn("bcrypt-hash");
        when(userRepository.insert(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service.complete(verification());

        assertThat(response.adminLoginId()).isEqualTo("ACMEADMIN");
        verifyNoInteractions(otpService);
        verify(organizationRepository, never()).insert(any(Organization.class));
        verify(userRepository).insert(any(User.class));
        verify(credentialDelivery).deliver(
                "admin@acme.example", "Acme Health", "Jane Admin", "ACMEADMIN", "TempPassword9!");
    }

    @Test
    void organizationOnlyRetryUsesAndConfirmsOnlyTheNewRecoveryAttempt() {
        PendingOrganizationRegistration pending = pending();
        Organization existingOrganization = matchingOrganization(pending);
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(organizationRepository.findById(pending.getOrganizationId()))
                .thenReturn(Optional.of(existingOrganization));
        when(organizationRepository.findByOrganizationEmail(pending.getOrganizationEmail()))
                .thenReturn(Optional.of(existingOrganization));
        when(temporaryPasswordGenerator.generate()).thenReturn("FirstTemp9!", "SecondTemp8?");
        when(passwordEncoder.encode("FirstTemp9!")).thenReturn("first-hash");
        when(passwordEncoder.encode("SecondTemp8?")).thenReturn("second-hash");
        when(userRepository.insert(any(User.class)))
                .thenThrow(new IllegalStateException("interrupted write"))
                .thenAnswer(invocation -> invocation.getArgument(0));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.complete(verification()))
                .isInstanceOf(IllegalStateException.class);
        service.complete(verification());

        ArgumentCaptor<User> attempts = ArgumentCaptor.forClass(User.class);
        verify(userRepository, org.mockito.Mockito.times(2)).insert(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(User::getPasswordHash)
                .containsExactly("first-hash", "second-hash");
        // The interrupted first attempt never created an Admin, so only the second password is emailed.
        verify(credentialDelivery, org.mockito.Mockito.times(1)).deliver(any(), any(), any(), any(), any());
        verify(credentialDelivery).deliver(
                "admin@acme.example", "Acme Health", "Jane Admin", "ACMEADMIN", "SecondTemp8?");
    }

    @Test
    void emailFailureRemovesNewAdminAndOrganizationAndRestoresOtp() {
        PendingOrganizationRegistration pending = pending();
        OtpVerification otp = OtpVerification.builder().id("otp-1").build();
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(otpService.verifyOtp(any(), any(), any())).thenReturn(otp);
        when(temporaryPasswordGenerator.generate()).thenReturn("TempPassword9!");
        when(passwordEncoder.encode("TempPassword9!")).thenReturn("bcrypt-hash");
        when(organizationRepository.insert(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.insert(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        org.mockito.Mockito.doThrow(new BusinessException("could not email"))
                .when(credentialDelivery).deliver(any(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.complete(verification()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("could not email");

        verify(userRepository).deleteById("selfreg-admin-registration");
        verify(organizationRepository).deleteById("selfreg-org-registration");
        verify(otpService).revertVerification(otp);
        verify(pendingRepository, never()).save(any());
        assertThat(pending.getStatus()).isEqualTo(OrganizationRegistrationStatus.PENDING_VERIFICATION);
    }

    @Test
    void repeatedCompletionAfterConcurrentAdminCreationDoesNotGenerateOrReplaceCredentials() {
        PendingOrganizationRegistration pending = pending();
        Organization organization = matchingOrganization(pending);
        User admin = User.builder()
                .id(pending.getAdminUserId()).organizationId(pending.getOrganizationId())
                .loginId(pending.getAdminLoginId()).email(pending.getAdminEmail())
                .passwordHash("winning-hash").role(UserRole.COMPANY_ADMIN).build();
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(organizationRepository.findById(pending.getOrganizationId())).thenReturn(Optional.of(organization));
        when(userRepository.findById(pending.getAdminUserId())).thenReturn(Optional.of(admin));
        when(pendingRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.complete(verification());

        assertThat(admin.getPasswordHash()).isEqualTo("winning-hash");
        verify(temporaryPasswordGenerator, never()).generate();
        verify(passwordEncoder, never()).encode(anyString());
        verify(userRepository, never()).insert(any(User.class));
        verify(credentialDelivery, never()).deliver(any(), any(), any(), any(), any());
    }

    @Test
    void competingCompletionCannotReplaceWinningAdminPasswordOrConfirmLosingAttempt() {
        PendingOrganizationRegistration pending = pending();
        Organization organization = matchingOrganization(pending);
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(organizationRepository.findById(pending.getOrganizationId())).thenReturn(Optional.of(organization));
        when(organizationRepository.findByOrganizationEmail(pending.getOrganizationEmail()))
                .thenReturn(Optional.of(organization));
        when(temporaryPasswordGenerator.generate()).thenReturn("LosingTemp7!");
        when(passwordEncoder.encode("LosingTemp7!")).thenReturn("losing-hash");
        when(userRepository.insert(any(User.class))).thenThrow(new DuplicateKeyException("concurrent winner"));
        when(userRepository.existsById(pending.getAdminUserId())).thenReturn(true);

        assertThatThrownBy(() -> service.complete(verification()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");

        verify(credentialDelivery, never()).deliver(any(), any(), any(), any(), any());
        verify(userRepository, never()).save(any(User.class));
        verify(userRepository, never()).deleteById(anyString());
    }

    @Test
    void adminSaveFailureCompensatesOnlyNewOrganizationAndRestoresOtp() {
        PendingOrganizationRegistration pending = pending();
        OtpVerification otp = OtpVerification.builder().id("otp-1").build();
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));
        when(otpService.verifyOtp(any(), any(), any())).thenReturn(otp);
        when(temporaryPasswordGenerator.generate()).thenReturn("TempPassword9!");
        when(passwordEncoder.encode("TempPassword9!")).thenReturn("bcrypt-hash");
        when(organizationRepository.insert(any(Organization.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.insert(any(User.class))).thenThrow(new IllegalStateException("write failed"));

        assertThatThrownBy(() -> service.complete(verification())).isInstanceOf(IllegalStateException.class);

        verify(organizationRepository).deleteById("selfreg-org-registration");
        verify(otpService).revertVerification(otp);
    }

    @Test
    void expiredPendingRegistrationCannotCreateRecords() {
        PendingOrganizationRegistration pending = pending();
        pending.setExpiresAt(LocalDateTime.now().minusSeconds(1));
        when(pendingRepository.findById("registration-1")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> service.complete(verification()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");

        verifyNoInteractions(otpService);
        verify(organizationRepository, never()).insert(any(Organization.class));
        verify(userRepository, never()).insert(any(User.class));
    }

    private OrganizationRegistrationRequest request() {
        OrganizationRegistrationRequest request = new OrganizationRegistrationRequest();
        request.setOrganizationName("Acme Health");
        request.setOrganizationEmail(" Contact@Acme.Example ");
        request.setPhoneNumber("555-0100");
        request.setState("IL");
        request.setCity("Chicago");
        request.setStreetAddress("100 Main St");
        request.setZipCode("60601");
        request.setOrganizationSize(CompanySize.MEDIUM);
        request.setIndustry("Healthcare");
        request.setWebsite("https://acme.example");
        request.setDescription(" Regional clinics ");
        request.setEinNumber("123456789");
        request.setEVerifyNumber("EV-1001");
        request.setPayrollPeriod(PayrollPeriod.MONTHLY);
        request.setAdminName(" Jane Admin ");
        request.setAdminEmail(" Admin@Acme.Example ");
        request.setAdminPhoneNumber(" 555-0101 ");
        return request;
    }

    private PendingOrganizationRegistration pending() {
        return PendingOrganizationRegistration.builder()
                .id("registration-1")
                .organizationId("selfreg-org-registration")
                .organizationCode("ACME-123456")
                .organizationName("Acme Health")
                .organizationEmail("contact@acme.example")
                .phoneNumber("555-0100").state("IL").city("Chicago")
                .streetAddress("100 Main St").zipCode("60601")
                .organizationSize(CompanySize.MEDIUM)
                .adminUserId("selfreg-admin-registration")
                .adminLoginId("ACMEADMIN")
                .adminEmail("admin@acme.example")
                .description("Regional clinics").einNumber("12-3456789").eVerifyNumber("EV-1001")
                .payrollPeriod(PayrollPeriod.MONTHLY).adminName("Jane Admin").adminPhoneNumber("555-0101")
                .status(OrganizationRegistrationStatus.PENDING_VERIFICATION)
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .updatedAt(LocalDateTime.now().minusMinutes(1))
                .expiresAt(LocalDateTime.now().plusMinutes(29))
                .build();
    }

    private Organization matchingOrganization(PendingOrganizationRegistration pending) {
        return Organization.builder()
                .id(pending.getOrganizationId())
                .organizationCode(pending.getOrganizationCode())
                .organizationEmail(pending.getOrganizationEmail())
                .build();
    }

    private OrganizationRegistrationVerificationRequest verification() {
        OrganizationRegistrationVerificationRequest request = new OrganizationRegistrationVerificationRequest();
        request.setRegistrationId("registration-1");
        request.setOtp("123456");
        return request;
    }
}
