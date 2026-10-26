package com.waypoint.dto.myhr;

import java.util.List;

public record MyHrWorkforceReportResponse(
        String scope,
        int periodDays,
        long totalEmployees,
        long activeEmployees,
        long inactiveEmployees,
        long totalManagers,
        List<MyHrReportEmployeeRow> employees,
        List<MyHrReportManagerRow> managerSummary) {
}
