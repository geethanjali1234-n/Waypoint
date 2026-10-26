package com.waypoint.dto.myhr;

import com.waypoint.enums.DocumentRequestStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Signed I-983 workflow state for one document request, as the viewer is
 * allowed to see it: the employee only gets their own submitted files (plus
 * the signed copy once approved); HR/Admin get every version.
 */
public record I983WorkflowResponse(
        DocumentRequestResponse request,
        String originalDocumentId,
        String currentDocumentId,
        String signedDocumentId,
        // true when the signed PDF was generated from the current working version
        boolean signedIsCurrent,
        // The viewer is a reviewer (assigned HR or Company Admin); canReview adds
        // that the request is still awaiting review, so actions are allowed now.
        boolean viewerIsReviewer,
        boolean canReview,
        boolean canResubmit,
        String returnReason,
        String reviewedByName,
        LocalDateTime reviewedAt,
        String approvedByName,
        LocalDateTime approvedAt,
        LocalDateTime submittedAt,
        List<Version> versions,
        List<HistoryEntry> history) {

    public record Version(
            String documentId, int version, String kind, String fileName,
            String uploadedByName, String uploadedByRole, LocalDateTime uploadedAt,
            String basedOnDocumentId, String signatureSource, String signatoryName,
            String signatoryTitle, LocalDate signatureEffectiveDate, List<Integer> signaturePages) {
    }

    public record HistoryEntry(
            String action, String actorName, String actorRole,
            DocumentRequestStatus fromStatus, DocumentRequestStatus toStatus,
            String detail, LocalDateTime at) {
    }
}
