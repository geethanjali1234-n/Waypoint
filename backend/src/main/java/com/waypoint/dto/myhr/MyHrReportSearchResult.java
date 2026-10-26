package com.waypoint.dto.myhr;

import com.waypoint.enums.UserRole;

public record MyHrReportSearchResult(
        String userId,
        String employeeId,
        String name,
        UserRole role,
        String designation,
        String reportingManagerName) {
}
