package com.waypoint.dto.platform;

import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OrganizationStatus;

import java.time.LocalDateTime;

public record WaypointAdminOrganizationDetail(
        String id,
        String organizationCode,
        String organizationName,
        OrganizationStatus status,
        String organizationEmail,
        String phoneNumber,
        String state,
        String city,
        String streetAddress,
        String zipCode,
        CompanySize organizationSize,
        String description,
        String industry,
        String website,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
