package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TimesheetReviewRequest(
        @NotNull(message = "Review status is required")
        TimesheetStatus status,

        @Size(max = 500, message = "Review comment cannot exceed 500 characters")
        String comment) {
}
