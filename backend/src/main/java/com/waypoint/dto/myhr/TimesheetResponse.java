package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record TimesheetResponse(
        String id,
        String employeeUserId,
        String employeeId,
        String employeeName,
        UserRole employeeRole,
        String reportingManagerUserId,
        String reportingManagerLoginId,
        String reportingManagerName,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        List<TimesheetEntryResponse> entries,
        BigDecimal totalHours,
        List<TimesheetSupportingDocumentResponse> supportingDocuments,
        TimesheetStatus status,
        LocalDateTime submittedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        String reviewedByUserId,
        LocalDateTime reviewedAt,
        String reviewComment,
        String changeReason) {
}
