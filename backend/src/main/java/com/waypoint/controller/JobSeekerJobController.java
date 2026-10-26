package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.jobseeker.JobSeekerApplicationResponse;
import com.waypoint.dto.jobseeker.JobSeekerJobResponse;
import com.waypoint.security.JwtAuthInterceptor;
import com.waypoint.service.JobSeekerJobService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/job-seeker/jobs")
@RequiredArgsConstructor
public class JobSeekerJobController {

    private final JobSeekerJobService jobSeekerJobService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<JobSeekerJobResponse>>> listOpenJobs(
            HttpServletRequest request,
            @RequestParam(required = false) String search) {

        List<JobSeekerJobResponse> jobs = jobSeekerJobService.listOpenJobs(authenticatedUserId(request), search);
        return ResponseEntity.ok(new ApiResponse<>(true, "Open jobs retrieved successfully", jobs));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<JobSeekerJobResponse>> getJob(
            HttpServletRequest request, @PathVariable String jobId) {

        JobSeekerJobResponse job = jobSeekerJobService.getJob(authenticatedUserId(request), jobId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Job retrieved successfully", job));
    }

    @PostMapping("/{jobId}/applications")
    public ResponseEntity<ApiResponse<JobSeekerApplicationResponse>> apply(
            HttpServletRequest request, @PathVariable String jobId) {

        JobSeekerApplicationResponse application = jobSeekerJobService.apply(
                authenticatedUserId(request), jobId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Application submitted successfully", application));
    }

    private String authenticatedUserId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthInterceptor.AUTHENTICATED_USER_ID);
    }
}
