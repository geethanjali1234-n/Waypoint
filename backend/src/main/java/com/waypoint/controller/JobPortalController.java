package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.job.JobApplicationResponse;
import com.waypoint.dto.job.JobDetailResponse;
import com.waypoint.dto.job.JobListResponse;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.JwtService;
import com.waypoint.service.JobPortalService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobPortalController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JobPortalService jobService;
    private final JwtService jwtService;

    @GetMapping
    public ResponseEntity<ApiResponse<JobListResponse>> getOpenJobs(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) EmploymentType employmentType,
            @RequestParam(required = false) WorkMode workMode,
            @RequestParam(required = false) BigDecimal salaryMin,
            @RequestParam(required = false) BigDecimal salaryMax,
            @RequestParam(required = false) SalaryRange salaryRange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate postedAfter,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) Integer maxDistanceMiles,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        String userId = resolveOptionalUserId(authorizationHeader);
        JobListResponse response = salaryRange == null
                ? jobService.getOpenJobs(title, location, employmentType, workMode, salaryMin, salaryMax,
                postedAfter, lat, lng, maxDistanceMiles, page, size, userId)
                : jobService.getOpenJobs(title, location, employmentType, workMode, salaryMin, salaryMax,
                salaryRange, postedAfter, page, size, userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Jobs retrieved successfully", response));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ApiResponse<JobDetailResponse>> getJobById(
            @PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        JobDetailResponse response = jobService.getJobById(jobId, resolveOptionalUserId(authorizationHeader));

        return ResponseEntity.ok(new ApiResponse<>(true, "Job retrieved successfully", response));
    }

    @PostMapping("/{jobId}/apply")
    public ResponseEntity<ApiResponse<JobApplicationResponse>> applyForJob(
            @PathVariable String jobId,
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        String userId = resolveUserId(authorizationHeader);
        JobApplicationResponse response = jobService.applyForJob(jobId, userId);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Job application submitted successfully", response));
    }

    private String resolveUserId(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Missing or invalid Authorization header");
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length());

        try {
            return jwtService.extractUserId(token);
        } catch (JwtException exception) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    /**
     * Best-effort resolution for the public job browsing endpoints: a
     * missing or invalid token just means "anonymous" rather than a 401,
     * since these endpoints are viewable without logging in.
     */
    private String resolveOptionalUserId(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            return null;
        }

        try {
            return jwtService.extractUserId(authorizationHeader.substring(BEARER_PREFIX.length()));
        } catch (JwtException exception) {
            return null;
        }
    }
}
