package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.jobseeker.JobSeekerDashboardResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.JwtService;
import com.waypoint.service.JobSeekerDashboardService;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/job-seeker")
@RequiredArgsConstructor
public class JobSeekerDashboardController {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final JobSeekerDashboardService jobSeekerDashboardService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<JobSeekerDashboardResponse>> getDashboard(
            @RequestHeader(value = "Authorization", required = false) String authorizationHeader) {

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Missing or invalid Authorization header");
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length());

        String userId;
        String role;

        try {
            userId = jwtService.extractUserId(token);
            role = jwtService.extractRole(token);
        } catch (JwtException exception) {
            throw new UnauthorizedException("Invalid or expired token");
        }

        if (!UserRole.JOB_SEEKER.name().equals(role)) {
            throw new ForbiddenException("Access restricted to job seekers");
        }

        JobSeekerDashboardResponse response =
                jobSeekerDashboardService.getDashboard(userId);

        return ResponseEntity.ok(
                new ApiResponse<>(
                        true,
                        "JobSeeker dashboard retrieved successfully",
                        response
                )
        );
    }
}