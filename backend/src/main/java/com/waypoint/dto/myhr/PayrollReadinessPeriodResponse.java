package com.waypoint.dto.myhr;

import java.time.LocalDate;

public record PayrollReadinessPeriodResponse(
        LocalDate payrollMonth,
        String payrollMonthLabel,
        LocalDate requiredPeriodStart,
        LocalDate requiredPeriodEnd,
        LocalDate payrollCutoffDate,
        long daysUntilCutoff) {
}
