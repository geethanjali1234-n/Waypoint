package com.waypoint.dto.myhr;

import java.time.LocalDateTime;

/**
 * One offer letter's approval state. {@code approvalStatus} is one of NONE (not yet submitted),
 * PENDING_APPROVAL, APPROVED, REJECTED or SENT. {@code formJson} is only returned to HR /
 * the Admin so a rejected letter can be corrected. {@code requiresAuthorizedSignature} marks a document the organization's
 * company admin account ({@code signatoryName}) signs on approval - only that account can approve or reject it.
 */
public record OfferLetterApprovalResponse(
        String requestId, String approvalStatus, String employeeName, String designation, String offerType,
        String employmentCategory, String joiningDate, String issueDate, String payRate, String workLocation,
        String rolesAndResponsibilities, String submittedByUserId, String submittedByName, LocalDateTime submittedAt,
        String reviewedByName, LocalDateTime reviewedAt, String rejectionReason, String documentId, String formJson,
        boolean canSubmit, boolean canApprove, boolean canSend,
        String documentType, String documentTitle, String fileName, String contentType,
        boolean requiresAuthorizedSignature, String signatoryName) {
}
