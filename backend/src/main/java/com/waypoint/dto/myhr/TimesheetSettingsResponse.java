package com.waypoint.dto.myhr;

import com.waypoint.enums.PayrollPeriod;

public record TimesheetSettingsResponse(
        PayrollPeriod payrollPeriod
) {}
