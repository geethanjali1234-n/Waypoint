package com.waypoint.dto.myhr;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record OfferLetterResponse(String documentRequestId, String employeeUserId, String employeeName,
        String employeeId, String employeeAddress, String employeeEmail, String organizationName, String organizationAddress,
        String offerType, String employmentCategory, String designation, LocalDate offerDate, LocalDate startDate,
        LocalDate endDate, String workLocation, String reportingManager, String internshipSchedule, String compensationType,
        String compensationAmount, String payFrequency, String timesheetRequirement, String benefitsEligibility,
        String issuerName, String issuerDesignation, String status, List<OfferLetterVersionResponse> versions, String previewHtml) {
    public record OfferLetterVersionResponse(Integer version, String documentId, String status, String generatedByUserId,
            LocalDateTime generatedAt, LocalDateTime sentAt) { }
}
