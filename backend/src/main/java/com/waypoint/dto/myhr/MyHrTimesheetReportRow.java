package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record MyHrTimesheetReportRow(
        String id,
        String employeeUserId,
        String employeeId,
        String employeeName,
        UserRole employeeRole,
        String designation,
        String reportingManagerUserId,
        String reportingManagerName,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        BigDecimal totalHours,
        TimesheetStatus status,
        List<String> issues,
        LocalDateTime submittedAt,
        LocalDateTime reviewedAt,
        String reviewComment,
        String changeReason) {
}
