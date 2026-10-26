package com.waypoint.controller;

import com.waypoint.dto.job.JobApplicationResponse;
import com.waypoint.dto.job.JobDetailResponse;
import com.waypoint.dto.job.JobListResponse;
import com.waypoint.dto.job.JobSummaryResponse;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.security.JwtService;
import com.waypoint.service.JobPortalService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(JobPortalController.class)
class JobPortalControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JobPortalService jobService;

    @MockitoBean
    private JwtService jwtService;

    private static final String VALID_TOKEN = "valid-token";

    private JobSummaryResponse sampleJobSummary() {
        return JobSummaryResponse.builder()
                .id("job-1")
                .jobCode("JOB-job-1")
                .title("Backend Engineer")
                .location("Austin, Texas")
                .workMode(WorkMode.REMOTE)
                .employmentType(EmploymentType.FULL_TIME)
                .salaryMin(new BigDecimal("70000"))
                .salaryMax(new BigDecimal("90000"))
                .applicationDeadline(LocalDateTime.of(2026, 12, 31, 23, 59))
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .requiredSkills(List.of("Java"))
                .build();
    }

    private JobListResponse sampleJobListResponse() {
        return JobListResponse.builder()
                .content(List.of(sampleJobSummary()))
                .page(0)
                .size(10)
                .totalElements(1)
                .totalPages(1)
                .build();
    }

    private JobDetailResponse sampleJobDetailResponse() {
        return JobDetailResponse.builder()
                .id("job-1")
                .jobCode("JOB-job-1")
                .title("Backend Engineer")
                .description("Description for Backend Engineer")
                .location("Austin, Texas")
                .workMode(WorkMode.REMOTE)
                .employmentType(EmploymentType.FULL_TIME)
                .requiredSkills(List.of("Java"))
                .salaryMin(new BigDecimal("70000"))
                .salaryMax(new BigDecimal("90000"))
                .applicationDeadline(LocalDateTime.of(2026, 12, 31, 23, 59))
                .status(JobStatus.OPEN)
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .companyName("Waypoint Technologies")
                .build();
    }

    private JobApplicationResponse sampleJobApplicationResponse() {
        return JobApplicationResponse.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-1")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
    }

    @Test
    void getOpenJobsReturns200AndExpectedResponse() throws Exception {
        when(jobService.getOpenJobs(null, null, null, null, null, null, null, null, null, null, 0, 10, null))
                .thenReturn(sampleJobListResponse());

        mockMvc.perform(get("/api/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Jobs retrieved successfully"))
                .andExpect(jsonPath("$.data.content[0].id").value("job-1"))
                .andExpect(jsonPath("$.data.content[0].title").value("Backend Engineer"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));
    }

    @Test
    void getOpenJobsReturns400ForInvalidEmploymentType() throws Exception {
        mockMvc.perform(get("/api/jobs").param("employmentType", "NOT_A_REAL_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'employmentType'"));
    }

    @Test
    void getJobByIdReturns200AndExpectedResponse() throws Exception {
        when(jobService.getJobById("job-1", null)).thenReturn(sampleJobDetailResponse());

        mockMvc.perform(get("/api/jobs/job-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job retrieved successfully"))
                .andExpect(jsonPath("$.data.id").value("job-1"))
                .andExpect(jsonPath("$.data.title").value("Backend Engineer"))
                .andExpect(jsonPath("$.data.companyName").value("Waypoint Technologies"));
    }

    @Test
    void getJobByIdReturns404WhenJobDoesNotExist() throws Exception {
        when(jobService.getJobById("missing-job", null)).thenThrow(new ResourceNotFoundException("Job not found"));

        mockMvc.perform(get("/api/jobs/missing-job"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Job not found"));
    }

    @Test
    void getOpenJobsBindsAllSupportedQueryParameters() throws Exception {
        when(jobService.getOpenJobs(
                "Java", "Dallas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                new BigDecimal("80000"), new BigDecimal("120000"), LocalDate.of(2026, 1, 15),
                new BigDecimal("32.7767"), new BigDecimal("-96.7970"), 25, 2, 20, null))
                .thenReturn(sampleJobListResponse());

        mockMvc.perform(get("/api/jobs")
                        .param("title", "Java")
                        .param("location", "Dallas")
                        .param("employmentType", "FULL_TIME")
                        .param("workMode", "REMOTE")
                        .param("salaryMin", "80000")
                        .param("salaryMax", "120000")
                        .param("postedAfter", "2026-01-15")
                        .param("lat", "32.7767")
                        .param("lng", "-96.7970")
                        .param("maxDistanceMiles", "25")
                        .param("page", "2")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Jobs retrieved successfully"));

        verify(jobService).getOpenJobs(
                "Java", "Dallas", EmploymentType.FULL_TIME, WorkMode.REMOTE,
                new BigDecimal("80000"), new BigDecimal("120000"), LocalDate.of(2026, 1, 15),
                new BigDecimal("32.7767"), new BigDecimal("-96.7970"), 25, 2, 20, null);
    }

    @Test
    void getOpenJobsBindsSalaryRangeQueryParameter() throws Exception {
        when(jobService.getOpenJobs(
                null, null, null, null, null, null, SalaryRange.RANGE_50K_100K,
                null, 0, 10, null))
                .thenReturn(sampleJobListResponse());

        mockMvc.perform(get("/api/jobs").param("salaryRange", "RANGE_50K_100K"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").value("job-1"));

        verify(jobService).getOpenJobs(
                null, null, null, null, null, null, SalaryRange.RANGE_50K_100K,
                null, 0, 10, null);
    }

    @Test
    void getOpenJobsReturns400ForInvalidPostedAfter() throws Exception {
        mockMvc.perform(get("/api/jobs").param("postedAfter", "not-a-date"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'postedAfter'"));

        verifyNoInteractions(jobService);
    }

    @ParameterizedTest
    @CsvSource({
            "page, abc",
            "salaryMin, abc"
    })
    void getOpenJobsReturns400ForInvalidNumericParameter(String paramName, String paramValue) throws Exception {
        mockMvc.perform(get("/api/jobs").param(paramName, paramValue))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter '" + paramName + "'"));

        verifyNoInteractions(jobService);
    }

    @Test
    void getOpenJobsReturns400WhenServiceThrowsBusinessException() throws Exception {
        when(jobService.getOpenJobs(null, null, null, null, null, null, null, null, null, null, -1, 10, null))
                .thenThrow(new BusinessException("Page must be 0 or greater"));

        mockMvc.perform(get("/api/jobs").param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Page must be 0 or greater"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(jobService).getOpenJobs(null, null, null, null, null, null, null, null, null, null, -1, 10, null);
    }

    @Test
    void applyForJobReturns201Created() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("user-1");
        when(jobService.applyForJob("job-1", "user-1"))
                .thenReturn(sampleJobApplicationResponse());

        mockMvc.perform(post("/api/jobs/job-1/apply")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Job application submitted successfully"))
                .andExpect(jsonPath("$.data.id").value("application-1"))
                .andExpect(jsonPath("$.data.jobId").value("job-1"))
                .andExpect(jsonPath("$.data.jobSeekerProfileId").value("jobSeeker-1"))
                .andExpect(jsonPath("$.data.status").value("APPLIED"));

        verify(jobService).applyForJob("job-1", "user-1");
    }

    @Test
    void applyForJobReturns401WhenAuthorizationHeaderMissing() throws Exception {
        mockMvc.perform(post("/api/jobs/job-1/apply"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Missing or invalid Authorization header"));

        verifyNoInteractions(jobService);
    }

    @Test
    void applyForJobReturns400WhenBusinessExceptionOccurs() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("user-1");
        when(jobService.applyForJob("job-1", "user-1"))
                .thenThrow(new BusinessException("You have already applied to this job"));

        mockMvc.perform(post("/api/jobs/job-1/apply")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("You have already applied to this job"));

        verify(jobService).applyForJob("job-1", "user-1");
    }

    @Test
    void applyForJobReturns404WhenJobDoesNotExist() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("user-1");
        when(jobService.applyForJob("missing-job", "user-1"))
                .thenThrow(new ResourceNotFoundException("Job not found"));

        mockMvc.perform(post("/api/jobs/missing-job/apply")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Job not found"));

        verify(jobService).applyForJob("missing-job", "user-1");
    }
}
