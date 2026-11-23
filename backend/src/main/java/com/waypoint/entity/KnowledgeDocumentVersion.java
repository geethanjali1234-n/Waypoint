package com.waypoint.entity;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.exception.DocumentExtractionException;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One approved StoredDocument within a KnowledgeDocument: what HR approved, what local extraction
 * found, and how far ingestion got. Embedded in KnowledgeDocument as its activeVersion or
 * pendingVersion and never stored on its own. Holds metadata only, never document text.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnowledgeDocumentVersion {

    // Source document, pinned at approval.
    private String sourceDocumentId;
    private String sourceDocumentName;
    private DocumentType documentType;
    private ChunkAccessLevel accessLevel;
    /** SHA-256 of the stored bytes HR approved; ingestion refuses any other content. */
    private String contentSha256;

    // Approval.
    private String approvedByUserId;
    private LocalDateTime approvedAt;

    // Local extraction results; null until extraction has run.
    private Integer pageCount;
    private Integer pagesWithoutText;
    /** Set only when HR explicitly accepted a PDF with pages that have no extractable text. */
    private String incompleteExtractionAcceptedByUserId;
    private LocalDateTime incompleteExtractionAcceptedAt;

    // G2 local content inspection and any later, bound human review. Never stores document text.
    private Integer inspectionFormatVersion;
    private Integer hiddenDocxRunsExcluded;
    private Integer suspiciousTextFragments;
    private Integer invisibleUnicodeCharacters;
    private Integer unicodeTagCharactersRemoved;
    private Integer hiddenCommentBlocks;
    private Integer instructionLikeContentMatches;
    private Integer pagesRequiringOcrReview;
    private Boolean contentReviewRequired;
    private String inspectionFindingsSha256;
    /** Hash of the bounded source-bound preview model, never the preview text itself. */
    private String reviewablePassagesSha256;
    /** SHA-256 of the exact extracted text eligible for indexing. */
    private String indexableTextSha256;
    private KnowledgeContentReviewDecision contentReviewDecision;
    private String contentReviewedByUserId;
    private LocalDateTime contentReviewedAt;
    private String reviewedSourceDocumentId;
    private String reviewedFindingsSha256;
    private String reviewedIndexableTextSha256;

    // A restore is a new approval of a formerly active source, never a revival of old chunks.
    private String restoredFromSourceDocumentId;
    private LocalDateTime restoredFromActivatedAt;

    // Ingestion.
    private KnowledgeIngestionStage stage;
    /** Tags every chunk this version writes; chunks serve only while it is the active version's. */
    private String ingestionId;
    /** Start of the current ingestion lease. */
    private LocalDateTime ingestionStartedAt;
    private Integer chunkCount;
    private String embeddingModel;
    private Integer embeddingDimensions;
    private LocalDateTime indexedAt;
    private LocalDateTime activatedAt;
    private String activatedByUserId;

    // Failure: reason codes only, never document content.
    private KnowledgeIngestionFailure failureReason;
    private DocumentExtractionException.Reason extractionFailureReason;
    private LocalDateTime failedAt;
}
