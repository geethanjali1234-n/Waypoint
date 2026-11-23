package com.waypoint.dto.myhr;

import com.waypoint.enums.ProfileChangeStatus;

import java.time.LocalDateTime;

/** Safe history for the signed-in user's own profile change request. */
public record MyHrOwnProfileChangeHistory(
        ProfileChangeStatus status,
        LocalDateTime requestedAt,
        LocalDateTime processedAt,
        LocalDateTime effectiveAt) {
}
