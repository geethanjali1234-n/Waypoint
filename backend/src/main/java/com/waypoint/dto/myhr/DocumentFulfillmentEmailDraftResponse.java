package com.waypoint.dto.myhr;

/** Pre-populated, editable email for a completed employee document. */
public record DocumentFulfillmentEmailDraftResponse(String recipientEmail, String subject, String message,
        String attachmentFileName) {
}
