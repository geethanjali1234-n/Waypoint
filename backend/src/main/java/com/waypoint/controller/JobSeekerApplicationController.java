package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.job.JobSeekerApplicationResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.JobPortalService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/job-seeker/applications")
@RequiredArgsConstructor
public class JobSeekerApplicationController {

    private final JobPortalService jobService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<List<JobSeekerApplicationResponse>>> getMyApplications(
            HttpServletRequest request) {

        String userId = resolveJobSeekerUserId(request);
        List<JobSeekerApplicationResponse> response = jobService.getMyApplications(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Applications retrieved successfully", response));
    }

    private String resolveJobSeekerUserId(HttpServletRequest request) {
        String userId = currentUserResolver.resolveUserId(request);
        String role = currentUserResolver.resolveRole(request);

        if (!UserRole.JOB_SEEKER.name().equals(role)) {
            throw new ForbiddenException("Access restricted to job seekers");
        }

        return userId;
    }
}
