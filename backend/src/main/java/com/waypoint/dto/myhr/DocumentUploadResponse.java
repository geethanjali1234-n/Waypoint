package com.waypoint.dto.myhr;

public record DocumentUploadResponse(
        String id,
        String fileName,
        String contentType,
        long fileSize,
        String documentType) {
}
