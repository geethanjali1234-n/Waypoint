package com.waypoint.dto.myhr;

/**
 * Count-only document-request queue state for the authenticated HR or Company Admin user.
 * It intentionally contains no request ids, employee data, document data, or attachment references.
 */
public record MyHrDocumentRequestQueueSummary(
        int assignedToMe,
        int pendingFulfillment,
        int awaitingI983Review,
        int returnedForCorrection,
        int pendingAdminApproval,
        int completed) {
}
