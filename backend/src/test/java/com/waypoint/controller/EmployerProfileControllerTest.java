package com.waypoint.controller;

import com.waypoint.dto.profile.EmployerProfileResponse;
import com.waypoint.enums.CompanySize;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.EmployerProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmployerProfileController.class)
class EmployerProfileControllerTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String USER_ID = "employer-user-1";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployerProfileService employerProfileService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private JwtService jwtService;

    @BeforeEach
    void authenticateEmployer() {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn(USER_ID);
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");
        when(currentUserResolver.resolveUserId(any())).thenReturn(USER_ID);
        when(currentUserResolver.resolveRole(any())).thenReturn("EMPLOYER");
    }

    @Test
    void getProfileReturnsEmployerProfile() throws Exception {
        when(employerProfileService.getProfile(USER_ID)).thenReturn(profileResponse());

        mockMvc.perform(get("/api/employer/profile")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Profile fetched successfully"))
                .andExpect(jsonPath("$.data.companyName").value("Waypoint Labs"))
                .andExpect(jsonPath("$.data.completionPercentage").value(82));

        verify(employerProfileService).getProfile(USER_ID);
    }

    @Test
    void updateProfileAcceptsValidRequest() throws Exception {
        when(employerProfileService.updateProfile(eq(USER_ID), any())).thenReturn(profileResponse());

        mockMvc.perform(put("/api/employer/profile")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyName": "Waypoint Labs",
                                  "companyDescription": "Career technology",
                                  "companySize": "MEDIUM",
                                  "foundedYear": 2020,
                                  "careersContactEmail": "careers@waypoint.test",
                                  "website": "https://waypoint.test",
                                  "phoneNumber": "+13125550123"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Profile updated successfully"));

        verify(employerProfileService).updateProfile(eq(USER_ID), any());
    }

    @Test
    void updateProfileRejectsInvalidInputBeforeCallingService() throws Exception {
        mockMvc.perform(put("/api/employer/profile")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "companyName": " ",
                                  "careersContactEmail": "not-an-email",
                                  "website": "waypoint.test",
                                  "phoneNumber": "123"
                                }
                                """))
                .andExpect(status().isBadRequest());

        verify(employerProfileService, never()).updateProfile(any(), any());
    }

    @Test
    void updateLogoPassesMultipartFileToService() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", "image-data".getBytes());
        when(employerProfileService.updateLogo(eq(USER_ID), any(MultipartFile.class)))
                .thenReturn(profileResponse());

        mockMvc.perform(multipart("/api/employer/profile/logo")
                        .file(file)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Company logo updated successfully"));

        verify(employerProfileService).updateLogo(eq(USER_ID), any(MultipartFile.class));
    }

    @Test
    void getProfileRejectsJobSeekerRole() throws Exception {
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("JOB_SEEKER");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");

        mockMvc.perform(get("/api/employer/profile")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isForbidden());

        verify(employerProfileService, never()).getProfile(any());
    }

    @Test
    void getProfileRejectsMissingAuthorizationHeader() throws Exception {
        mockMvc.perform(get("/api/employer/profile"))
                .andExpect(status().isUnauthorized());

        verify(employerProfileService, never()).getProfile(any());
    }

    private EmployerProfileResponse profileResponse() {
        return EmployerProfileResponse.builder()
                .employerProfileId("employer-profile-1")
                .userId(USER_ID)
                .companyName("Waypoint Labs")
                .email("employer@waypoint.test")
                .companySize(CompanySize.MEDIUM)
                .companyLogoUrl("/uploads/company-logos/logo.png")
                .completionPercentage(82)
                .build();
    }
}
