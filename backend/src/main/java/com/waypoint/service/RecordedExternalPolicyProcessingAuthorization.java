package com.waypoint.service;

import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalScope;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The production {@link ExternalPolicyProcessingAuthorization}: Claude may receive retrieved policy excerpts only when
 * the organization has an ACTIVE organization approval for Claude covering COMPANY_WIDE content AND every excerpt is
 * COMPANY_WIDE AND every excerpt's document has an ACTIVE approval for exactly the version it came from (same
 * knowledge document, ingestion and source document). With no records - the default - nothing is permitted.
 *
 * <p>Checked on every request after retrieval, so a new version, a revoked policy or a revoked approval takes effect
 * immediately. A lookup failure propagates and the chat service treats it as not authorized.
 */
@Component
public class RecordedExternalPolicyProcessingAuthorization implements ExternalPolicyProcessingAuthorization {

    private final ExternalProcessingApprovalRepository approvals;

    public RecordedExternalPolicyProcessingAuthorization(ExternalProcessingApprovalRepository approvals) {
        this.approvals = approvals;
    }

    @Override
    public boolean permits(String organizationId, List<RetrievedChunk> chunks) {
        if (organizationId == null || organizationId.isBlank() || chunks == null || chunks.isEmpty()
                || chunks.stream().anyMatch(chunk -> chunk == null || chunk.accessLevel() != ChunkAccessLevel.COMPANY_WIDE)) {
            return false;
        }
        List<ExternalProcessingApproval> active = activeClaudeApprovals(organizationId);
        if (ExternalProcessingApprovalService.activeOrganizationApproval(
                active, ExternalAiProvider.ANTHROPIC_CLAUDE, ChunkAccessLevel.COMPANY_WIDE) == null) {
            return false;
        }
        return chunks.stream().allMatch(chunk -> active.stream().anyMatch(record ->
                record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION
                        && record.getDocumentAccessLevel() == ChunkAccessLevel.COMPANY_WIDE
                        && chunk.knowledgeDocumentId() != null
                        && chunk.knowledgeDocumentId().equals(record.getKnowledgeDocumentId())
                        && chunk.ingestionId() != null && chunk.ingestionId().equals(record.getIngestionId())
                        && chunk.sourceDocumentId() != null && chunk.sourceDocumentId().equals(record.getSourceDocumentId())));
    }

    @Override
    public boolean mayPermit(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return false;
        }
        return ExternalProcessingApprovalService.activeOrganizationApproval(activeClaudeApprovals(organizationId),
                ExternalAiProvider.ANTHROPIC_CLAUDE, ChunkAccessLevel.COMPANY_WIDE) != null;
    }

    private List<ExternalProcessingApproval> activeClaudeApprovals(String organizationId) {
        List<ExternalProcessingApproval> found = approvals.findByOrganizationIdAndProviderAndStatus(
                organizationId, ExternalAiProvider.ANTHROPIC_CLAUDE, ExternalProcessingApprovalStatus.ACTIVE);
        return found == null ? List.of() : found.stream()
                .filter(record -> record != null && organizationId.equals(record.getOrganizationId())
                        && record.getStatus() == ExternalProcessingApprovalStatus.ACTIVE
                        && record.getProvider() == ExternalAiProvider.ANTHROPIC_CLAUDE)
                .toList();
    }
}
