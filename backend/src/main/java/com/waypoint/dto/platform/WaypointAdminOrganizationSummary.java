package com.waypoint.dto.platform;

import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OrganizationStatus;

import java.time.LocalDateTime;

public record WaypointAdminOrganizationSummary(
        String id,
        String organizationCode,
        String organizationName,
        OrganizationStatus status,
        String organizationEmail,
        String city,
        String state,
        String industry,
        CompanySize organizationSize,
        LocalDateTime createdAt) {
}
