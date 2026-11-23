package com.waypoint.dto.myhr;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MyHrChatRequest(
        @NotBlank(message = "Message must not be blank")
        @Size(max = 4000, message = "Message must be 4000 characters or fewer")
        String message,
        @Size(max = 10, message = "History must contain 10 messages or fewer")
        List<@Valid MyHrChatHistoryMessage> history) {
}
