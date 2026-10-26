package com.waypoint.dto.myhr;

import java.time.LocalDate;

public record ReportingAssignmentHistoryItem(
        String managerName,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        boolean active) {
}
