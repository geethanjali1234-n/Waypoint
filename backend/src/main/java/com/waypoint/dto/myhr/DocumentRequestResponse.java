package com.waypoint.dto.myhr;

import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public record DocumentRequestResponse(
        String id,
        String requestedByUserId, String requestedByLoginId, String requestedByEmail, String requestedByStreetAddress,
        String requestedByCity, String requestedByState, String requestedByZipCode, String requestedByName, UserRole requestedByRole,
        String requestedForUserId, String requestedForLoginId, String requestedForName, UserRole requestedForRole,
        RequestedDocumentType documentType, String requestTitle, List<String> requestedDocuments,
        String requestDescription, String category, String priority, String requestDirection,
        List<String> attachmentDocumentIds, List<String> attachmentFileNames, LocalDate requiredBy, DocumentRequestStatus status,
        String fulfilledDocumentId, String employeeSignatureDocumentId, String signedResponseDocumentId,
        LocalDateTime signatureSubmittedAt, String rejectionReason, String rejectedByUserId,
        LocalDateTime requestedAt, LocalDateTime fulfilledAt, LocalDateTime rejectedAt,
        // Signed I-983 workflow: set once the employee has uploaded their I-983 (see I983WorkflowService).
        String originalDocumentId) {
}
