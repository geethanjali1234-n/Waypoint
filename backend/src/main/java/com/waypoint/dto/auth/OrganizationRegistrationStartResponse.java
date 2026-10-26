package com.waypoint.dto.auth;

import java.time.LocalDateTime;

public record OrganizationRegistrationStartResponse(
        String registrationId,
        String adminEmail,
        LocalDateTime expiresAt) {
}
