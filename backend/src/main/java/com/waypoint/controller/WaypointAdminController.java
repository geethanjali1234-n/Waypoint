package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.platform.WaypointAdminDashboardResponse;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.WaypointAdminAuthenticatedUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waypoint-admin")
@RequiredArgsConstructor
public class WaypointAdminController {

    private final CurrentUserResolver currentUserResolver;
    private final WaypointAdminAuthenticatedUserService authenticatedUserService;

    @GetMapping("/dashboard")
    public ApiResponse<WaypointAdminDashboardResponse> dashboard(HttpServletRequest request) {
        String userId = currentUserResolver.resolveUserId(request);
        var admin = authenticatedUserService.require(userId);
        return new ApiResponse<>(true, "Waypoint Admin dashboard",
                new WaypointAdminDashboardResponse(
                        admin.userId(), admin.loginId(), admin.email(), admin.role(),
                        admin.mustChangePassword()));
    }
}
