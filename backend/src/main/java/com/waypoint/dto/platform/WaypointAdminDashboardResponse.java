package com.waypoint.dto.platform;

import com.waypoint.enums.UserRole;

/** Platform identity only; no customer organization or HR data is exposed. */
public record WaypointAdminDashboardResponse(
        String userId,
        String loginId,
        String email,
        UserRole role,
        boolean mustChangePassword) {
}
