package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One of the signed-in user's own timesheets that overlaps a requested date range; no entries or comments. */
public record MyHrTimesheetPeriodStatus(
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        TimesheetStatus status,
        LocalDateTime submittedAt) {
}
