package com.waypoint.dto.myhr;

public record TimesheetSupportingDocumentResponse(String id, String fileName,
        String identityValidationStatus, String identityValidationMessage) {
    public TimesheetSupportingDocumentResponse(String id, String fileName) {
        this(id, fileName, "UNVERIFIED", "Employee name not verified. HR must check the name before approval.");
    }
}
