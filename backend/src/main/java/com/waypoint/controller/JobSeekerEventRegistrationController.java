package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.event.JobSeekerEventRegistrationResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.EventService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/job-seeker/registrations")
@RequiredArgsConstructor
public class JobSeekerEventRegistrationController {

    private final EventService eventService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<List<JobSeekerEventRegistrationResponse>>> getMyRegistrations(
            HttpServletRequest request) {

        String userId = resolveJobSeekerUserId(request);
        List<JobSeekerEventRegistrationResponse> response = eventService.getMyRegistrations(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Registered events retrieved successfully", response));
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
