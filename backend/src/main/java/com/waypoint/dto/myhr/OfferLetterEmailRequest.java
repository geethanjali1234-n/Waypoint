package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The only values HR / Company Admin may edit when emailing an offer letter.
 * The sender, organization, actor and PDF are always resolved server-side.
 * {@code message} is plain text; the server renders it into safe HTML.
 */
public record OfferLetterEmailRequest(
        @NotBlank @Size(max = 254) String recipientEmail,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 20000) String message,
        @Size(max = 160) String employeeName) {

    /**
     * {@code employeeName} is the name on the letter being sent (the one HR may
     * have edited on the prepare page). It only names the attachment for this
     * send and is never written to the employee profile; when absent the
     * profile name is used.
     */
    public OfferLetterEmailRequest(String recipientEmail, String subject, String message) {
        this(recipientEmail, subject, message, null);
    }
}
