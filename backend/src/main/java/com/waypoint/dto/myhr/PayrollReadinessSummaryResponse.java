package com.waypoint.dto.myhr;

import java.math.BigDecimal;

public record PayrollReadinessSummaryResponse(
        long totalEmployees,
        long readyForPayroll,
        long monthSubmitted,
        long partiallyApproved,
        long actionRequired,
        long missingTimesheet,
        long incompleteTimesheet,
        long awaitingApproval,
        long correctionRequired,
        BigDecimal totalApprovedHours,
        BigDecimal totalWorkedHours,
        BigDecimal totalOvertimeHours) {
}
