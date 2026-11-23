package com.waypoint.service;

import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.entity.KnowledgeVersionHistoryEntry;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.StorageProvider;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.DocumentExtractionException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.KnowledgeChunkRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.DocumentTextExtractor.ExtractedText;
import com.waypoint.service.DocumentTextExtractor.ContentInspection;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * HR's gate into the MyHR knowledge base: approving a company policy, replacing it with a newer
 * stored document, accepting a PDF whose text is incomplete, and revoking it.
 *
 * <p>Every operation takes a {@link VerifiedMyHrActor}, must be HR or COMPANY_ADMIN, and reads and
 * writes only the actor's organization. Approval is local: it reads the stored bytes through
 * {@link DocumentService#getStoredContent}, never getContent (which can synthesize placeholder
 * documents), pins their SHA-256, and runs local text extraction to check the document is usable.
 * The extracted text is then discarded; nothing here embeds, chunks or calls an AI provider, and
 * ingestion later refuses any content whose hash differs from the approved one.
 *
 * <p>Approval and replacement require {@link KnowledgeDocumentIndexGuard}, because only the unique
 * LIVE source-document index stops two concurrent approvals of one document from both succeeding.
 * Revocation never depends on it. Replacement, acceptance and revocation save the entry under its
 * {@code @Version}, so a concurrent change makes them fail instead of overwriting it.
 *
 * <p>Logs carry knowledge document ids, stages, roles and counts only: never document text, file
 * names, hashes, user or organization ids, storage keys or provider messages.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeApprovalService {

    /**
     * How long an INGESTING version holds its lease, measured from ingestionStartedAt on the same
     * Clock. Until it expires, replacement waits for the ingestion to finish or fail.
     */
    public static final Duration INGESTION_LEASE = Duration.ofMinutes(30);
    static final int MAX_TITLE_LENGTH = 200;

    private static final Set<ChunkAccessLevel> APPROVABLE_ACCESS_LEVELS =
            Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);

    static final String AUTHENTICATION_REQUIRED = "Authentication is required";
    static final String NOT_A_KNOWLEDGE_MANAGER = "Only HR or company administrators can manage the knowledge base";
    static final String DOCUMENT_ID_REQUIRED = "Document ID is required";
    static final String TITLE_REQUIRED = "Knowledge document title is required";
    static final String TITLE_TOO_LONG = "Knowledge document title must be at most " + MAX_TITLE_LENGTH + " characters";
    static final String ACCESS_LEVEL_NOT_ALLOWED = "Knowledge documents must be company-wide or HR-only";
    static final String DOCUMENT_NOT_FOUND = "Document not found";
    static final String KNOWLEDGE_DOCUMENT_NOT_FOUND = "Knowledge document not found";
    static final String NOT_COMPANY_POLICY = "Only company policy documents can be added to the knowledge base";
    static final String SOURCE_NOT_ACTIVE = "Only the current version of a stored document can be approved";
    static final String SOURCE_METADATA_INCOMPLETE = "The stored document's metadata is incomplete";
    static final String SOURCE_UNREADABLE = "The stored document's content is unavailable or does not match its upload";
    static final String SOURCE_ALREADY_IN_KNOWLEDGE_BASE = "This document is already in the knowledge base";
    static final String CONTENT_ALREADY_IN_KNOWLEDGE_BASE =
            "A document with identical content is already in the knowledge base or awaiting ingestion";
    static final String EXTRACTION_RESULT_INVALID = "The document's text extraction result is inconsistent";
    static final String KNOWLEDGE_DOCUMENT_REVOKED = "This knowledge document has been revoked";
    static final String INGESTION_IN_PROGRESS =
            "The pending version is being ingested; wait for ingestion to finish before replacing it";
    static final String INGESTION_STATE_UNRESOLVED =
            "The pending version's ingestion state is unresolved, so it cannot be replaced";
    static final String NOTHING_AWAITING_REVIEW = "No version of this document is awaiting review of incomplete text";
    static final String REVIEWED_VERSION_CHANGED =
            "The version awaiting review has changed; review the current version before accepting it";
    static final String CONTENT_INSPECTION_REQUIRED =
            "The pending version has not completed the required content inspection; approve a replacement document instead";
    static final String HISTORICAL_VERSION_NOT_FOUND =
            "The selected historical version is not available for this knowledge document";
    static final String HISTORICAL_VERSION_INVALID =
            "The selected historical version is not an eligible company policy";
    static final String HISTORICAL_VERSION_ALREADY_ACTIVE =
            "The selected historical version is already active";
    static final String HISTORICAL_VERSION_CONTENT_CHANGED =
            "The selected historical source no longer matches the approved version";
    static final String CONCURRENT_CHANGE =
            "This knowledge document was changed by another request; reload it and try again";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeApprovalService.class);

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeDocumentIndexGuard indexGuard;
    private final DocumentService documentService;
    private final DocumentTextExtractor textExtractor;
    private final Clock clock;

    /**
     * Creates a LIVE knowledge base entry whose pending version is the given COMPANY_POLICY stored
     * document. The version is APPROVED for ingestion only when local extraction found no review
     * requirement. Incomplete PDFs and suspicious extraction findings remain NEEDS_REVIEW.
     */
    public KnowledgeDocument approve(
            VerifiedMyHrActor actor, String storedDocumentId, String title, ChunkAccessLevel accessLevel) {
        requireKnowledgeManager(actor);
        requireId(storedDocumentId);
        String safeTitle = requireTitle(title);
        requireApprovableAccessLevel(accessLevel);
        indexGuard.requireUniqueLiveSourceIndex();

        LocalDateTime now = LocalDateTime.now(clock);
        KnowledgeDocumentVersion version = approveVersion(actor, storedDocumentId, accessLevel, now);
        KnowledgeDocument entry = KnowledgeDocument.builder()
                .organizationId(actor.organizationId())
                .title(safeTitle)
                .status(KnowledgeDocumentStatus.LIVE)
                .pendingVersion(version)
                .sourceDocumentIds(new ArrayList<>(List.of(storedDocumentId)))
                .createdByUserId(actor.userId())
                .createdAt(now)
                .build();

        KnowledgeDocument saved;
        try {
            saved = knowledgeDocumentRepository.insert(entry);
        } catch (DuplicateKeyException alreadyLive) {
            // The unique LIVE index caught a concurrent approval of the same document.
            throw new BusinessException(SOURCE_ALREADY_IN_KNOWLEDGE_BASE);
        }
        log.info("Knowledge document {} approved by {} (stage={}, accessLevel={})",
                saved.getId(), actor.role(), version.getStage(), accessLevel);
        return saved;
    }

    /**
     * Makes another COMPANY_POLICY stored document the entry's pending version. The active version
     * keeps serving until the new one is ingested and activated. A pending version that has not
     * started ingesting, failed, or whose ingestion lease expired is superseded; one still within
     * its lease, or INGESTING without a recorded start, blocks replacement. Chunks a superseded
     * version wrote never serve, because only the active version's ingestionId does.
     */
    public KnowledgeDocument approveReplacement(
            VerifiedMyHrActor actor, String knowledgeDocumentId, String storedDocumentId, ChunkAccessLevel accessLevel) {
        requireKnowledgeManager(actor);
        requireId(knowledgeDocumentId);
        requireId(storedDocumentId);
        requireApprovableAccessLevel(accessLevel);
        indexGuard.requireUniqueLiveSourceIndex();

        LocalDateTime now = LocalDateTime.now(clock);
        KnowledgeDocument entry = requireLiveEntry(actor, knowledgeDocumentId);
        requireReplaceablePendingVersion(entry.getPendingVersion(), now);

        KnowledgeDocumentVersion version = approveVersion(actor, storedDocumentId, accessLevel, now);
        List<String> sourceDocumentIds = new ArrayList<>();
        KnowledgeDocumentVersion active = entry.getActiveVersion();
        if (active != null && active.getSourceDocumentId() != null) {
            sourceDocumentIds.add(active.getSourceDocumentId());
        }
        sourceDocumentIds.add(storedDocumentId);
        entry.setPendingVersion(version);
        entry.setSourceDocumentIds(sourceDocumentIds);

        KnowledgeDocument saved = saveExisting(entry, "replacement");
        log.info("Knowledge document {} replacement approved by {} (stage={}, accessLevel={})",
                saved.getId(), actor.role(), version.getStage(), accessLevel);
        return saved;
    }

    /**
     * Re-approves one source this LIVE entry previously served. The historic ingestion id and chunks
     * are never reused: bytes, hash, extraction and G2 inspection are checked again before a new
     * pending version can be ingested and activated.
     */
    public KnowledgeDocument restoreHistoricalVersion(
            VerifiedMyHrActor actor, String knowledgeDocumentId, String expectedHistoricalSourceDocumentId) {
        requireKnowledgeManager(actor);
        requireId(knowledgeDocumentId);
        requireId(expectedHistoricalSourceDocumentId);
        indexGuard.requireUniqueLiveSourceIndex();

        LocalDateTime now = LocalDateTime.now(clock);
        KnowledgeDocument entry = requireLiveEntry(actor, knowledgeDocumentId);
        requireReplaceablePendingVersion(entry.getPendingVersion(), now);
        KnowledgeVersionHistoryEntry historical = entry.getVersionHistory().stream()
                .filter(candidate -> candidate != null && candidate.getVersion() != null)
                .filter(candidate -> expectedHistoricalSourceDocumentId.equals(candidate.getVersion().getSourceDocumentId()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(HISTORICAL_VERSION_NOT_FOUND));
        KnowledgeDocumentVersion historicalVersion = historical.getVersion();
        if (historicalVersion.getDocumentType() != DocumentType.COMPANY_POLICY
                || historicalVersion.getAccessLevel() == null
                || !APPROVABLE_ACCESS_LEVELS.contains(historicalVersion.getAccessLevel())
                || !isSha256(historicalVersion.getContentSha256())) {
            throw new BusinessException(HISTORICAL_VERSION_INVALID);
        }
        if (entry.getActiveVersion() != null
                && expectedHistoricalSourceDocumentId.equals(entry.getActiveVersion().getSourceDocumentId())) {
            throw new BusinessException(HISTORICAL_VERSION_ALREADY_ACTIVE);
        }

        KnowledgeDocumentVersion restored = approveVersion(actor, expectedHistoricalSourceDocumentId,
                historicalVersion.getAccessLevel(), now, historicalVersion.getContentSha256());
        restored.setRestoredFromSourceDocumentId(expectedHistoricalSourceDocumentId);
        restored.setRestoredFromActivatedAt(historicalVersion.getActivatedAt());
        List<String> sourceDocumentIds = new ArrayList<>();
        if (entry.getActiveVersion() != null && entry.getActiveVersion().getSourceDocumentId() != null) {
            sourceDocumentIds.add(entry.getActiveVersion().getSourceDocumentId());
        }
        sourceDocumentIds.add(expectedHistoricalSourceDocumentId);
        entry.setPendingVersion(restored);
        entry.setSourceDocumentIds(sourceDocumentIds);

        KnowledgeDocument saved = saveExisting(entry, "historical restoration");
        log.info("Knowledge document {} historical version approved for restoration by {} (stage={}, accessLevel={})",
                saved.getId(), actor.role(), restored.getStage(), restored.getAccessLevel());
        return saved;
    }

    /**
     * Records that HR reviewed a pending PDF version whose pages were not all extractable and
     * accepts it for ingestion as is. expectedSourceDocumentId is the stored document HR reviewed,
     * so a replacement made after the review is never accepted unseen.
     */
    public KnowledgeDocument acceptIncompleteExtraction(
            VerifiedMyHrActor actor, String knowledgeDocumentId, String expectedSourceDocumentId) {
        requireKnowledgeManager(actor);
        requireId(knowledgeDocumentId);
        requireId(expectedSourceDocumentId);

        KnowledgeDocument entry = requireLiveEntry(actor, knowledgeDocumentId);
        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        if (pending == null || pending.getStage() != KnowledgeIngestionStage.NEEDS_REVIEW) {
            throw new BusinessException(NOTHING_AWAITING_REVIEW);
        }
        if (!expectedSourceDocumentId.equals(pending.getSourceDocumentId())) {
            throw new BusinessException(REVIEWED_VERSION_CHANGED);
        }
        if (pending.getPagesWithoutText() == null || pending.getPagesWithoutText() <= 0) {
            throw new BusinessException(EXTRACTION_RESULT_INVALID);
        }
        if (!KnowledgeContentReviewMetadata.hasValidInspection(pending)) {
            throw new BusinessException(CONTENT_INSPECTION_REQUIRED);
        }

        pending.setIncompleteExtractionAcceptedByUserId(actor.userId());
        pending.setIncompleteExtractionAcceptedAt(LocalDateTime.now(clock));
        // This legacy endpoint only accepts missing PDF text. It cannot approve hidden or
        // suspicious content, which requires the Phase 1C bound review decision endpoint; once that
        // bound decision exists, accepting the missing text completes the approval.
        if (!Boolean.TRUE.equals(pending.getContentReviewRequired())
                || KnowledgeContentReviewMetadata.hasSatisfiedContentReview(pending)) {
            pending.setStage(KnowledgeIngestionStage.APPROVED);
        }

        KnowledgeDocument saved = saveExisting(entry, "incomplete-extraction acceptance");
        log.info("Knowledge document {} incomplete text accepted by {} ({} of {} pages without text)",
                saved.getId(), actor.role(), pending.getPagesWithoutText(), pending.getPageCount());
        return saved;
    }

    /**
     * Revokes the entry, then removes its chunks. Revocation alone stops every chunk from serving,
     * so it is saved first; if the save fails nothing is deleted. A failed cleanup leaves the entry
     * revoked and is retried by revoking again.
     */
    public RevocationResult revoke(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        requireKnowledgeManager(actor);
        requireId(knowledgeDocumentId);

        KnowledgeDocument entry = requireEntry(actor, knowledgeDocumentId);
        KnowledgeDocument revoked;
        if (entry.getStatus() == KnowledgeDocumentStatus.REVOKED) {
            revoked = entry;
        } else {
            entry.setStatus(KnowledgeDocumentStatus.REVOKED);
            entry.setRevokedByUserId(actor.userId());
            entry.setRevokedAt(LocalDateTime.now(clock));
            revoked = saveExisting(entry, "revocation");
            log.info("Knowledge document {} revoked by {}", revoked.getId(), actor.role());
        }

        try {
            long deleted = knowledgeChunkRepository
                    .deleteByOrganizationIdAndKnowledgeDocumentId(actor.organizationId(), knowledgeDocumentId);
            log.info("Removed {} chunks of revoked knowledge document {}", deleted, revoked.getId());
            return new RevocationResult(revoked, deleted, true);
        } catch (RuntimeException cleanupFailure) {
            // Class name only: driver messages can include connection details.
            log.warn("Knowledge document {} is revoked but its chunks could not be removed ({}); revoke again to retry",
                    revoked.getId(), cleanupFailure.getClass().getSimpleName());
            return new RevocationResult(revoked, 0, false);
        }
    }

    /**
     * The revoked entry and how chunk cleanup went. When chunkCleanupCompleted is false the entry
     * is still revoked, and so serves nothing, but chunks remain until revoke is called again.
     */
    public record RevocationResult(KnowledgeDocument document, long chunksDeleted, boolean chunkCleanupCompleted) {
    }

    // --- Approval of one stored document --------------------------------------------------------

    private KnowledgeDocumentVersion approveVersion(
            VerifiedMyHrActor actor, String storedDocumentId, ChunkAccessLevel accessLevel, LocalDateTime now) {
        return approveVersion(actor, storedDocumentId, accessLevel, now, null);
    }

    /**
     * expectedHistoricalContentSha256 is used only for restoration: a source id alone cannot make
     * changed bytes look like a prior approved policy.
     */
    private KnowledgeDocumentVersion approveVersion(
            VerifiedMyHrActor actor, String storedDocumentId, ChunkAccessLevel accessLevel, LocalDateTime now,
            String expectedHistoricalContentSha256) {
        String organizationId = actor.organizationId();
        if (knowledgeDocumentRepository.existsWithSourceDocument(
                organizationId, KnowledgeDocumentStatus.LIVE, storedDocumentId)) {
            throw new BusinessException(SOURCE_ALREADY_IN_KNOWLEDGE_BASE);
        }

        StoredDocument metadata = documentService.getMetadata(storedDocumentId);
        requireEligibleSource(metadata, storedDocumentId, organizationId);
        byte[] content = readStoredContent(metadata, storedDocumentId, organizationId);

        String contentSha256 = sha256(content);
        if (expectedHistoricalContentSha256 != null && !MessageDigest.isEqual(
                contentSha256.getBytes(StandardCharsets.US_ASCII),
                expectedHistoricalContentSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw new BusinessException(HISTORICAL_VERSION_CONTENT_CHANGED);
        }
        if (knowledgeDocumentRepository.existsByOrganizationIdAndStatusAndActiveVersionContentSha256(
                organizationId, KnowledgeDocumentStatus.LIVE, contentSha256)
                || knowledgeDocumentRepository.existsByOrganizationIdAndStatusAndPendingVersionContentSha256(
                organizationId, KnowledgeDocumentStatus.LIVE, contentSha256)) {
            throw new BusinessException(CONTENT_ALREADY_IN_KNOWLEDGE_BASE);
        }

        ExtractedText extracted = extract(content, metadata.getFileName());
        int pageCount = extracted.pageCount();
        int pagesWithoutText = extracted.pagesWithoutText();
        ContentInspection inspection = extracted.inspection();
        boolean contentReviewRequired = KnowledgeContentReviewMetadata.requiresContentReview(inspection);

        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(storedDocumentId)
                .sourceDocumentName(metadata.getFileName())
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(accessLevel)
                .contentSha256(contentSha256)
                .approvedByUserId(actor.userId())
                .approvedAt(now)
                .pageCount(pageCount)
                .pagesWithoutText(pagesWithoutText)
                .inspectionFormatVersion(ContentInspection.FORMAT_VERSION)
                .hiddenDocxRunsExcluded(inspection.hiddenDocxRunsExcluded())
                .suspiciousTextFragments(inspection.suspiciousTextFragments())
                .invisibleUnicodeCharacters(inspection.invisibleUnicodeCharacters())
                .unicodeTagCharactersRemoved(inspection.unicodeTagCharactersRemoved())
                .hiddenCommentBlocks(inspection.hiddenCommentBlocks())
                .instructionLikeContentMatches(inspection.instructionLikeContentMatches())
                .pagesRequiringOcrReview(inspection.pagesRequiringOcrReview())
                .contentReviewRequired(contentReviewRequired)
                .inspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection))
                .reviewablePassagesSha256(KnowledgeContentReviewMetadata.passagesFingerprint(
                        inspection.reviewablePassages(extracted.text())))
                .indexableTextSha256(KnowledgeContentReviewMetadata.textFingerprint(extracted.text()))
                .stage(pagesWithoutText > 0 || contentReviewRequired
                        ? KnowledgeIngestionStage.NEEDS_REVIEW : KnowledgeIngestionStage.APPROVED)
                .build();
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    /**
     * Another organization's document is reported as not found, so its existence is not revealed.
     * The size must be recorded, because getStoredContent only compares it with the stored bytes
     * when it is.
     */
    private static void requireEligibleSource(StoredDocument metadata, String storedDocumentId, String organizationId) {
        if (metadata == null
                || !storedDocumentId.equals(metadata.getId())
                || !organizationId.equals(metadata.getOrganizationId())) {
            throw new ResourceNotFoundException(DOCUMENT_NOT_FOUND);
        }
        if (metadata.getDocumentType() != DocumentType.COMPANY_POLICY) {
            throw new BusinessException(NOT_COMPANY_POLICY);
        }
        if (metadata.getStatus() != DocumentStatus.ACTIVE) {
            throw new BusinessException(SOURCE_NOT_ACTIVE);
        }
        if (metadata.getStorageProvider() != StorageProvider.R2
                || isBlank(metadata.getStorageKey())
                || isBlank(metadata.getFileName())
                || metadata.getFileSize() == null
                || metadata.getFileSize() <= 0) {
            throw new BusinessException(SOURCE_METADATA_INCOMPLETE);
        }
    }

    /**
     * The uploaded bytes, re-checked against the metadata read just before: getStoredContent loads
     * the metadata again, and it must still describe the same eligible upload.
     */
    private byte[] readStoredContent(StoredDocument checked, String storedDocumentId, String organizationId) {
        DocumentService.DocumentContent stored;
        try {
            stored = documentService.getStoredContent(storedDocumentId);
        } catch (ResourceNotFoundException | BusinessException safeMessage) {
            throw safeMessage;
        } catch (RuntimeException storageFailure) {
            // Storage messages name the storage key, which contains the file name; the cause is dropped.
            log.warn("Stored document content could not be read for a knowledge approval ({})",
                    storageFailure.getClass().getSimpleName());
            throw new BusinessException(SOURCE_UNREADABLE);
        }
        if (stored == null || stored.content() == null || stored.content().length == 0) {
            throw new BusinessException(SOURCE_UNREADABLE);
        }
        StoredDocument current = stored.metadata();
        requireEligibleSource(current, storedDocumentId, organizationId);
        if (!Objects.equals(checked.getStorageKey(), current.getStorageKey())
                || !Objects.equals(checked.getFileName(), current.getFileName())
                || !Objects.equals(checked.getFileSize(), current.getFileSize())
                || stored.content().length != current.getFileSize()) {
            throw new BusinessException(SOURCE_UNREADABLE);
        }
        return stored.content();
    }

    /** Runs local extraction only to validate the document; the text is not kept. */
    private ExtractedText extract(byte[] content, String fileName) {
        ExtractedText extracted;
        try {
            extracted = textExtractor.extract(content, fileName);
        } catch (DocumentExtractionException failure) {
            log.info("Knowledge approval rejected: text extraction failed ({})", failure.getReason());
            // Messages are fixed per reason and never quote the document.
            throw new BusinessException(failure.getMessage());
        }
        if (extracted == null
                || extracted.text() == null
                || extracted.text().isBlank()
                || extracted.inspection() == null
                || extracted.pageCount() < 0
                || extracted.pagesWithoutText() < 0
                || extracted.pagesWithoutText() > extracted.pageCount()
                || (extracted.format() != DocumentTextExtractor.DocumentFormat.PDF && extracted.pagesWithoutText() != 0)
                || extracted.inspection().hiddenDocxRunsExcluded() < 0
                || extracted.inspection().suspiciousTextFragments() < 0
                || extracted.inspection().invisibleUnicodeCharacters() < 0
                || extracted.inspection().unicodeTagCharactersRemoved() < 0
                || extracted.inspection().hiddenCommentBlocks() < 0
                || extracted.inspection().instructionLikeContentMatches() < 0
                || extracted.inspection().pagesRequiringOcrReview() < 0
                || extracted.inspection().pagesRequiringOcrReview() != extracted.pagesWithoutText()) {
            throw new BusinessException(EXTRACTION_RESULT_INVALID);
        }
        return extracted;
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }

    // --- Entries --------------------------------------------------------------------------------

    /** Looked up within the actor's organization only, and re-checked in case the query ever widens. */
    private KnowledgeDocument requireEntry(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        return knowledgeDocumentRepository
                .findByIdAndOrganizationId(knowledgeDocumentId, actor.organizationId())
                .filter(found -> actor.organizationId().equals(found.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(KNOWLEDGE_DOCUMENT_NOT_FOUND));
    }

    private KnowledgeDocument requireLiveEntry(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        KnowledgeDocument entry = requireEntry(actor, knowledgeDocumentId);
        if (entry.getStatus() != KnowledgeDocumentStatus.LIVE) {
            throw new BusinessException(KNOWLEDGE_DOCUMENT_REVOKED);
        }
        return entry;
    }

    /**
     * An INGESTING version may be writing chunks, so it is superseded only once its lease has
     * expired. Without a recorded start, or a stage at all, there is no way to tell whether an
     * ingestion is still running, so that state is rejected rather than treated as expired.
     */
    private static void requireReplaceablePendingVersion(KnowledgeDocumentVersion pending, LocalDateTime now) {
        if (pending == null) {
            return;
        }
        if (pending.getStage() == null) {
            throw new BusinessException(INGESTION_STATE_UNRESOLVED);
        }
        if (pending.getStage() != KnowledgeIngestionStage.INGESTING) {
            return;
        }
        LocalDateTime startedAt = pending.getIngestionStartedAt();
        if (startedAt == null) {
            throw new BusinessException(INGESTION_STATE_UNRESOLVED);
        }
        if (now.isBefore(startedAt.plus(INGESTION_LEASE))) {
            throw new BusinessException(INGESTION_IN_PROGRESS);
        }
    }

    /** Saves under the entry's @Version, so a concurrent change fails this save instead of being lost. */
    private KnowledgeDocument saveExisting(KnowledgeDocument entry, String operation) {
        try {
            return knowledgeDocumentRepository.save(entry);
        } catch (OptimisticLockingFailureException concurrentChange) {
            log.info("Knowledge document {} changed during {}; nothing was saved", entry.getId(), operation);
            throw new BusinessException(CONCURRENT_CHANGE);
        } catch (DuplicateKeyException alreadyLive) {
            throw new BusinessException(SOURCE_ALREADY_IN_KNOWLEDGE_BASE);
        }
    }

    // --- Input checks ---------------------------------------------------------------------------

    private static void requireKnowledgeManager(VerifiedMyHrActor actor) {
        if (actor == null) {
            throw new UnauthorizedException(AUTHENTICATION_REQUIRED);
        }
        if (!actor.canManageKnowledge()) {
            throw new ForbiddenException(NOT_A_KNOWLEDGE_MANAGER);
        }
    }

    private static void requireId(String id) {
        if (isBlank(id)) {
            throw new BusinessException(DOCUMENT_ID_REQUIRED);
        }
    }

    private static String requireTitle(String title) {
        if (isBlank(title)) {
            throw new BusinessException(TITLE_REQUIRED);
        }
        String stripped = title.strip();
        if (stripped.length() > MAX_TITLE_LENGTH) {
            throw new BusinessException(TITLE_TOO_LONG);
        }
        return stripped;
    }

    private static void requireApprovableAccessLevel(ChunkAccessLevel accessLevel) {
        if (accessLevel == null || !APPROVABLE_ACCESS_LEVELS.contains(accessLevel)) {
            throw new BusinessException(ACCESS_LEVEL_NOT_ALLOWED);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
