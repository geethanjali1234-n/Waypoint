package com.waypoint.dto.myhr;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.EmploymentType;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record EmployeeDetailResponse(
        String userId,
        String employeeId,
        String name,
        String firstName,
        String lastName,
        String personType,
        String workEmail,
        String personalEmail,
        String phoneNumber,
        String designation,
        EmploymentType employmentType,
        LocalDate startDate,
        String streetAddress,
        String city,
        String state,
        String zipCode,
        AccountStatus accountStatus,
        LocalDateTime createdAt,
        ReportingManagerRef reportingManager,
        List<ReportingAssignmentHistoryItem> reportingHistory,
        List<EmployeeDirectReportItem> directReports) {
}
