package com.waypoint.controller;

import com.waypoint.service.EmployerApplicantService;
import com.waypoint.service.ResumeStorageService;
import com.waypoint.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmployerApplicantController.class)
class EmployerApplicantControllerTest {

    private static final String VALID_TOKEN = "valid-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EmployerApplicantService employerApplicantService;

    @MockitoBean
    private JwtService jwtService;

    @Test
    void employerCanDownloadResumeFromAnOwnedApplication() throws Exception {
        byte[] resumeBytes = "resume".getBytes();
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("employer-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");
        when(employerApplicantService.getApplicantResume(
                "employer-user-1", "job-1", "application-1"))
                .thenReturn(new ResumeStorageService.ResumeDocument(
                        resumeBytes, "alex.pdf", MediaType.APPLICATION_PDF, resumeBytes.length));

        mockMvc.perform(get("/api/employer/jobs/job-1/applications/application-1/resume")
                        .param("download", "true")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(content().bytes(resumeBytes))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
    }

    @Test
    void jobSeekerTokenCannotAccessEmployerResumeEndpoint() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("jobSeeker-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("JOB_SEEKER");

        mockMvc.perform(get("/api/employer/jobs/job-1/applications/application-1/resume")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isForbidden());

        verify(employerApplicantService, never()).getApplicantResume(any(), any(), any());
    }
}
