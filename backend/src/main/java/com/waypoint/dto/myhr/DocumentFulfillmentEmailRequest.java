package com.waypoint.dto.myhr;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** The editable values HR supplies when delivering a completed document. */
public record DocumentFulfillmentEmailRequest(
        @NotBlank @Email @Size(max = 254) String recipientEmail,
        @NotBlank @Size(max = 200) String subject,
        @NotBlank @Size(max = 20000) String message) {
}
