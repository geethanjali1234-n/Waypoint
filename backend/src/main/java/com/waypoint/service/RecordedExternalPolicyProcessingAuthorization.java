package com.waypoint.service;

import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalCoverage;
import com.waypoint.enums.ExternalProcessingApprovalScope;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The production {@link ExternalPolicyProcessingAuthorization}: Claude may receive retrieved policy excerpts only when
 * the organization has an ACTIVE organization approval for Claude covering COMPANY_WIDE content AND every excerpt is
 * COMPANY_WIDE AND no excerpt's document is excluded AND every excerpt is covered, either by an ACTIVE approval for
 * exactly the version it came from (same knowledge document, ingestion and source document), or - only when the
 * organization approval's coverage is ALL_PUBLISHED_COMPANY_WIDE - by being the document's currently published
 * company-wide version. With no records - the default - nothing is permitted.
 *
 * <p>Automatic coverage never applies to a document whose Claude approval was revoked unless a later explicit decision
 * (a new version approval, or lifting an exclusion) followed: an ambiguous history denies.
 *
 * <p>Checked on every request after retrieval, so a new version, a revoked policy or a revoked approval takes effect
 * immediately. A lookup failure propagates and the chat service treats it as not authorized.
 */
@Component
public class RecordedExternalPolicyProcessingAuthorization implements ExternalPolicyProcessingAuthorization {

    private final ExternalProcessingApprovalRepository approvals;
    /** Needed only for automatic coverage; without it automatic coverage never permits anything. */
    private final KnowledgeDocumentRepository knowledgeDocuments;

    public RecordedExternalPolicyProcessingAuthorization(ExternalProcessingApprovalRepository approvals) {
        this(approvals, null);
    }

    @Autowired
    public RecordedExternalPolicyProcessingAuthorization(ExternalProcessingApprovalRepository approvals,
                                                         KnowledgeDocumentRepository knowledgeDocuments) {
        this.approvals = approvals;
        this.knowledgeDocuments = knowledgeDocuments;
    }

    @Override
    public boolean permits(String organizationId, List<RetrievedChunk> chunks) {
        if (organizationId == null || organizationId.isBlank() || chunks == null || chunks.isEmpty()
                || chunks.stream().anyMatch(chunk -> chunk == null || chunk.accessLevel() != ChunkAccessLevel.COMPANY_WIDE)) {
            return false;
        }
        List<ExternalProcessingApproval> active = activeClaudeApprovals(organizationId);
        ExternalProcessingApproval organization = ExternalProcessingApprovalService.activeOrganizationApproval(
                active, ExternalAiProvider.ANTHROPIC_CLAUDE, ChunkAccessLevel.COMPANY_WIDE);
        if (organization == null) {
            return false;
        }
        if (chunks.stream().anyMatch(chunk -> isExcluded(active, chunk.knowledgeDocumentId()))) {
            return false;
        }
        boolean automatic = organization.effectiveCoverage() == ExternalProcessingApprovalCoverage.ALL_PUBLISHED_COMPANY_WIDE;
        List<ExternalProcessingApproval> history = automatic ? claudeHistory(organizationId) : List.of();
        Map<String, Optional<KnowledgeDocument>> documents = new HashMap<>();
        return chunks.stream().allMatch(chunk -> explicitlyApproved(active, chunk)
                || (automatic && automaticallyCovered(organizationId, history, documents, chunk)));
    }

    @Override
    public boolean mayPermit(String organizationId) {
        if (organizationId == null || organizationId.isBlank()) {
            return false;
        }
        return ExternalProcessingApprovalService.activeOrganizationApproval(activeClaudeApprovals(organizationId),
                ExternalAiProvider.ANTHROPIC_CLAUDE, ChunkAccessLevel.COMPANY_WIDE) != null;
    }

    /** The original rule: an ACTIVE approval for exactly this company-wide version. */
    private static boolean explicitlyApproved(List<ExternalProcessingApproval> active, RetrievedChunk chunk) {
        return active.stream().anyMatch(record ->
                record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION
                        && record.getDocumentAccessLevel() == ChunkAccessLevel.COMPANY_WIDE
                        && chunk.knowledgeDocumentId() != null
                        && chunk.knowledgeDocumentId().equals(record.getKnowledgeDocumentId())
                        && chunk.ingestionId() != null && chunk.ingestionId().equals(record.getIngestionId())
                        && chunk.sourceDocumentId() != null && chunk.sourceDocumentId().equals(record.getSourceDocumentId()));
    }

    static boolean isExcluded(List<ExternalProcessingApproval> active, String knowledgeDocumentId) {
        return active.stream().anyMatch(record -> record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_EXCLUSION
                && record.getStatus() == ExternalProcessingApprovalStatus.ACTIVE
                && (knowledgeDocumentId == null || knowledgeDocumentId.equals(record.getKnowledgeDocumentId())));
    }

    /**
     * The excerpt comes from the document's current published version in this organization: LIVE, activated, indexed,
     * company-wide, and the same ingestion and source document as the excerpt. Its Claude history must not hold an
     * unresolved revocation.
     */
    private boolean automaticallyCovered(String organizationId, List<ExternalProcessingApproval> history,
                                         Map<String, Optional<KnowledgeDocument>> documents, RetrievedChunk chunk) {
        String documentId = chunk.knowledgeDocumentId();
        if (knowledgeDocuments == null || documentId == null || chunk.ingestionId() == null
                || chunk.sourceDocumentId() == null || hasUnresolvedRevocation(history, documentId)) {
            return false;
        }
        Optional<KnowledgeDocument> found = documents.computeIfAbsent(documentId,
                id -> knowledgeDocuments.findByIdAndOrganizationId(id, organizationId));
        KnowledgeDocument entry = found == null ? null : found.orElse(null);
        if (entry == null || !organizationId.equals(entry.getOrganizationId())
                || !ExternalProcessingApprovalService.isActivated(entry)) {
            return false;
        }
        KnowledgeDocumentVersion version = entry.getActiveVersion();
        return version.getAccessLevel() == ChunkAccessLevel.COMPANY_WIDE
                && chunk.ingestionId().equals(version.getIngestionId())
                && chunk.sourceDocumentId().equals(version.getSourceDocumentId());
    }

    /**
     * True when the document's Claude approval was revoked and no explicit decision came after it: neither a version
     * approval recorded later, nor an exclusion lifted later. A revocation without a timestamp, or a later decision
     * without one, is treated as unresolved.
     */
    static boolean hasUnresolvedRevocation(List<ExternalProcessingApproval> history, String knowledgeDocumentId) {
        List<ExternalProcessingApproval> forDocument = history.stream()
                .filter(record -> knowledgeDocumentId.equals(record.getKnowledgeDocumentId())).toList();
        List<ExternalProcessingApproval> revocations = forDocument.stream()
                .filter(record -> record.getScope() == ExternalProcessingApprovalScope.DOCUMENT_VERSION
                        && record.getStatus() == ExternalProcessingApprovalStatus.REVOKED).toList();
        if (revocations.isEmpty()) {
            return false;
        }
        if (revocations.stream().anyMatch(record -> record.getRevokedAt() == null)) {
            return true;
        }
        LocalDateTime lastRevocation = revocations.stream().map(ExternalProcessingApproval::getRevokedAt)
                .max(LocalDateTime::compareTo).orElseThrow();
        return forDocument.stream().noneMatch(record -> {
            LocalDateTime decidedAt = switch (record.getScope()) {
                case DOCUMENT_VERSION -> record.getStatus() == ExternalProcessingApprovalStatus.ACTIVE
                        ? record.getApprovedAt() : null;
                case DOCUMENT_EXCLUSION -> record.getStatus() == ExternalProcessingApprovalStatus.REVOKED
                        ? record.getRevokedAt() : null;
                default -> null;
            };
            return decidedAt != null && decidedAt.isAfter(lastRevocation);
        });
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

    /** Every Claude record of the organization, ACTIVE and REVOKED; read only for automatic coverage. */
    private List<ExternalProcessingApproval> claudeHistory(String organizationId) {
        List<ExternalProcessingApproval> found = approvals.findByOrganizationIdOrderByApprovedAtDesc(organizationId);
        return found == null ? List.of() : found.stream()
                .filter(record -> record != null && organizationId.equals(record.getOrganizationId())
                        && record.getProvider() == ExternalAiProvider.ANTHROPIC_CLAUDE
                        && Objects.nonNull(record.getScope()) && Objects.nonNull(record.getStatus()))
                .toList();
    }
}
