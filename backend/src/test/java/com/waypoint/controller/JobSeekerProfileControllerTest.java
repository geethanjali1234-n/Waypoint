package com.waypoint.controller;

import tools.jackson.databind.ObjectMapper;
import com.waypoint.dto.profile.JobSeekerProfileResponse;
import com.waypoint.dto.profile.JobSeekerProfileUpdateRequest;
import com.waypoint.enums.EmploymentType;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.JobSeekerProfileService;
import com.waypoint.service.JobSeekerResumeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JobSeekerProfileController.class)
class JobSeekerProfileControllerTest {

    private static final String VALID_TOKEN = "valid-token";
    private static final String JOB_SEEKER_USER_ID = "jobSeeker-user-1";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private JobSeekerProfileService jobSeekerProfileService;

    @MockitoBean
    private JobSeekerResumeService jobSeekerResumeService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void uploadResumeUsesSharedResumeServiceAndReturnsUpdatedProfile() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn(JOB_SEEKER_USER_ID);
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("JOB_SEEKER");
        when(currentUserResolver.resolveUserId(any())).thenReturn(JOB_SEEKER_USER_ID);
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");
        when(jobSeekerProfileService.getProfile(JOB_SEEKER_USER_ID))
                .thenReturn(JobSeekerProfileResponse.builder()
                        .resumeFileName("replacement-resume.pdf")
                        .build());

        MockMultipartFile file = new MockMultipartFile(
                "file", "replacement-resume.pdf", "application/pdf", "resume".getBytes());

        mockMvc.perform(multipart("/api/job-seeker/profile/resume")
                        .file(file)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.resumeFileName").value("replacement-resume.pdf"));

        verify(jobSeekerResumeService).uploadResume(eq(JOB_SEEKER_USER_ID), any(MultipartFile.class));
        verify(jobSeekerProfileService).getProfile(JOB_SEEKER_USER_ID);
    }

    private JobSeekerProfileResponse sampleProfileResponse() {
        return JobSeekerProfileResponse.builder()
                .userId("user-1")
                .jobSeekerProfileId("profile-1")
                .firstName("Jordan")
                .lastName("Rivera")
                .email("jordan@example.com")
                .phoneNumber("+15551234567")
                .skills(List.of("Java"))
                .preferredJobRoles(List.of("Backend Developer"))
                .completionPercentage(80)
                .build();
    }

    private JobSeekerProfileUpdateRequest validUpdateRequest() {
        JobSeekerProfileUpdateRequest request = new JobSeekerProfileUpdateRequest();
        request.setFirstName("Jordan");
        request.setLastName("Rivera");
        request.setPhoneNumber("+15551234567");
        request.setSkills(List.of("Java"));
        request.setPreferredJobRoles(List.of("Backend Developer"));
        request.setPreferredEmploymentType(List.of(EmploymentType.FULL_TIME));
        request.setExpectedSalaryMin(new BigDecimal("70000"));
        request.setExpectedSalaryMax(new BigDecimal("90000"));
        return request;
    }

    private void mockValidJobSeeker() {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("JOB_SEEKER");
        when(currentUserResolver.resolveUserId(org.mockito.ArgumentMatchers.any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(org.mockito.ArgumentMatchers.any())).thenReturn("JOB_SEEKER");
    }

    @Test
    void getProfileReturns200AndExpectedResponse() throws Exception {
        mockValidJobSeeker();
        when(jobSeekerProfileService.getProfile("user-1")).thenReturn(sampleProfileResponse());

        mockMvc.perform(get("/api/job-seeker/profile").header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Profile fetched successfully"))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.firstName").value("Jordan"));
    }

    @Test
    void getProfileReturns403WhenRoleIsNotJobSeeker() throws Exception {
        // JwtAuthInterceptor enforces the JOB_SEEKER-only role gate for /api/job-seeker/**
        // ahead of the controller, so a non-jobSeeker token never reaches the
        // controller's own currentUserResolver-based check below.
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");

        mockMvc.perform(get("/api/job-seeker/profile").header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("You do not have access to this resource"));

        verifyNoInteractions(jobSeekerProfileService, currentUserResolver);
    }

    @Test
    void getProfileReturns404WhenProfileDoesNotExist() throws Exception {
        mockValidJobSeeker();
        when(jobSeekerProfileService.getProfile("user-1"))
                .thenThrow(new ResourceNotFoundException("JobSeeker profile not found"));

        mockMvc.perform(get("/api/job-seeker/profile").header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("JobSeeker profile not found"));
    }

    @Test
    void updateProfileReturns200AndExpectedResponse() throws Exception {
        mockValidJobSeeker();
        when(jobSeekerProfileService.updateProfile(org.mockito.ArgumentMatchers.eq("user-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(sampleProfileResponse());

        mockMvc.perform(put("/api/job-seeker/profile")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Profile updated successfully"));
    }

    @Test
    void updateProfileReturns400WhenValidationFails() throws Exception {
        mockValidJobSeeker();
        JobSeekerProfileUpdateRequest invalid = new JobSeekerProfileUpdateRequest();

        mockMvc.perform(put("/api/job-seeker/profile")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.data.firstName").value("First name is required"));

        verifyNoInteractions(jobSeekerProfileService);
    }

    @Test
    void updatePhotoReturns200AndExpectedResponse() throws Exception {
        mockValidJobSeeker();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "content".getBytes());
        when(jobSeekerProfileService.updateProfilePhoto(org.mockito.ArgumentMatchers.eq("user-1"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(sampleProfileResponse());

        mockMvc.perform(multipart("/api/job-seeker/profile/photo")
                        .file(file)
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Profile photo updated successfully"));
    }

    @Test
    void getProfileReturns401WhenAuthorizationHeaderMissing() throws Exception {
        // JwtAuthInterceptor rejects the missing header before the request
        // ever reaches the controller's own currentUserResolver check.
        mockMvc.perform(get("/api/job-seeker/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Missing or invalid authentication token"));

        verifyNoInteractions(jobSeekerProfileService, currentUserResolver);
    }
}
