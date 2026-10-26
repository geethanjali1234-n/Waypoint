package com.waypoint.service;

import com.waypoint.enums.DocumentType;

public record DocumentUploadRequest(
        String organizationId,
        String ownerUserId,
        DocumentType documentType,
        String relatedEntityType,
        String relatedEntityId,
        String fileName,
        String contentType,
        byte[] content,
        Integer version,
        String uploadedByUserId) {
}
