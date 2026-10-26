package com.waypoint.dto.platform;

import java.util.List;

public record WaypointAdminOrganizationPageResponse(
        List<WaypointAdminOrganizationSummary> items,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
