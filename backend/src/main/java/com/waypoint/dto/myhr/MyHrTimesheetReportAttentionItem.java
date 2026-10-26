package com.waypoint.dto.myhr;

public record MyHrTimesheetReportAttentionItem(
        String issue,
        String label,
        long count) {
}
