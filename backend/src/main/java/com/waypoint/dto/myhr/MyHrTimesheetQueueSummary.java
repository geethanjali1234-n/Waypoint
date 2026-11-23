package com.waypoint.dto.myhr;

/**
 * Aggregate-only timesheet review counts for the authenticated reviewer's permitted scope.
 * Employee identities, hours and payroll data deliberately do not leave the service boundary.
 */
public record MyHrTimesheetQueueSummary(
        int visibleForReview,
        int submitted,
        int approved,
        int rejected) {
}
