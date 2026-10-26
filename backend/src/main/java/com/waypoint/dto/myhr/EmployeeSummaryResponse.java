package com.waypoint.dto.myhr;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.EmploymentType;

import java.time.LocalDate;

public record EmployeeSummaryResponse(
        String userId,
        String employeeId,
        String name,
        String personType,
        String workEmail,
        String personalEmail,
        String designation,
        EmploymentType employmentType,
        LocalDate startDate,
        String reportingManagerName,
        AccountStatus accountStatus) {
}
