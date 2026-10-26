package com.waypoint.entity;

import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "document_requests")
@CompoundIndexes({
        @CompoundIndex(name = "organization_recipient_status",
                def = "{'organizationId': 1, 'requestedForUserId': 1, 'status': 1}"),
        @CompoundIndex(name = "organization_status_requestedAt",
                def = "{'organizationId': 1, 'status': 1, 'requestedAt': -1}"),
        @CompoundIndex(name = "organization_requestedAt",
                def = "{'organizationId': 1, 'requestedAt': -1}"),
        @CompoundIndex(name = "organization_requester_requestedAt",
                def = "{'organizationId': 1, 'requestedByUserId': 1, 'requestedAt': -1}"),
        @CompoundIndex(name = "organization_recipient_requestedAt",
                def = "{'organizationId': 1, 'requestedForUserId': 1, 'requestedAt': -1}")
})
public class DocumentRequest {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String requestedByUserId;

    @Indexed
    private String requestedForUserId;

    private RequestedDocumentType documentType;
    private String requestTitle;
    private List<String> requestedDocuments;
    private String requestDescription;
    private String category;
    private String priority;
    private String requestDirection;
    private List<String> attachmentDocumentIds;
    private LocalDate requiredBy;

    @Indexed
    private DocumentRequestStatus status;

    private String fulfilledByUserId;
    private String fulfilledDocumentId;
    private String employeeSignatureDocumentId;
    private String signedResponseDocumentId;
    private LocalDateTime signatureSubmittedAt;
    private String rejectionReason;
    private String rejectedByUserId;
    private LocalDateTime requestedAt;
    private LocalDateTime fulfilledAt;
    private LocalDateTime rejectedAt;

    // Signed I-983 workflow. originalDocumentId is the employee's latest
    // submitted file, currentDocumentId the version HR/Admin works on (the
    // original or an HR/Admin replacement) and organizationSignedDocumentId
    // the generated signed PDF. Every file is kept in documentVersions.
    private String originalDocumentId;
    private String currentDocumentId;
    private String organizationSignedDocumentId;
    private List<DocumentRequestVersion> documentVersions;
    private List<DocumentRequestHistoryEntry> history;
    private LocalDateTime submittedAt;
    private String reviewedByUserId;
    private LocalDateTime reviewedAt;
    private String returnReason;
    private String approvedByUserId;
    private LocalDateTime approvedAt;

    // Offer Letter approval: set when HR submits the letter for Company Admin approval.
    private OfferLetterApprovalDetails offerLetterApproval;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
