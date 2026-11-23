package com.waypoint.dto.myhr;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;

import java.time.LocalDateTime;
import java.util.List;

/**
 * One MyHR knowledge base entry as HR and company administrators see it. Metadata only: never
 * policy text, content hashes, embeddings, storage keys, or user ids.
 *
 * <p>sourceDocumentId and ingestionId are opaque handles HR echoes back to accept incomplete text
 * or activate exactly the version they reviewed, so a concurrent change is never acted on unseen.
 */
public record KnowledgeDocumentResponse(
        String id,
        String title,
        KnowledgeDocumentStatus status,
        Version activeVersion,
        Version pendingVersion,
        int previousVersionCount,
        List<HistoricalVersion> previousVersions,
        LocalDateTime createdAt,
        LocalDateTime revokedAt,
        Actions actions) {

    public KnowledgeDocumentResponse {
        previousVersions = previousVersions == null ? List.of() : List.copyOf(previousVersions);
    }

    /** Backward-compatible constructor for callers before history details were exposed. */
    public KnowledgeDocumentResponse(
            String id, String title, KnowledgeDocumentStatus status, Version activeVersion, Version pendingVersion,
            int previousVersionCount, LocalDateTime createdAt, LocalDateTime revokedAt, Actions actions) {
        this(id, title, status, activeVersion, pendingVersion, previousVersionCount, List.of(), createdAt, revokedAt,
                actions);
    }

    public record Version(
            String sourceDocumentId,
            String fileName,
            ChunkAccessLevel accessLevel,
            KnowledgeIngestionStage stage,
            Integer pageCount,
            Integer pagesWithoutText,
            boolean incompleteTextAccepted,
            String ingestionId,
            Integer indexedSectionCount,
            LocalDateTime approvedAt,
            LocalDateTime ingestionStartedAt,
            LocalDateTime indexedAt,
            LocalDateTime activatedAt,
            LocalDateTime failedAt,
            String problem,
            boolean retryable,
            ContentReview contentReview) {

        /** A version without a G2 content inspection (for example a legacy LIVE version). */
        public Version(String sourceDocumentId, String fileName, ChunkAccessLevel accessLevel,
                       KnowledgeIngestionStage stage, Integer pageCount, Integer pagesWithoutText,
                       boolean incompleteTextAccepted, String ingestionId, Integer indexedSectionCount,
                       LocalDateTime approvedAt, LocalDateTime ingestionStartedAt, LocalDateTime indexedAt,
                       LocalDateTime activatedAt, LocalDateTime failedAt, String problem, boolean retryable) {
            this(sourceDocumentId, fileName, accessLevel, stage, pageCount, pagesWithoutText, incompleteTextAccepted,
                    ingestionId, indexedSectionCount, approvedAt, ingestionStartedAt, indexedAt, activatedAt, failedAt,
                    problem, retryable, null);
        }
    }

    /**
     * Counts from the local G2 content inspection and the recorded review decision, if any. Counts
     * only: never document text, passages, fingerprints or reviewer ids. The text itself is served
     * only by the content-review preview, to knowledge managers.
     */
    public record ContentReview(
            boolean inspected,
            boolean required,
            boolean satisfied,
            KnowledgeContentReviewDecision decision,
            int hiddenDocxRunsExcluded,
            int suspiciousTextFragments,
            int invisibleUnicodeCharacters,
            int unicodeTagCharactersRemoved,
            int hiddenCommentBlocks,
            int instructionLikeContentMatches,
            int pagesRequiringOcrReview) {
    }

    /** Metadata for a formerly active source. It never includes text, hashes, storage keys or chunks. */
    public record HistoricalVersion(
            String sourceDocumentId,
            String fileName,
            ChunkAccessLevel accessLevel,
            LocalDateTime activatedAt,
            LocalDateTime supersededAt,
            boolean restorable) {
    }

    /** What the current state and configuration allow; the server re-checks every action. */
    public record Actions(
            boolean acceptIncompleteText,
            boolean ingest,
            boolean activate,
            boolean replace,
            boolean revoke,
            boolean retryCleanup,
            boolean reviewContent,
            boolean restoreHistoricalVersion) {

        public Actions(boolean acceptIncompleteText, boolean ingest, boolean activate, boolean replace,
                       boolean revoke, boolean retryCleanup) {
            this(acceptIncompleteText, ingest, activate, replace, revoke, retryCleanup, false, false);
        }

        public Actions(boolean acceptIncompleteText, boolean ingest, boolean activate, boolean replace,
                       boolean revoke, boolean retryCleanup, boolean reviewContent) {
            this(acceptIncompleteText, ingest, activate, replace, revoke, retryCleanup, reviewContent, false);
        }
    }
}
