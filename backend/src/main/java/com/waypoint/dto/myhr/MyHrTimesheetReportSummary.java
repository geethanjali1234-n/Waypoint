package com.waypoint.dto.myhr;

import java.math.BigDecimal;

public record MyHrTimesheetReportSummary(
        long employees,
        long timesheets,
        BigDecimal totalWorkedHours,
        long awaitingApproval,
        long approved,
        long rejected,
        long draft,
        long exceptions) {
}
