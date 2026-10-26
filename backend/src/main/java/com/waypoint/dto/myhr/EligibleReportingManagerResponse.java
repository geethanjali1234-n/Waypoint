package com.waypoint.dto.myhr;

import java.time.LocalDate;

public record EligibleReportingManagerResponse(
        String userId,
        String loginId,
        String name,
        String roleLabel,
        LocalDate startDate) {
}
