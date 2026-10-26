package com.waypoint.controller;

import com.waypoint.dto.jobseeker.JobSeekerDashboardResponse;
import com.waypoint.security.JwtService;
import com.waypoint.service.JobSeekerDashboardService;
import io.jsonwebtoken.ExpiredJwtException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(JobSeekerDashboardController.class)
class JobSeekerDashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private JobSeekerDashboardService jobSeekerDashboardService;

    private static final String VALID_TOKEN = "valid-token";

    private JobSeekerDashboardResponse sampleDashboard() {
        return JobSeekerDashboardResponse.builder()
                .userId("user-1")
                .firstName("Jordan")
                .profilePicturePath("/uploads/profile-pictures/user-1.png")
                .hasProfile(true)
                .build();
    }

    @Test
    void getDashboardReturns200AndExpectedResponse() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("JOB_SEEKER");
        when(jobSeekerDashboardService.getDashboard("user-1")).thenReturn(sampleDashboard());

        mockMvc.perform(get("/api/job-seeker/dashboard")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("JobSeeker dashboard retrieved successfully"))
                .andExpect(jsonPath("$.data.userId").value("user-1"))
                .andExpect(jsonPath("$.data.firstName").value("Jordan"))
                .andExpect(jsonPath("$.data.hasProfile").value(true));
    }

    @Test
    void getDashboardReturns401WhenAuthorizationHeaderMissing() throws Exception {
        mockMvc.perform(get("/api/job-seeker/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Missing or invalid Authorization header"));

        verifyNoInteractions(jwtService, jobSeekerDashboardService);
    }

    @Test
    void getDashboardReturns401WhenAuthorizationHeaderDoesNotStartWithBearer() throws Exception {
        mockMvc.perform(get("/api/job-seeker/dashboard")
                        .header("Authorization", "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Missing or invalid Authorization header"));

        verifyNoInteractions(jwtService, jobSeekerDashboardService);
    }

    @Test
    void getDashboardReturns401WhenTokenIsInvalidOrExpired() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN))
                .thenThrow(new ExpiredJwtException(null, null, "token expired"));

        mockMvc.perform(get("/api/job-seeker/dashboard")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid or expired token"));

        verifyNoInteractions(jobSeekerDashboardService);
    }

    @Test
    void getDashboardReturns403WhenRoleIsNotJobSeeker() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");

        mockMvc.perform(get("/api/job-seeker/dashboard")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Access restricted to job seekers"));

        verifyNoInteractions(jobSeekerDashboardService);
    }
}
