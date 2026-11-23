package com.waypoint.dto.myhr;

/**
 * Count-only profile-change queue state for the authenticated reviewer.
 * It deliberately excludes profile values, employee names, request ids, and comments.
 */
public record MyHrProfileChangeQueueSummary(
        int scopedRequests,
        int pending,
        int approved,
        int rejected) {
}
