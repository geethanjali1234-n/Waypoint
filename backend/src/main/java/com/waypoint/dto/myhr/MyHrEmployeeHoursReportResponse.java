package com.waypoint.dto.myhr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record MyHrEmployeeHoursReportResponse(
        String employeeId,
        String employeeName,
        LocalDate startDate,
        LocalDate endDate,
        BigDecimal totalHours,
        List<MyHrTimesheetEntryRow> timesheets) {
}
