package com.waypoint.dto.myhr;

import com.waypoint.enums.PayrollReadinessResponsibility;
import com.waypoint.enums.PayrollReadinessState;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.util.List;

public record PayrollReadinessRowResponse(
        String employeeUserId,
        String employeeId,
        String employeeName,
        UserRole role,
        String designation,
        String reportingManagerName,
        LocalDate requiredPeriodStart,
        LocalDate requiredPeriodEnd,
        List<TimesheetStatus> existingTimesheetStatuses,
        int requiredDays,
        int coveredDays,
        int missingDays,
        int approvedCoveredDays,
        int submittedCoveredDays,
        boolean monthSubmissionComplete,
        double coveragePercentage,
        BigDecimal approvedHours,
        BigDecimal workedHours,
        BigDecimal overtimeHours,
        BigDecimal leaveHours,
        PayrollReadinessState readinessState,
        PayrollReadinessResponsibility responsibility,
        String reason,
        boolean reminderEligible,
        LocalDateTime lastReminderAt) {
}
