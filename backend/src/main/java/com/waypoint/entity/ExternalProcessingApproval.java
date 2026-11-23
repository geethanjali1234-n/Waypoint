package com.waypoint.entity;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalScope;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * A Company Admin's recorded approval for sending company content to one external AI provider. Nothing is approved
 * unless a record says so. Records are never deleted or edited back to ACTIVE: revocation sets the revocation fields,
 * and approving again creates a new record, so the collection is the approval and revocation history.
 *
 * <p>An ORGANIZATION record lists the content access levels the organization approved for the provider. A
 * DOCUMENT_VERSION record approves one knowledge document's exact activated version, identified by its ingestion id
 * (which changes with every new version) and source document; a new version is therefore never covered by an
 * earlier approval.
 *
 * <p>approvalReference is the admin's reference to the organizational decision this records (for example a
 * signed data-processing agreement or a ticket). Waypoint records it; it does not create that consent.
 *
 * <p>No index annotations: indexes are created only by an explicitly approved setup step.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "external_processing_approvals")
public class ExternalProcessingApproval {

    @Id
    private String id;

    private String organizationId;
    private ExternalAiProvider provider;
    private ExternalProcessingApprovalScope scope;
    private ExternalProcessingApprovalStatus status;

    /** ORGANIZATION scope: the content access levels approved. Never EMPLOYEE_PRIVATE. */
    private Set<ChunkAccessLevel> accessLevels;

    /** DOCUMENT_VERSION scope: the exact version approved. */
    private String knowledgeDocumentId;
    private String ingestionId;
    private String sourceDocumentId;
    private String contentSha256;
    private String documentTitle;
    private ChunkAccessLevel documentAccessLevel;

    private String approvalReference;
    private String approvedByUserId;
    private LocalDateTime approvedAt;

    private String revokedByUserId;
    private LocalDateTime revokedAt;
    private String revocationReason;

    @Version
    private Long version;
}
