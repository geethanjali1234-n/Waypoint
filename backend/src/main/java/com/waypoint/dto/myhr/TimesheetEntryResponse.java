package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetEntryType;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TimesheetEntryResponse(
        LocalDate workDate,
        String dayOfWeek,
        BigDecimal hoursWorked,
        TimesheetEntryType entryType,
        String description) {

    public TimesheetEntryResponse(LocalDate workDate, String dayOfWeek, BigDecimal hoursWorked, String description) {
        this(workDate, dayOfWeek, hoursWorked, TimesheetEntryType.WORK, description);
    }
}
