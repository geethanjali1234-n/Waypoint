package com.waypoint.dto.myhr;

import java.util.List;

public record PayrollReadinessResponse(
        PayrollReadinessPeriodResponse period,
        PayrollReadinessSummaryResponse summary,
        List<PayrollReadinessRowResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {
}
