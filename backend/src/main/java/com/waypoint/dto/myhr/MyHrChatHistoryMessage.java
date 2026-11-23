package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * One earlier message of a Wavi conversation, as the browser returns it. provenance is the opaque stamp the server
 * issued for this exact message; it is optional, never trusted by itself, and never sent to an AI provider. A
 * message without a stamp that verifies is not sent to an external AI provider.
 */
public record MyHrChatHistoryMessage(
        @NotBlank(message = "History role is required")
        @Pattern(regexp = "user|assistant", message = "History role must be user or assistant")
        String role,
        @NotBlank(message = "History content is required")
        @Size(max = 4000, message = "History content must be 4000 characters or fewer")
        String content,
        @Size(max = 200, message = "History provenance must be 200 characters or fewer")
        String provenance) {

    public MyHrChatHistoryMessage(String role, String content) {
        this(role, content, null);
    }

    /** Content stays out of logs. */
    @Override
    public String toString() {
        return "MyHrChatHistoryMessage[role=" + role + ", stamped=" + (provenance != null) + "]";
    }
}
