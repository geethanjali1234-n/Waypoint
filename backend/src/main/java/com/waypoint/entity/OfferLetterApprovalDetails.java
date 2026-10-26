package com.waypoint.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * What the Company Admin reviews for a document that HR submitted for
 * approval (an offer letter, or any other document HR delivers to an employee). It is a snapshot taken at submission time: the approved PDF
 * itself is the request's {@code currentDocumentId}, and the approval status
 * lives in the request's existing status/approval fields
 * (SUBMITTED = pending approval, APPROVED, RETURNED_FOR_CORRECTION =
 * rejected by the Admin, FULFILLED = sent).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OfferLetterApprovalDetails {
    /** The document being approved: its request type and title (null on approvals stored before other documents needed approval = an offer letter). */
    private String documentType;
    private String documentTitle;
    /** The submitted file's name and type, so the approved copy is delivered exactly as submitted. */
    private String fileName;
    private String contentType;
    private String employeeName;
    private String designation;
    private String offerType;
    private String employmentCategory;
    private String joiningDate;
    private String issueDate;
    private String payRate;
    private String workLocation;
    private String rolesAndResponsibilities;
    private String submittedByUserId;
    private String submittedByName;
    private LocalDateTime submittedAt;
    private String submittedDocumentId;
    private Integer submittedDocumentVersion;
    private String approvedDocumentId;
    private Integer approvedDocumentVersion;
    private String approvalComment;
    /** The HR prepare-page form as submitted, so a rejected letter can be corrected without re-entering it. */
    private String formJson;
    /**
     * Where the company admin (the Organization Settings admin account) signs the submitted PDF, or null when the document carries no
     * signatory signature. When set, only the signatory can approve or reject it, and approving applies their stored
     * signature there (the signed PDF becomes the request's current document, which is what HR then sends).
     */
    private SignaturePlacement authorizedSignature;

    /** page is 1-based; x/y/width/height are fractions (0-1) of the page, measured from its top-left corner. */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SignaturePlacement {
        private int page;
        private double x;
        private double y;
        private double width;
        private double height;
    }
}
