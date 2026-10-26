package com.waypoint.service;

import com.waypoint.dto.auth.ChangePasswordRequest;
import com.waypoint.dto.auth.ChangeAccountEmailRequest;
import com.waypoint.dto.auth.JobSeekerRegistrationRequest;
import com.waypoint.dto.auth.JobSeekerRegistrationResponse;
import com.waypoint.dto.auth.EmployerRegistrationRequest;
import com.waypoint.dto.auth.EmployerRegistrationResponse;
import com.waypoint.dto.auth.LoginRequest;
import com.waypoint.dto.auth.LoginResponse;
import com.waypoint.dto.auth.VerifyOtpRequest;
import com.waypoint.dto.auth.VerifyOtpResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.OtpVerification;
import com.waypoint.entity.User;
import com.waypoint.entity.Organization;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OtpPurpose;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final String USER_ID = "user-1";
    private static final String OLD_PASSWORD = "OldPassword1";
    private static final String NEW_PASSWORD = "NewPassword1";
    private static final String OLD_HASH = "old-hash";

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationRepository organizationRepository;

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private OtpService otpService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private DevPasswordResetOtpLogger devPasswordResetOtpLogger;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private AuthService authService;

    private User buildUser(UserRole role) {
        return User.builder()
                .id(USER_ID)
                .email("user@example.com")
                .passwordHash(OLD_HASH)
                .role(role)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private ChangePasswordRequest buildRequest(String currentPassword, String newPassword, String confirmPassword) {
        ChangePasswordRequest request = new ChangePasswordRequest();
        request.setCurrentPassword(currentPassword);
        request.setNewPassword(newPassword);
        request.setConfirmPassword(confirmPassword);
        return request;
    }

    private ChangeAccountEmailRequest emailRequest(String newEmail, String currentPassword) {
        ChangeAccountEmailRequest request = new ChangeAccountEmailRequest();
        request.setNewEmail(newEmail);
        request.setCurrentPassword(currentPassword);
        return request;
    }

    // ---- changeCompanyAdminEmail ----

    @Test
    void changeCompanyAdminEmail_updatesTheAccountOrganizationAndSession() {
        User user = buildUser(UserRole.COMPANY_ADMIN);
        user.setOrganizationId("org-1");
        user.setLoginId("TARDUSADMIN");
        Organization organization = Organization.builder().id("org-1").adminEmail("user@example.com").build();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(userRepository.existsByEmail("new.admin@example.com")).thenReturn(false);
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(organization));
        when(jwtService.generateToken(USER_ID, "new.admin@example.com", UserRole.COMPANY_ADMIN.name())).thenReturn("new-token");

        LoginResponse result = authService.changeCompanyAdminEmail(USER_ID,
                emailRequest(" New.Admin@Example.com ", OLD_PASSWORD));

        assertThat(user.getEmail()).isEqualTo("new.admin@example.com");
        assertThat(organization.getAdminEmail()).isEqualTo("new.admin@example.com");
        assertThat(result.getAccessToken()).isEqualTo("new-token");
        assertThat(result.getEmail()).isEqualTo("new.admin@example.com");
        verify(userRepository).save(user);
        verify(organizationRepository).save(organization);
        verify(auditService).record(any(AuditEvent.class));
    }

    @Test
    void changeCompanyAdminEmail_rejectsIncorrectPasswordWithoutSaving() {
        User user = buildUser(UserRole.COMPANY_ADMIN);
        user.setOrganizationId("org-1");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", OLD_HASH)).thenReturn(false);

        assertThatThrownBy(() -> authService.changeCompanyAdminEmail(USER_ID,
                emailRequest("new.admin@example.com", "wrong")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Current password is incorrect");

        verify(userRepository, never()).save(any());
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void changeCompanyAdminEmail_rejectsNonCompanyAdminAndDuplicateOrUnchangedEmails() {
        User employee = buildUser(UserRole.EMPLOYEE);
        employee.setOrganizationId("org-1");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(employee));

        assertThatThrownBy(() -> authService.changeCompanyAdminEmail(USER_ID,
                emailRequest("new.admin@example.com", OLD_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only an active Company Admin can change this account email");

        User admin = buildUser(UserRole.COMPANY_ADMIN);
        admin.setOrganizationId("org-1");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        assertThatThrownBy(() -> authService.changeCompanyAdminEmail(USER_ID,
                emailRequest("USER@example.com", OLD_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("New email must be different from the current email");

        when(userRepository.existsByEmail("new.admin@example.com")).thenReturn(true);
        assertThatThrownBy(() -> authService.changeCompanyAdminEmail(USER_ID,
                emailRequest("new.admin@example.com", OLD_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email already registered");
        verify(organizationRepository, never()).save(any());
    }

    private EmployerRegistrationRequest employerRequest() {
        EmployerRegistrationRequest request = new EmployerRegistrationRequest();
        request.setCompanyName("Waypoint Technologies");
        request.setEmail("Hiring@Waypoint.com");
        request.setCompanyAddress("Austin, Texas");
        request.setPhoneNumber("+15551234567");
        request.setCompanySize(CompanySize.SMALL);
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        return request;
    }

    private JobSeekerRegistrationRequest jobSeekerRequest() {
        JobSeekerRegistrationRequest request = new JobSeekerRegistrationRequest();
        request.setFirstName("Jordan");
        request.setLastName("Rivera");
        request.setEmail("Jordan@Example.com");
        request.setAddress("Austin, Texas");
        request.setPhoneNumber("+15551234567");
        request.setSkills(List.of("Java", "Spring", "MongoDB"));
        request.setPreferredJobRoles(List.of("Backend Developer"));
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        return request;
    }

    private User user(String id, String email, UserRole role, boolean verified, AccountStatus status) {
        return User.builder()
                .id(id)
                .email(email)
                .passwordHash("hashed-password")
                .role(role)
                .isEmailVerified(verified)
                .accountStatus(status)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private void doThrowOnGenerateOtp() {
        when(otpService.generateAndStoreOtp(anyString(), any(OtpPurpose.class)))
                .thenThrow(new RuntimeException("otp store failed"));
    }

    // ---- changePassword ----

    @Test
    void changePassword_jobSeekerUser_succeeds() {
        User user = buildUser(UserRole.JOB_SEEKER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void changePassword_employerUser_succeeds() {
        User user = buildUser(UserRole.EMPLOYER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        verify(userRepository).save(user);
        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
    }

    @Test
    void firstLoginPasswordChangeSupportsOrganizationIndependentWaypointAdmin() {
        User user = buildUser(UserRole.WAYPOINT_ADMIN);
        user.setLoginId("WPADMIN");
        user.setOrganizationId(null);
        user.setMustChangePassword(true);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.changeFirstLoginPassword(
                USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        assertThat(user.getMustChangePassword()).isFalse();
        verify(auditService, never()).record(any());
    }

    @Test
    void changePassword_incorrectCurrentPassword_throwsBusinessException() {
        User user = buildUser(UserRole.JOB_SEEKER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPassword1", OLD_HASH)).thenReturn(false);

        assertThatThrownBy(() ->
                authService.changePassword(USER_ID, buildRequest("WrongPassword1", NEW_PASSWORD, NEW_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Current password is incorrect");

        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_newAndConfirmMismatch_throwsBusinessException() {
        assertThatThrownBy(() ->
                authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, "Different1")))
                .isInstanceOf(BusinessException.class)
                .hasMessage("New password and confirm password do not match");

        verify(userRepository, never()).findById(any());
        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_newSameAsCurrent_throwsBusinessException() {
        User user = buildUser(UserRole.JOB_SEEKER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);

        assertThatThrownBy(() ->
                authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, OLD_PASSWORD, OLD_PASSWORD)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("New password must be different from the current password");

        verify(userRepository, never()).save(any());
    }

    @Test
    void changePassword_userNotFound_throwsResourceNotFoundException() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found");
    }

    @Test
    void changePassword_storesPasswordEncoded_notPlaintext() {
        User user = buildUser(UserRole.JOB_SEEKER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("encoded-new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        assertThat(user.getPasswordHash()).isNotEqualTo(NEW_PASSWORD);
        assertThat(user.getPasswordHash()).isEqualTo("encoded-new-hash");
    }

    @Test
    void changePassword_looksUpOnlyAuthenticatedUserId() {
        User user = buildUser(UserRole.JOB_SEEKER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        verify(userRepository, times(1)).findById(USER_ID);
        verify(userRepository, never()).findByEmail(any());
        verify(jobSeekerProfileRepository, never()).findById(any());
        verify(employerProfileRepository, never()).findById(any());
    }

    @Test
    void changePassword_oldPasswordNoLongerAuthenticates_newPasswordDoes() {
        User user = buildUser(UserRole.JOB_SEEKER);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, OLD_HASH)).thenReturn(true);
        when(passwordEncoder.matches(NEW_PASSWORD, OLD_HASH)).thenReturn(false);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn("new-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        authService.changePassword(USER_ID, buildRequest(OLD_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

        when(userRepository.findByEmail(user.getEmail())).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(OLD_PASSWORD, "new-hash")).thenReturn(false);
        when(passwordEncoder.matches(NEW_PASSWORD, "new-hash")).thenReturn(true);
        when(jwtService.generateToken(any(), any(), any())).thenReturn("token");

        LoginRequest oldLogin = new LoginRequest();
        oldLogin.setEmail(user.getEmail());
        oldLogin.setPassword(OLD_PASSWORD);

        assertThatThrownBy(() -> authService.login(oldLogin)).isInstanceOf(BusinessException.class);

        LoginRequest newLogin = new LoginRequest();
        newLogin.setEmail(user.getEmail());
        newLogin.setPassword(NEW_PASSWORD);

        assertThat(authService.login(newLogin).getAccessToken()).isEqualTo("token");
    }

    // ---- registerEmployer ----

    @Test
    void registerEmployerThrowsWhenPasswordsDoNotMatch() {
        EmployerRegistrationRequest request = employerRequest();
        request.setConfirmPassword("different");

        assertThatThrownBy(() -> authService.registerEmployer(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Password and confirm password do not match");

        verify(userRepository, never()).existsByEmail(anyString());
        verify(userRepository, never()).save(any());
    }

    @Test
    void registerEmployerThrowsWhenEmailAlreadyRegistered() {
        EmployerRegistrationRequest request = employerRequest();

        when(userRepository.existsByEmail("hiring@waypoint.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.registerEmployer(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email already registered");

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerEmployerNormalizesEmailAndSavesUserAndProfile() {
        EmployerRegistrationRequest request = employerRequest();

        User savedUser = user("user-1", "hiring@waypoint.com", UserRole.EMPLOYER, false, AccountStatus.ACTIVE);
        EmployerProfile savedProfile = EmployerProfile.builder()
                .id("employer-1")
                .userId("user-1")
                .companyName("Waypoint Technologies")
                .companyAddress("Austin, Texas")
                .phoneNumber("+15551234567")
                .companySize(CompanySize.SMALL)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(userRepository.existsByEmail("hiring@waypoint.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(employerProfileRepository.save(any(EmployerProfile.class))).thenReturn(savedProfile);

        EmployerRegistrationResponse response = authService.registerEmployer(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User capturedUser = userCaptor.getValue();
        assertThat(capturedUser.getEmail()).isEqualTo("hiring@waypoint.com");
        assertThat(capturedUser.getPasswordHash()).isEqualTo("hashed-password");
        assertThat(capturedUser.getRole()).isEqualTo(UserRole.EMPLOYER);
        assertThat(capturedUser.getIsEmailVerified()).isFalse();
        assertThat(capturedUser.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);

        ArgumentCaptor<EmployerProfile> profileCaptor = ArgumentCaptor.forClass(EmployerProfile.class);
        verify(employerProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(profileCaptor.getValue().getCompanyName()).isEqualTo("Waypoint Technologies");

        verify(otpService).generateAndStoreOtp("hiring@waypoint.com", OtpPurpose.EMAIL_VERIFICATION);

        assertThat(response.getUserId()).isEqualTo("user-1");
        assertThat(response.getEmployerProfileId()).isEqualTo("employer-1");
        assertThat(response.getEmail()).isEqualTo("hiring@waypoint.com");
        assertThat(response.getCompanyName()).isEqualTo("Waypoint Technologies");
        assertThat(response.getCompanySize()).isEqualTo(CompanySize.SMALL);
        assertThat(response.getIsEmailVerified()).isFalse();
        assertThat(response.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void registerEmployerRollsBackUserWhenProfileSaveFails() {
        EmployerRegistrationRequest request = employerRequest();
        User savedUser = user("user-1", "hiring@waypoint.com", UserRole.EMPLOYER, false, AccountStatus.ACTIVE);

        when(userRepository.existsByEmail("hiring@waypoint.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(employerProfileRepository.save(any(EmployerProfile.class)))
                .thenThrow(new RuntimeException("mongo write failed"));

        assertThatThrownBy(() -> authService.registerEmployer(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo write failed");

        verify(userRepository).deleteById("user-1");
        verify(otpService, never()).generateAndStoreOtp(anyString(), any());
    }

    @Test
    void registerEmployerRollsBackUserAndProfileWhenOtpGenerationFails() {
        EmployerRegistrationRequest request = employerRequest();
        User savedUser = user("user-1", "hiring@waypoint.com", UserRole.EMPLOYER, false, AccountStatus.ACTIVE);
        EmployerProfile savedProfile = EmployerProfile.builder()
                .id("employer-1")
                .userId("user-1")
                .companyName("Waypoint Technologies")
                .build();

        when(userRepository.existsByEmail("hiring@waypoint.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(employerProfileRepository.save(any(EmployerProfile.class))).thenReturn(savedProfile);
        doThrowOnGenerateOtp();

        assertThatThrownBy(() -> authService.registerEmployer(request))
                .isInstanceOf(RuntimeException.class);

        verify(employerProfileRepository).deleteById("employer-1");
        verify(userRepository).deleteById("user-1");
    }

    // ---- registerJobSeeker ----

    @Test
    void registerJobSeekerThrowsWhenPasswordsDoNotMatch() {
        JobSeekerRegistrationRequest request = jobSeekerRequest();
        request.setConfirmPassword("different");

        assertThatThrownBy(() -> authService.registerJobSeeker(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Password and confirm password do not match");

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerJobSeekerThrowsWhenEmailAlreadyRegistered() {
        JobSeekerRegistrationRequest request = jobSeekerRequest();

        when(userRepository.existsByEmail("jordan@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.registerJobSeeker(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Email already registered");

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerJobSeekerNormalizesEmailAndSavesUserAndProfile() {
        JobSeekerRegistrationRequest request = jobSeekerRequest();

        User savedUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, false, AccountStatus.ACTIVE);
        JobSeekerProfile savedProfile = JobSeekerProfile.builder()
                .id("jobSeeker-1")
                .userId("user-1")
                .firstName("Jordan")
                .lastName("Rivera")
                .build();

        when(userRepository.existsByEmail("jordan@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jobSeekerProfileRepository.save(any(JobSeekerProfile.class))).thenReturn(savedProfile);

        JobSeekerRegistrationResponse response = authService.registerJobSeeker(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("jordan@example.com");
        assertThat(userCaptor.getValue().getRole()).isEqualTo(UserRole.JOB_SEEKER);

        ArgumentCaptor<JobSeekerProfile> profileCaptor = ArgumentCaptor.forClass(JobSeekerProfile.class);
        verify(jobSeekerProfileRepository).save(profileCaptor.capture());
        assertThat(profileCaptor.getValue().getUserId()).isEqualTo("user-1");
        assertThat(profileCaptor.getValue().getFirstName()).isEqualTo("Jordan");
        assertThat(profileCaptor.getValue().getSkills()).containsExactly("Java", "Spring", "MongoDB");

        verify(otpService).generateAndStoreOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION);

        assertThat(response.getUserId()).isEqualTo("user-1");
        assertThat(response.getJobSeekerProfileId()).isEqualTo("jobSeeker-1");
        assertThat(response.getFirstName()).isEqualTo("Jordan");
        assertThat(response.getLastName()).isEqualTo("Rivera");
    }

    @Test
    void registerJobSeekerRollsBackUserWhenProfileSaveFails() {
        JobSeekerRegistrationRequest request = jobSeekerRequest();
        User savedUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, false, AccountStatus.ACTIVE);

        when(userRepository.existsByEmail("jordan@example.com")).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hashed-password");
        when(userRepository.save(any(User.class))).thenReturn(savedUser);
        when(jobSeekerProfileRepository.save(any(JobSeekerProfile.class)))
                .thenThrow(new RuntimeException("mongo write failed"));

        assertThatThrownBy(() -> authService.registerJobSeeker(request))
                .isInstanceOf(RuntimeException.class);

        verify(userRepository).deleteById("user-1");
        verify(otpService, never()).generateAndStoreOtp(anyString(), any());
    }

    // ---- verifyOtp ----

    @Test
    void verifyOtpThrowsWhenUserNotFound() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("missing@example.com");
        request.setOtp("123456");

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.verifyOtp(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("User not found");

        verify(otpService, never()).verifyOtp(anyString(), any(), anyString());
    }

    @Test
    void verifyOtpMarksUserVerifiedOnSuccess() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("jordan@example.com");
        request.setOtp("123456");

        User existingUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, false, AccountStatus.ACTIVE);
        OtpVerification otpVerification = OtpVerification.builder()
                .id("otp-1")
                .email("jordan@example.com")
                .purpose(OtpPurpose.EMAIL_VERIFICATION)
                .isVerified(true)
                .verifiedAt(LocalDateTime.now())
                .build();

        when(userRepository.findByEmail("jordan@example.com")).thenReturn(Optional.of(existingUser));
        when(otpService.verifyOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .thenReturn(otpVerification);
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        VerifyOtpResponse response = authService.verifyOtp(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        assertThat(captor.getValue().getIsEmailVerified()).isTrue();

        assertThat(response.getUserId()).isEqualTo("user-1");
        assertThat(response.getIsEmailVerified()).isTrue();
        assertThat(response.getVerifiedAt()).isEqualTo(otpVerification.getVerifiedAt());
    }

    @Test
    void verifyOtpPropagatesBusinessExceptionFromOtpService() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("jordan@example.com");
        request.setOtp("000000");

        User existingUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, false, AccountStatus.ACTIVE);

        when(userRepository.findByEmail("jordan@example.com")).thenReturn(Optional.of(existingUser));
        when(otpService.verifyOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION, "000000"))
                .thenThrow(new BusinessException("Invalid OTP"));

        assertThatThrownBy(() -> authService.verifyOtp(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid OTP");

        verify(userRepository, never()).save(any());
    }

    @Test
    void verifyOtpRevertsVerificationWhenUserSaveFails() {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("jordan@example.com");
        request.setOtp("123456");

        User existingUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, false, AccountStatus.ACTIVE);
        OtpVerification otpVerification = OtpVerification.builder()
                .id("otp-1")
                .email("jordan@example.com")
                .purpose(OtpPurpose.EMAIL_VERIFICATION)
                .isVerified(true)
                .build();

        when(userRepository.findByEmail("jordan@example.com")).thenReturn(Optional.of(existingUser));
        when(otpService.verifyOtp("jordan@example.com", OtpPurpose.EMAIL_VERIFICATION, "123456"))
                .thenReturn(otpVerification);
        when(userRepository.save(any(User.class))).thenThrow(new RuntimeException("mongo write failed"));

        assertThatThrownBy(() -> authService.verifyOtp(request))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("mongo write failed");

        verify(otpService).revertVerification(otpVerification);
    }

    // ---- login ----

    @Test
    void loginThrowsGenericMessageWhenUserNotFound() {
        LoginRequest request = new LoginRequest();
        request.setEmail("missing@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmail("missing@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid email or password");

        verify(jwtService, never()).generateToken(anyString(), anyString(), anyString());
    }

    @Test
    void loginThrowsGenericMessageWhenPasswordDoesNotMatch() {
        LoginRequest request = new LoginRequest();
        request.setEmail("jordan@example.com");
        request.setPassword("wrong-password");

        User existingUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, true, AccountStatus.ACTIVE);

        when(userRepository.findByEmail("jordan@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("wrong-password", existingUser.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid email or password");
    }

    @Test
    void loginThrowsWhenEmailNotVerified() {
        LoginRequest request = new LoginRequest();
        request.setEmail("jordan@example.com");
        request.setPassword("password123");

        User existingUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, false, AccountStatus.ACTIVE);

        when(userRepository.findByEmail("jordan@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password123", existingUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Please verify your email before logging in.");

        verify(jwtService, never()).generateToken(anyString(), anyString(), anyString());
    }

    @Test
    void loginThrowsWhenAccountNotActive() {
        LoginRequest request = new LoginRequest();
        request.setEmail("jordan@example.com");
        request.setPassword("password123");

        User existingUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, true, AccountStatus.BLOCKED);

        when(userRepository.findByEmail("jordan@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password123", existingUser.getPasswordHash())).thenReturn(true);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Your account is not active. Please contact support.");

        verify(jwtService, never()).generateToken(anyString(), anyString(), anyString());
    }

    @Test
    void loginSucceedsAndReturnsAccessToken() {
        LoginRequest request = new LoginRequest();
        request.setEmail("Jordan@Example.com");
        request.setPassword("password123");

        User existingUser = user("user-1", "jordan@example.com", UserRole.JOB_SEEKER, true, AccountStatus.ACTIVE);

        when(userRepository.findByEmail("jordan@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password123", existingUser.getPasswordHash())).thenReturn(true);
        when(jwtService.generateToken("user-1", "jordan@example.com", "JOB_SEEKER")).thenReturn("signed-jwt");

        LoginResponse response = authService.login(request);

        assertThat(response.getUserId()).isEqualTo("user-1");
        assertThat(response.getEmail()).isEqualTo("jordan@example.com");
        assertThat(response.getRole()).isEqualTo(UserRole.JOB_SEEKER);
        assertThat(response.getAccessToken()).isEqualTo("signed-jwt");
        assertThat(response.getTokenType()).isEqualTo("Bearer");

        verify(jwtService, times(1)).generateToken(eq("user-1"), eq("jordan@example.com"), eq("JOB_SEEKER"));
    }

    @Test
    void waypointAdminLoginUsesExistingLoginIdAndJwtFlowWithoutOrganization() {
        LoginRequest request = new LoginRequest();
        request.setLoginId(" wpadmin ");
        request.setPassword("Temporary9!");

        User platformAdmin = User.builder()
                .id("platform-1")
                .loginId("WPADMIN")
                .email("admin@waypoint.test")
                .passwordHash("bcrypt-hash")
                .role(UserRole.WAYPOINT_ADMIN)
                .organizationId(null)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .mustChangePassword(true)
                .build();
        when(userRepository.findByLoginId("WPADMIN")).thenReturn(Optional.of(platformAdmin));
        when(passwordEncoder.matches("Temporary9!", "bcrypt-hash")).thenReturn(true);
        when(jwtService.generateToken("platform-1", "admin@waypoint.test", "WAYPOINT_ADMIN"))
                .thenReturn("platform-jwt");

        LoginResponse response = authService.login(request);

        assertThat(response.getRole()).isEqualTo(UserRole.WAYPOINT_ADMIN);
        assertThat(response.getOrganizationId()).isNull();
        assertThat(response.getMustChangePassword()).isTrue();
        assertThat(response.getAccessToken()).isEqualTo("platform-jwt");
    }
}
