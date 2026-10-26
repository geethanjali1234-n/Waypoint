package com.waypoint.dto.myhr;

import com.waypoint.enums.AccountStatus;

public record EmployeeDirectReportItem(
        String userId,
        String employeeId,
        String name,
        String designation,
        AccountStatus accountStatus) {
}
