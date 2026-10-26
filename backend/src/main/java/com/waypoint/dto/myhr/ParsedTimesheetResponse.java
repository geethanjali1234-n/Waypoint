package com.waypoint.dto.myhr;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ParsedTimesheetResponse(
        String supportingDocumentId,
        String supportingDocumentName,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        BigDecimal totalHours,
        List<TimesheetEntryResponse> entries,
        String extractionStatus,
        String message) {
}
