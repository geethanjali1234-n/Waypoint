package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.job.CreateJobRequest;
import com.waypoint.dto.job.EmployerJobResponse;
import com.waypoint.security.JwtAuthInterceptor;
import com.waypoint.service.JobService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/employer/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmployerJobResponse>>> getEmployerJobs(HttpServletRequest request) {
        List<EmployerJobResponse> jobs = jobService.getEmployerJobs(authenticatedUserId(request));
        return ResponseEntity.ok(new ApiResponse<>(true, "Employer jobs retrieved successfully", jobs));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<EmployerJobResponse>> getEmployerJob(
            HttpServletRequest request, @PathVariable String jobId) {

        EmployerJobResponse job = jobService.getEmployerJob(authenticatedUserId(request), jobId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Employer job retrieved successfully", job));
    }

    @GetMapping("/new-code")
    public ResponseEntity<ApiResponse<String>> getNewJobCode(HttpServletRequest request) {
        String jobCode = jobService.generateAvailableJobCode(authenticatedUserId(request));
        return ResponseEntity.ok(new ApiResponse<>(true, "Job ID generated successfully", jobCode));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EmployerJobResponse>> createJob(
            HttpServletRequest request,
            @Valid @RequestBody CreateJobRequest createJobRequest) {

        EmployerJobResponse job = jobService.createJob(authenticatedUserId(request), createJobRequest);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Job posting published successfully", job));
    }

    @PutMapping("/{jobId}")
    public ResponseEntity<ApiResponse<EmployerJobResponse>> updateJob(
            HttpServletRequest request,
            @PathVariable String jobId,
            @Valid @RequestBody CreateJobRequest createJobRequest) {

        EmployerJobResponse job = jobService.updateJob(authenticatedUserId(request), jobId, createJobRequest);
        return ResponseEntity.ok(new ApiResponse<>(true, "Job posting updated successfully", job));
    }

    @PatchMapping("/{jobId}/close")
    public ResponseEntity<ApiResponse<EmployerJobResponse>> closeJob(
            HttpServletRequest request, @PathVariable String jobId) {

        EmployerJobResponse job = jobService.closeJob(authenticatedUserId(request), jobId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Job posting closed successfully", job));
    }

    @PatchMapping("/{jobId}/reopen")
    public ResponseEntity<ApiResponse<EmployerJobResponse>> reopenJob(
            HttpServletRequest request, @PathVariable String jobId) {

        EmployerJobResponse job = jobService.reopenJob(authenticatedUserId(request), jobId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Job posting reopened successfully", job));
    }

    private String authenticatedUserId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthInterceptor.AUTHENTICATED_USER_ID);
    }
}
