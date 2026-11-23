package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;

import java.time.LocalDate;

public record MyHrRecentTimesheetStatus(
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        TimesheetStatus status) {
}
