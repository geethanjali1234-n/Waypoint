package com.waypoint.dto.myhr;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.TimesheetStatus;

import java.math.BigDecimal;

public record MyHrReportEmployeeRow(
        String employeeId,
        String name,
        String designation,
        EmploymentType employmentType,
        AccountStatus accountStatus,
        String reportingManagerName,
        BigDecimal totalHours,
        TimesheetStatus timesheetStatus) {
}
