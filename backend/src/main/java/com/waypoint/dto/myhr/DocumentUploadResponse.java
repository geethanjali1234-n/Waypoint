package com.waypoint.dto.myhr;

public record DocumentUploadResponse(
        String id,
        String fileName,
        String contentType,
        long fileSize,
        String documentType,
        String identityValidationStatus,
        String identityValidationMessage) {
    public DocumentUploadResponse(String id, String fileName, String contentType, long fileSize, String documentType) {
        this(id, fileName, contentType, fileSize, documentType, null, null);
    }
}
