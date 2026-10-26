package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

public record MyHrTimesheetEntryRow(
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        BigDecimal hours,
        TimesheetStatus status,
        boolean countedTowardTotal) {
}
