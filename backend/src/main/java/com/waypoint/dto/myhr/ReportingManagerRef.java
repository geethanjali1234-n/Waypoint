package com.waypoint.dto.myhr;

import java.time.LocalDate;

public record ReportingManagerRef(
        String userId,
        String employeeId,
        String name,
        String roleLabel,
        LocalDate effectiveFrom,
        String assignedByName) {
}
