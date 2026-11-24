package com.waypoint.dto.myhr;

import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalCoverage;
import com.waypoint.enums.ExternalProcessingApprovalScope;
import com.waypoint.enums.ExternalProcessingApprovalStatus;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * One approval record as an admin sees it. effective says whether it currently allows anything: an ACTIVE document
 * approval stops taking effect when its policy gets a new version, is revoked, or the organization approval ends.
 */
public record ExternalProcessingApprovalResponse(
        String id,
        ExternalAiProvider provider,
        ExternalProcessingApprovalScope scope,
        ExternalProcessingApprovalStatus status,
        boolean effective,
        Set<ChunkAccessLevel> accessLevels,
        String knowledgeDocumentId,
        String ingestionId,
        String documentTitle,
        String approvalReference,
        String approvedByUserId,
        LocalDateTime approvedAt,
        String revokedByUserId,
        LocalDateTime revokedAt,
        String revocationReason,
        /** ORGANIZATION records: which documents they cover (REVIEWED_VERSIONS for older records); otherwise null. */
        ExternalProcessingApprovalCoverage coverage) {

    public static ExternalProcessingApprovalResponse of(ExternalProcessingApproval record, boolean effective) {
        return new ExternalProcessingApprovalResponse(record.getId(), record.getProvider(), record.getScope(),
                record.getStatus(), effective, record.getAccessLevels() == null ? null : Set.copyOf(record.getAccessLevels()),
                record.getKnowledgeDocumentId(), record.getIngestionId(), record.getDocumentTitle(),
                record.getApprovalReference(), record.getApprovedByUserId(), record.getApprovedAt(),
                record.getRevokedByUserId(), record.getRevokedAt(), record.getRevocationReason(),
                record.getScope() == ExternalProcessingApprovalScope.ORGANIZATION ? record.effectiveCoverage() : null);
    }
}
