package com.waypoint.controller;

import com.waypoint.dto.auth.JobSeekerRegistrationRequest;
import com.waypoint.dto.auth.JobSeekerRegistrationResponse;
import com.waypoint.dto.auth.EmployerRegistrationRequest;
import com.waypoint.dto.auth.EmployerRegistrationResponse;
import com.waypoint.dto.auth.LoginRequest;
import com.waypoint.dto.auth.LoginResponse;
import com.waypoint.dto.auth.OrganizationRegistrationResponse;
import com.waypoint.dto.auth.OrganizationRegistrationStartResponse;
import com.waypoint.dto.auth.ForgotPasswordRequest;
import com.waypoint.dto.auth.OrganizationRegistrationRequest;
import com.waypoint.dto.auth.VerifyOtpRequest;
import com.waypoint.dto.auth.VerifyOtpResponse;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.AuthService;
import com.waypoint.service.OrganizationRegistrationService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private OrganizationRegistrationService organizationRegistrationService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void changePassword_missingAuthorizationHeader_returns401() throws Exception {
        when(currentUserResolver.resolveUserId(any()))
                .thenThrow(new UnauthorizedException("Missing or invalid Authorization header"));

        String body = objectMapper.writeValueAsString(new java.util.HashMap<>() {{
            put("currentPassword", "OldPassword1");
            put("newPassword", "NewPassword1");
            put("confirmPassword", "NewPassword1");
        }});

        mockMvc.perform(patch("/api/auth/change-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        verify(authService, never()).changePassword(any(), any());
    }

    private EmployerRegistrationRequest validEmployerRequest() {
        EmployerRegistrationRequest request = new EmployerRegistrationRequest();
        request.setCompanyName("Waypoint Technologies");
        request.setEmail("hiring@waypoint.com");
        request.setCompanyAddress("Austin, Texas");
        request.setPhoneNumber("+15551234567");
        request.setCompanySize(CompanySize.SMALL);
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        return request;
    }

    private JobSeekerRegistrationRequest validJobSeekerRequest() {
        JobSeekerRegistrationRequest request = new JobSeekerRegistrationRequest();
        request.setFirstName("Jordan");
        request.setLastName("Rivera");
        request.setEmail("jordan@example.com");
        request.setAddress("Austin, Texas");
        request.setPhoneNumber("+15551234567");
        request.setSkills(List.of("Java", "Spring", "MongoDB"));
        request.setPreferredJobRoles(List.of("Backend Developer"));
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        return request;
    }

    @Test
    void registerEmployerReturns201AndExpectedResponse() throws Exception {
        EmployerRegistrationResponse response = EmployerRegistrationResponse.builder()
                .userId("user-1")
                .employerProfileId("employer-1")
                .email("hiring@waypoint.com")
                .companyName("Waypoint Technologies")
                .companySize(CompanySize.SMALL)
                .isEmailVerified(false)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();

        when(authService.registerEmployer(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/register/employer")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validEmployerRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Employer registered successfully"))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.companyName").value("Waypoint Technologies"));
    }

    @Test
    void publicOrganizationRegistrationStartsWithoutAuthenticationAndNeverReturnsOtp() throws Exception {
        when(organizationRegistrationService.start(any())).thenReturn(
                new OrganizationRegistrationStartResponse(
                        "registration-1", "admin@acme.example", LocalDateTime.now().plusMinutes(30)));

        mockMvc.perform(post("/api/auth/organization-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationName":"Acme Health","organizationEmail":"contact@acme.example",
                                 "phoneNumber":"555-0100","state":"IL","city":"Chicago",
                                 "streetAddress":"100 Main St","zipCode":"60601","organizationSize":"MEDIUM",
                                 "industry":"Healthcare","einNumber":"12-3456789","eVerifyNumber":"EV-1001",
                                 "payrollPeriod":"MONTHLY","adminName":"Jane Admin",
                                 "adminEmail":"admin@acme.example","adminPhoneNumber":"555-0101"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.registrationId").value("registration-1"))
                .andExpect(jsonPath("$.data.adminEmail").value("admin@acme.example"))
                .andExpect(jsonPath("$.data.otp").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist());
    }

    @Test
    void organizationRegistrationBindsTheFullProfileIncludingEVerifyNumber() throws Exception {
        when(organizationRegistrationService.start(any())).thenReturn(
                new OrganizationRegistrationStartResponse(
                        "registration-1", "admin@acme.example", LocalDateTime.now().plusMinutes(30)));

        mockMvc.perform(post("/api/auth/organization-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationName":"Acme Health","organizationEmail":"contact@acme.example",
                                 "phoneNumber":"555-0100","state":"IL","city":"Chicago",
                                 "streetAddress":"100 Main St","zipCode":"60601","organizationSize":"MEDIUM",
                                 "industry":"Healthcare","einNumber":"12-3456789","eVerifyNumber":"EV-1001",
                                 "payrollPeriod":"MONTHLY","adminName":"Jane Admin",
                                 "adminEmail":"admin@acme.example","adminPhoneNumber":"555-0101"}
                                """))
                .andExpect(status().isAccepted());

        org.mockito.ArgumentCaptor<OrganizationRegistrationRequest> request =
                org.mockito.ArgumentCaptor.forClass(OrganizationRegistrationRequest.class);
        verify(organizationRegistrationService).start(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().getEVerifyNumber()).isEqualTo("EV-1001");
        org.assertj.core.api.Assertions.assertThat(request.getValue().getPayrollPeriod())
                .isEqualTo(com.waypoint.enums.PayrollPeriod.MONTHLY);
    }

    @Test
    void organizationRegistrationAcceptsMultipartWithAnOptionalLogo() throws Exception {
        when(organizationRegistrationService.start(any(), any())).thenReturn(
                new OrganizationRegistrationStartResponse(
                        "registration-1", "admin@acme.example", LocalDateTime.now().plusMinutes(30)));
        var request = new org.springframework.mock.web.MockMultipartFile("request", "", MediaType.APPLICATION_JSON_VALUE, """
                {"organizationName":"Acme Health","organizationEmail":"contact@acme.example",
                 "phoneNumber":"555-0100","state":"IL","city":"Chicago",
                 "streetAddress":"100 Main St","zipCode":"60601","organizationSize":"MEDIUM",
                 "industry":"Healthcare","einNumber":"12-3456789","eVerifyNumber":"EV-1001","payrollPeriod":"MONTHLY",
                 "adminName":"Jane Admin","adminEmail":"admin@acme.example","adminPhoneNumber":"555-0101"}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var logo = new org.springframework.mock.web.MockMultipartFile(
                "logo", "logo.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N', 'G'});

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .multipart("/api/auth/organization-registration").file(request).file(logo))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.registrationId").value("registration-1"));

        org.mockito.ArgumentCaptor<org.springframework.web.multipart.MultipartFile> file =
                org.mockito.ArgumentCaptor.forClass(org.springframework.web.multipart.MultipartFile.class);
        verify(organizationRegistrationService).start(any(), file.capture());
        org.assertj.core.api.Assertions.assertThat(file.getValue().getOriginalFilename()).isEqualTo("logo.png");
    }

    @Test
    void organizationRegistrationRequiresTheSettingsProfileFields() throws Exception {
        mockMvc.perform(post("/api/auth/organization-registration")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"organizationName":"Acme Health","organizationEmail":"contact@acme.example",
                                 "phoneNumber":"555-0100","state":"IL","city":"Chicago",
                                 "streetAddress":"100 Main St","zipCode":"60601","organizationSize":"MEDIUM",
                                 "einNumber":"12-34","adminEmail":"admin@acme.example"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.industry").exists())
                .andExpect(jsonPath("$.data.einNumber").value("EIN must be 9 digits, e.g. 12-3456789"))
                .andExpect(jsonPath("$.data.eVerifyNumber").value("E-Verify number is required"))
                .andExpect(jsonPath("$.data.payrollPeriod").exists())
                .andExpect(jsonPath("$.data.adminName").exists())
                .andExpect(jsonPath("$.data.adminPhoneNumber").exists());

        verify(organizationRegistrationService, never()).start(any());
    }

    @Test
    void verifiedOrganizationRegistrationReturnsCreatedWithoutAuthenticationToken() throws Exception {
        when(organizationRegistrationService.complete(any())).thenReturn(
                new OrganizationRegistrationResponse("org-1", "ACME-123456", "ACMEADMIN"));

        mockMvc.perform(post("/api/auth/organization-registration/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"registrationId\":\"registration-1\",\"otp\":\"123456\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.organizationCode").value("ACME-123456"))
                .andExpect(jsonPath("$.data.adminLoginId").value("ACMEADMIN"))
                .andExpect(jsonPath("$.data.temporaryPassword").doesNotExist())
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.accessToken").doesNotExist());
    }

    @Test
    void registerEmployerReturns400WhenRequiredFieldsMissing() throws Exception {
        EmployerRegistrationRequest request = new EmployerRegistrationRequest();

        mockMvc.perform(post("/api/auth/register/employer")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.companyName").value("Company name is required"))
                .andExpect(jsonPath("$.data.email").value("Email is required"));
    }

    @Test
    void registerEmployerReturns400WhenServiceThrowsBusinessException() throws Exception {
        when(authService.registerEmployer(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException("Email already registered"));

        mockMvc.perform(post("/api/auth/register/employer")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validEmployerRequest())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Email already registered"));
    }

    @Test
    void registerJobSeekerReturns201AndExpectedResponse() throws Exception {
        JobSeekerRegistrationResponse response = JobSeekerRegistrationResponse.builder()
                .userId("user-1")
                .jobSeekerProfileId("jobSeeker-1")
                .email("jordan@example.com")
                .firstName("Jordan")
                .lastName("Rivera")
                .isEmailVerified(false)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();

        when(authService.registerJobSeeker(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/register/job-seeker")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validJobSeekerRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job Seeker registered successfully"))
                .andExpect(jsonPath("$.data.firstName").value("Jordan"));
    }

    @Test
    void registerJobSeekerReturns400WhenPasswordTooShort() throws Exception {
        JobSeekerRegistrationRequest request = validJobSeekerRequest();
        request.setPassword("short");
        request.setConfirmPassword("short");

        mockMvc.perform(post("/api/auth/register/job-seeker")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.password").value("Password must be at least 8 characters"));
    }

    @Test
    void verifyOtpReturns200AndExpectedResponse() throws Exception {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("jordan@example.com");
        request.setOtp("123456");

        VerifyOtpResponse response = VerifyOtpResponse.builder()
                .userId("user-1")
                .email("jordan@example.com")
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .verifiedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();

        when(authService.verifyOtp(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/verify-otp")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Email verified successfully"))
                .andExpect(jsonPath("$.data.isEmailVerified").value(true));
    }

    @Test
    void verifyOtpReturns400ForMalformedOtp() throws Exception {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("jordan@example.com");
        request.setOtp("abc");

        mockMvc.perform(post("/api/auth/verify-otp")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.otp").value("OTP must be 6 digits"));
    }

    @Test
    void verifyOtpReturns400WhenServiceThrowsBusinessException() throws Exception {
        VerifyOtpRequest request = new VerifyOtpRequest();
        request.setEmail("jordan@example.com");
        request.setOtp("123456");

        when(authService.verifyOtp(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException("Invalid OTP"));

        mockMvc.perform(post("/api/auth/verify-otp")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid OTP"));
    }

    @Test
    void loginReturns200AndExpectedResponse() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("jordan@example.com");
        request.setPassword("password123");

        LoginResponse response = LoginResponse.builder()
                .userId("user-1")
                .email("jordan@example.com")
                .role(UserRole.JOB_SEEKER)
                .accountStatus(AccountStatus.ACTIVE)
                .isEmailVerified(true)
                .accessToken("signed-jwt")
                .tokenType("Bearer")
                .build();

        when(authService.login(org.mockito.ArgumentMatchers.any())).thenReturn(response);

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Login successful"))
                .andExpect(jsonPath("$.data.accessToken").value("signed-jwt"))
                .andExpect(jsonPath("$.data.role").value("JOB_SEEKER"));
    }

    @Test
    void loginReturns400ForInvalidEmailFormat() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("not-an-email");
        request.setPassword("password123");

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.email").value("Email must be valid"));
    }

    @Test
    void loginReturns400WhenServiceThrowsBusinessException() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setEmail("jordan@example.com");
        request.setPassword("wrong-password");

        when(authService.login(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BusinessException("Invalid email or password"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid email or password"));
    }

    @Test
    void forgotPasswordAlwaysReturnsGenericResponseWithoutAccountDetails() throws Exception {
        ForgotPasswordRequest request = new ForgotPasswordRequest();
        request.setEmail("unknown@example.com");

        mockMvc.perform(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value(
                        "If an account exists for that email, a reset code has been sent."))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(authService).forgotPassword(any(ForgotPasswordRequest.class));
    }
}
