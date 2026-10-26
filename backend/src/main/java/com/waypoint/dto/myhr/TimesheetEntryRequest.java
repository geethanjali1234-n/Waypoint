package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetEntryType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TimesheetEntryRequest(
        @NotNull(message = "Work date is required")
        LocalDate workDate,

        @NotNull(message = "Hours worked are required")
        @DecimalMin(value = "0.0", message = "Hours worked cannot be negative")
        @DecimalMax(value = "24.0", message = "Hours worked cannot exceed 24 hours per day")
        BigDecimal hoursWorked,

        TimesheetEntryType entryType,

        @Size(max = 500, message = "Description cannot exceed 500 characters")
        String description) {

    public TimesheetEntryRequest(LocalDate workDate, BigDecimal hoursWorked, String description) {
        this(workDate, hoursWorked, TimesheetEntryType.WORK, description);
    }
}
