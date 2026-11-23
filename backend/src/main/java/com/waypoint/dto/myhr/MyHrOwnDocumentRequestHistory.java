package com.waypoint.dto.myhr;

import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Bounded, document-content-free history used by Wavi for the signed-in employee only.
 * It intentionally omits document ids, attachments, descriptions, and participant identities.
 */
public record MyHrOwnDocumentRequestHistory(
        RequestedDocumentType documentType,
        String requestTitle,
        List<String> requestedDocuments,
        String category,
        boolean requestedByMe,
        DocumentRequestStatus status,
        LocalDateTime requestedAt,
        LocalDateTime reviewedAt,
        LocalDateTime approvedAt,
        LocalDateTime fulfilledAt,
        LocalDateTime rejectedAt,
        LocalDateTime signatureSubmittedAt,
        String rejectionReason,
        boolean signedDocumentRecorded,
        boolean downloadableCompletedDocument) {
}
