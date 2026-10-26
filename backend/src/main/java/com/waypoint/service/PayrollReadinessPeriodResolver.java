package com.waypoint.service;

import com.waypoint.config.PayrollReadinessProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

@Component
@RequiredArgsConstructor
public class PayrollReadinessPeriodResolver {
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US);
    private final PayrollReadinessProperties properties;

    public PayrollReadinessService.Period resolvePayrollMonth(LocalDate payrollMonthDate) {
        YearMonth payrollMonth = payrollMonthDate == null ? YearMonth.now(properties.zone()) : YearMonth.from(payrollMonthDate);
        YearMonth requiredMonth = payrollMonth.minusMonths(1);
        int cutoffDay = Math.min(properties.getCutoffDay(), payrollMonth.lengthOfMonth());
        return new PayrollReadinessService.Period(
                payrollMonth.atDay(1),
                requiredMonth.atDay(1),
                requiredMonth.atEndOfMonth(),
                payrollMonth.atDay(cutoffDay),
                payrollMonth.format(MONTH_LABEL),
                requiredMonth.format(MONTH_LABEL));
    }

    public PayrollReadinessService.Period resolveForRunDate(LocalDate runDate) {
        return resolvePayrollMonth(YearMonth.from(runDate).atDay(1));
    }
}
