package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.employer.EmployerDashboardResponse;
import com.waypoint.security.JwtAuthInterceptor;
import com.waypoint.service.EmployerDashboardService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/employer")
@RequiredArgsConstructor
public class EmployerDashboardController {

    private final EmployerDashboardService employerDashboardService;

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<EmployerDashboardResponse>> getDashboard(HttpServletRequest request) {

        String authenticatedUserId = (String) request.getAttribute(JwtAuthInterceptor.AUTHENTICATED_USER_ID);

        EmployerDashboardResponse response = employerDashboardService.getDashboard(authenticatedUserId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Dashboard loaded successfully", response));
    }
}
