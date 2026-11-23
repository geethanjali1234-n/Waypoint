package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * The signed-in user's most recently submitted timesheet. {@code submittedAt} is the time of the
 * latest submission or resubmission of that timesheet; it is null when a submitted record carries
 * no submission time, so no date can be reported.
 */
public record MyHrLatestTimesheetSubmission(
        LocalDateTime submittedAt,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        TimesheetStatus status) {
}
