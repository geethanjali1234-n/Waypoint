package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.platform.WaypointAdminOrganizationDetail;
import com.waypoint.dto.platform.WaypointAdminOrganizationPageResponse;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.WaypointAdminOrganizationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/waypoint-admin/organizations")
@RequiredArgsConstructor
public class WaypointAdminOrganizationController {

    private final CurrentUserResolver currentUserResolver;
    private final WaypointAdminOrganizationService organizationService;

    @GetMapping
    public ApiResponse<WaypointAdminOrganizationPageResponse> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        String userId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Organizations retrieved",
                organizationService.list(userId, page, size, search));
    }

    @GetMapping("/{organizationId}")
    public ApiResponse<WaypointAdminOrganizationDetail> get(
            HttpServletRequest request, @PathVariable String organizationId) {
        String userId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Organization retrieved",
                organizationService.get(userId, organizationId));
    }
}
