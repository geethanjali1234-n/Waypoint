package com.waypoint.dto.auth;

public record OrganizationRegistrationResponse(
        String organizationId,
        String organizationCode,
        String adminLoginId) {
}
