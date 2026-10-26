package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.PayrollReadinessBulkReminderRequest;
import com.waypoint.dto.myhr.PayrollReadinessReminderResult;
import com.waypoint.dto.myhr.PayrollReadinessResponse;
import com.waypoint.exception.BusinessException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.PayrollReadinessService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;

@RestController
@RequestMapping("/api/myhr/payroll-readiness")
@RequiredArgsConstructor
public class PayrollReadinessController {
    private final CurrentUserResolver currentUserResolver;
    private final PayrollReadinessService payrollReadinessService;

    @GetMapping
    public ApiResponse<PayrollReadinessResponse> getReadiness(
            HttpServletRequest request,
            @RequestParam(name = "payrollMonth", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payrollMonth,
            @RequestParam(name = "payrollYear", required = false) Integer payrollYear,
            @RequestParam(name = "payrollMonthNumber", required = false) Integer payrollMonthNumber,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "readiness", required = false) String readiness,
            @RequestParam(name = "responsibility", defaultValue = "ALL") String responsibility,
            @RequestParam(name = "managerId", required = false) String managerId,
            @RequestParam(name = "clientId", required = false) String clientId,
            @RequestParam(name = "sort", defaultValue = "EMPLOYEE_ASC") String sort,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        LocalDate resolvedPayrollMonth = resolvePayrollMonth(payrollMonth, payrollYear, payrollMonthNumber);
        PayrollReadinessResponse response = payrollReadinessService.getReadiness(
                actorUserId, resolvedPayrollMonth, search, status != null ? status : readiness, responsibility,
                managerId, clientId, sort, page, size);
        return new ApiResponse<>(true, "Payroll readiness loaded", response);
    }

    private LocalDate resolvePayrollMonth(LocalDate payrollMonth, Integer payrollYear, Integer payrollMonthNumber) {
        if (payrollMonth != null && (payrollYear != null || payrollMonthNumber != null)) {
            throw new BusinessException("Use either payrollMonth or payrollYear/payrollMonthNumber, not both");
        }
        if (payrollYear == null && payrollMonthNumber == null) return payrollMonth;
        if (payrollYear == null || payrollMonthNumber == null) {
            throw new BusinessException("Both payrollYear and payrollMonthNumber are required when selecting payroll by year/month");
        }
        if (payrollMonthNumber < 1 || payrollMonthNumber > 12) {
            throw new BusinessException("payrollMonthNumber must be between 1 and 12");
        }
        if (payrollYear < 1900 || payrollYear > 3000) {
            throw new BusinessException("payrollYear is outside the supported range");
        }
        return YearMonth.of(payrollYear, payrollMonthNumber).atDay(1);
    }

    @PostMapping("/{employeeUserId}/reminders")
    public ApiResponse<PayrollReadinessReminderResult> sendReminder(
            HttpServletRequest request,
            @PathVariable String employeeUserId,
            @RequestParam(name = "payrollMonth", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payrollMonth) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Payroll readiness reminder processed",
                payrollReadinessService.sendReminder(actorUserId, payrollMonth, employeeUserId));
    }

    @PostMapping("/reminders/bulk")
    public ApiResponse<PayrollReadinessReminderResult> sendBulkReminders(
            HttpServletRequest request,
            @RequestParam(name = "payrollMonth", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate payrollMonth,
            @RequestBody PayrollReadinessBulkReminderRequest payload) {
        String actorUserId = currentUserResolver.resolveUserId(request);
        return new ApiResponse<>(true, "Payroll readiness reminders processed",
                payrollReadinessService.sendBulkReminders(actorUserId, payrollMonth, payload));
    }
}
