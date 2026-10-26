package com.waypoint.controller;

import com.waypoint.dto.job.JobSeekerApplicationResponse;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.JwtService;
import com.waypoint.service.JobPortalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(JobSeekerApplicationController.class)
class JobSeekerApplicationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobPortalService jobService;

    @MockitoBean
    private CurrentUserResolver currentUserResolver;

    @MockitoBean
    private JwtService jwtService;

    private JobSeekerApplicationResponse sampleApplication() {
        return JobSeekerApplicationResponse.builder()
                .id("application-1")
                .jobId("job-1")
                .jobTitle("Backend Engineer")
                .companyName("Waypoint Technologies")
                .location("Austin, Texas")
                .status(ApplicationStatus.UNDER_REVIEW)
                .appliedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
    }

    @Test
    void getMyApplicationsReturns200WithPopulatedList() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");
        when(jobService.getMyApplications("user-1")).thenReturn(List.of(sampleApplication()));

        mockMvc.perform(get("/api/job-seeker/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Applications retrieved successfully"))
                .andExpect(jsonPath("$.data[0].id").value("application-1"))
                .andExpect(jsonPath("$.data[0].jobTitle").value("Backend Engineer"))
                .andExpect(jsonPath("$.data[0].companyName").value("Waypoint Technologies"))
                .andExpect(jsonPath("$.data[0].status").value("UNDER_REVIEW"));
    }

    @Test
    void getMyApplicationsReturns200WithEmptyList() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("JOB_SEEKER");
        when(jobService.getMyApplications("user-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/job-seeker/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getMyApplicationsReturns403WhenRoleIsNotJobSeeker() throws Exception {
        when(currentUserResolver.resolveUserId(any())).thenReturn("user-1");
        when(currentUserResolver.resolveRole(any())).thenReturn("EMPLOYER");

        mockMvc.perform(get("/api/job-seeker/applications"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Access restricted to job seekers"));
    }

    @Test
    void getMyApplicationsPropagatesForbiddenExceptionFromResolver() throws Exception {
        when(currentUserResolver.resolveUserId(any()))
                .thenThrow(new ForbiddenException("Access restricted to job seekers"));

        mockMvc.perform(get("/api/job-seeker/applications"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Access restricted to job seekers"));
    }
}
