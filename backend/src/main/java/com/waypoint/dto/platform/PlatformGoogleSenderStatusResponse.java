package com.waypoint.dto.platform;

import java.time.LocalDateTime;

/** Credential-safe status of WayPoint's dedicated Gmail sender. */
public record PlatformGoogleSenderStatusResponse(
        boolean enabled,
        boolean authorized,
        String googleAccountEmail,
        LocalDateTime connectedAt,
        String lastErrorCode) {
}
