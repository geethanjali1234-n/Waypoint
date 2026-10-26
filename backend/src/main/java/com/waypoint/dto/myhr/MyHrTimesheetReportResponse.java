package com.waypoint.dto.myhr;

import java.util.List;

public record MyHrTimesheetReportResponse(
        List<MyHrTimesheetReportRow> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        MyHrTimesheetReportSummary summary,
        List<MyHrTimesheetReportAttentionItem> attentionRequired,
        List<String> supportedScopes,
        List<String> unsupportedScopes) {
}
