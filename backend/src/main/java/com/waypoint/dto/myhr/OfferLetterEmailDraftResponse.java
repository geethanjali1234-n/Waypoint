package com.waypoint.dto.myhr;

/** Pre-populated, editable offer letter email. {@code message} is plain text. */
public record OfferLetterEmailDraftResponse(String recipientEmail, String subject, String message,
        String attachmentFileName) {
}
