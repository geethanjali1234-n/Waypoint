package com.waypoint.dto.myhr;

import java.util.List;

public record PayrollReadinessBulkReminderRequest(
        List<String> employeeUserIds,
        String search,
        String readiness,
        String responsibility,
        String managerId,
        String sort) {
    public PayrollReadinessBulkReminderRequest(List<String> employeeUserIds) {
        this(employeeUserIds, null, null, null, null, null);
    }
}

