package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;

public record TimesheetUpsertRequest(
        @NotNull(message = "Period start date is required")
        LocalDate periodStartDate,

        @NotNull(message = "Period end date is required")
        LocalDate periodEndDate,

        @NotEmpty(message = "At least one timesheet entry is required")
        @Size(max = 31, message = "A timesheet period cannot contain more than 31 entries")
        List<@Valid TimesheetEntryRequest> entries,

        @Size(max = 10, message = "A timesheet cannot have more than 10 supporting documents")
        List<String> supportingDocumentIds,

        @NotNull(message = "Timesheet status is required")
        TimesheetStatus status,

        @Size(max = 1000, message = "Reason for change cannot exceed 1000 characters")
        String changeReason,

        Boolean confirmReedit) {
}
