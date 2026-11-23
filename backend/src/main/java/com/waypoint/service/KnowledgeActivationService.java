package com.waypoint.service;

import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.entity.KnowledgeVersionHistoryEntry;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.setup.KnowledgeChunkSearchReadinessProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Promotes one already-indexed, approved policy version to the active version.
 *
 * <p>The feature is disabled unless explicitly enabled by configuration. When enabled, activation
 * verifies the source bytes, chunk set, and sampled Atlas-search readiness before one
 * {@code @Version}-checked document save switches the serving ingestion. Chunk cleanup is strictly
 * post-confirmation and remains ordinary cleanup; revocation continues to own full deletion.
 */
@Service
public class KnowledgeActivationService {

    static final String ACTIVATION_DISABLED = "Knowledge activation is not enabled";
    static final String AUTHENTICATION_REQUIRED = "Authentication is required";
    static final String NOT_A_KNOWLEDGE_MANAGER = "Only HR or company administrators can manage the knowledge base";
    static final String DOCUMENT_ID_REQUIRED = "Knowledge document ID is required";
    static final String INGESTION_ID_REQUIRED = "Expected ingestion ID is required";
    static final String KNOWLEDGE_DOCUMENT_NOT_FOUND = "Knowledge document not found";
    static final String KNOWLEDGE_DOCUMENT_REVOKED = "This knowledge document has been revoked";
    static final String PENDING_VERSION_INVALID = "The pending version is not eligible for activation";
    static final String EMBEDDING_CONFIGURATION_INVALID = "The configured embedding model cannot activate this version";
    static final String SOURCE_NOT_VERIFIED = "The approved source document could not be verified for activation";
    static final String CHUNK_INTEGRITY_NOT_VERIFIED = "The indexed chunks could not be verified for activation";
    static final String VERSION_HISTORY_FULL = "This knowledge document has reached its version history limit";
    static final String ACTIVATION_CONFLICT =
            "This knowledge document changed during activation; reload it and try again";
    static final String ACTIVATION_UNCONFIRMED = "Knowledge activation could not be confirmed; try again later";

    private static final int REQUIRED_DIMENSIONS = KnowledgeChunkIntegrityCheck.REQUIRED_EMBEDDING_DIMENSIONS;
    private static final int MAX_CHUNKS = KnowledgeChunkIntegrityCheck.MAX_SUPPORTED_CHUNKS;
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Set<ChunkAccessLevel> ACTIVATABLE_ACCESS_LEVELS =
            Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
    private static final Logger log = LoggerFactory.getLogger(KnowledgeActivationService.class);

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeDocumentIndexGuard indexGuard;
    private final KnowledgeSourceDocumentReader sourceDocumentReader;
    private final KnowledgeChunkIntegrityCheck integrityCheck;
    private final KnowledgeChunkSearchReadinessProbe searchReadinessProbe;
    private final KnowledgeChunkCleaner knowledgeChunkCleaner;
    private final EmbeddingService embeddingService;
    private final Clock clock;
    private final boolean activationEnabled;

    public KnowledgeActivationService(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeDocumentIndexGuard indexGuard,
            KnowledgeSourceDocumentReader sourceDocumentReader,
            KnowledgeChunkIntegrityCheck integrityCheck,
            KnowledgeChunkSearchReadinessProbe searchReadinessProbe,
            KnowledgeChunkCleaner knowledgeChunkCleaner,
            EmbeddingService embeddingService,
            Clock clock,
            @Value("${waypoint.myhr.knowledge.activation.enabled:false}") boolean activationEnabled) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.indexGuard = indexGuard;
        this.sourceDocumentReader = sourceDocumentReader;
        this.integrityCheck = integrityCheck;
        this.searchReadinessProbe = searchReadinessProbe;
        this.knowledgeChunkCleaner = knowledgeChunkCleaner;
        this.embeddingService = embeddingService;
        this.clock = clock;
        this.activationEnabled = activationEnabled;
    }

    /**
     * Activates only the pending ingestion explicitly named by the caller. A readiness delay is a
     * normal, non-mutating outcome; all other rejected preconditions throw fixed safe messages.
     */
    public ActivationResult activate(VerifiedMyHrActor actor, String knowledgeDocumentId, String expectedIngestionId) {
        requireKnowledgeManager(actor);
        requireText(knowledgeDocumentId, DOCUMENT_ID_REQUIRED);
        requireText(expectedIngestionId, INGESTION_ID_REQUIRED);
        if (!activationEnabled) {
            throw new BusinessException(ACTIVATION_DISABLED);
        }

        // This is deliberately before any document lookup. An unavailable uniqueness guarantee
        // blocks activation just as it blocks approval and replacement.
        indexGuard.requireUniqueLiveSourceIndex();

        String organizationId = actor.organizationId();
        KnowledgeDocument initial = requireEntry(organizationId, knowledgeDocumentId);
        if (isConfirmedActive(initial, expectedIngestionId)) {
            return cleanupAfterConfirmedActivation(ActivationOutcome.ALREADY_ACTIVE, organizationId, knowledgeDocumentId);
        }
        VersionFingerprint expected = requireActivatable(initial, expectedIngestionId);
        requireConfiguredModel(expected.embeddingModel());

        reverifySource(
                organizationId,
                expected.sourceDocumentId(),
                expected.sourceDocumentName(),
                expected.contentSha256());
        requireVerifiedIntegrity(organizationId, knowledgeDocumentId, expectedIngestionId, expected);
        if (!isSearchReady(organizationId, knowledgeDocumentId, expectedIngestionId, expected)) {
            return new ActivationResult(ActivationOutcome.AWAITING_SEARCH_INDEX, CleanupStatus.NOT_ATTEMPTED);
        }

        // Re-read after all external/read-only checks. The fingerprint prevents activating an
        // altered pending version whose ingestion id happened to remain the same.
        KnowledgeDocument current = requireEntry(organizationId, knowledgeDocumentId);
        if (isConfirmedActive(current, expectedIngestionId)) {
            return cleanupAfterConfirmedActivation(ActivationOutcome.ALREADY_ACTIVE, organizationId, knowledgeDocumentId);
        }
        VersionFingerprint currentFingerprint = requireActivatable(current, expectedIngestionId);
        if (!expected.equals(currentFingerprint)) {
            throw new BusinessException(ACTIVATION_CONFLICT);
        }
        requireConfiguredModel(currentFingerprint.embeddingModel());

        KnowledgeDocument candidate = activatedCopy(current, actor, LocalDateTime.now(clock));
        try {
            KnowledgeDocument saved = knowledgeDocumentRepository.save(candidate);
            if (isConfirmedActive(saved, expectedIngestionId, expected)) {
                log.info("Knowledge document {} activated by {}", knowledgeDocumentId, actor.role());
                return cleanupAfterConfirmedActivation(ActivationOutcome.ACTIVATED, organizationId, knowledgeDocumentId);
            }
        } catch (OptimisticLockingFailureException conflict) {
            log.info("Knowledge document {} changed during activation", knowledgeDocumentId);
        } catch (RuntimeException uncertain) {
            // Never include a driver/provider message: it can contain connection details.
            log.warn("Knowledge document {} activation save was not confirmed ({})", knowledgeDocumentId,
                    uncertain.getClass().getSimpleName());
        }
        return reconcileUnconfirmedSave(organizationId, knowledgeDocumentId, expectedIngestionId, expected);
    }

    private ActivationResult reconcileUnconfirmedSave(
            String organizationId, String knowledgeDocumentId, String expectedIngestionId, VersionFingerprint expected) {
        Optional<KnowledgeDocument> reloaded;
        try {
            reloaded = knowledgeDocumentRepository.findByIdAndOrganizationId(knowledgeDocumentId, organizationId)
                    .filter(found -> organizationId.equals(found.getOrganizationId()));
        } catch (RuntimeException unreadable) {
            log.warn("Knowledge document {} activation confirmation could not be read ({})", knowledgeDocumentId,
                    unreadable.getClass().getSimpleName());
            throw new BusinessException(ACTIVATION_UNCONFIRMED);
        }
        if (reloaded.isEmpty()) {
            throw new BusinessException(ACTIVATION_UNCONFIRMED);
        }
        KnowledgeDocument current = reloaded.get();
        if (isConfirmedActive(current, expectedIngestionId, expected)) {
            return cleanupAfterConfirmedActivation(ActivationOutcome.ALREADY_ACTIVE, organizationId, knowledgeDocumentId);
        }
        if (current.getStatus() != KnowledgeDocumentStatus.LIVE) {
            throw new BusinessException(KNOWLEDGE_DOCUMENT_REVOKED);
        }
        throw new BusinessException(ACTIVATION_CONFLICT);
    }

    private void reverifySource(
            String organizationId, String sourceDocumentId, String sourceDocumentName, String contentSha256) {
        try {
            // The reader proves organization, type, status, storage metadata, bytes, and SHA-256.
            // Retain its result only long enough to bind the approved filename to those same bytes.
            KnowledgeSourceDocumentReader.VerifiedSourceContent verified =
                    sourceDocumentReader.read(organizationId, sourceDocumentId, contentSha256);
            if (verified == null
                    || verified.metadata() == null
                    || verified.content() == null
                    || verified.content().length == 0
                    || !sourceDocumentId.equals(verified.metadata().getId())
                    || !sourceDocumentName.equals(verified.metadata().getFileName())
                    || !contentSha256.equals(verified.contentSha256())) {
                throw new IllegalStateException("Verified source result was incomplete");
            }
        } catch (RuntimeException rejected) {
            log.info("Knowledge source could not be reverified for activation ({})",
                    rejected.getClass().getSimpleName());
            throw new BusinessException(SOURCE_NOT_VERIFIED);
        }
    }

    private void requireVerifiedIntegrity(
            String organizationId, String knowledgeDocumentId, String ingestionId, VersionFingerprint expected) {
        KnowledgeChunkIntegrityCheck.IntegrityCheckResult result;
        try {
            result = integrityCheck.check(new KnowledgeChunkIntegrityCheck.Expectation(
                    organizationId, knowledgeDocumentId, ingestionId, expected.chunkCount(), expected.sourceDocumentId(),
                    expected.documentType(), expected.accessLevel(), expected.embeddingModel()));
        } catch (RuntimeException unavailable) {
            log.warn("Knowledge chunk integrity check was unavailable ({})", unavailable.getClass().getSimpleName());
            throw new BusinessException(CHUNK_INTEGRITY_NOT_VERIFIED);
        }
        if (result == null || !result.verified()) {
            throw new BusinessException(CHUNK_INTEGRITY_NOT_VERIFIED);
        }
    }

    private boolean isSearchReady(
            String organizationId, String knowledgeDocumentId, String ingestionId, VersionFingerprint expected) {
        try {
            KnowledgeChunkSearchReadinessProbe.SearchReadinessReport report = searchReadinessProbe.probe(
                    new KnowledgeChunkSearchReadinessProbe.ProbeRequest(
                            organizationId, knowledgeDocumentId, ingestionId, expected.chunkCount(), expected.accessLevel()));
            return report != null && report.ready();
        } catch (RuntimeException unavailable) {
            // Search availability is deliberately an awaiting outcome, never an activation bypass.
            log.info("Knowledge search readiness is awaiting confirmation ({})", unavailable.getClass().getSimpleName());
            return false;
        }
    }

    private VersionFingerprint requireActivatable(KnowledgeDocument entry, String expectedIngestionId) {
        if (entry.getStatus() != KnowledgeDocumentStatus.LIVE) {
            throw new BusinessException(KNOWLEDGE_DOCUMENT_REVOKED);
        }
        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        if (pending == null || pending.getStage() != KnowledgeIngestionStage.INDEXED
                || !expectedIngestionId.equals(pending.getIngestionId())) {
            throw new BusinessException(PENDING_VERSION_INVALID);
        }
        VersionFingerprint fingerprint = VersionFingerprint.of(pending);
        if (!fingerprint.isValid() || !KnowledgeContentReviewMetadata.hasValidInspection(pending)
                || (Boolean.TRUE.equals(pending.getContentReviewRequired())
                && !KnowledgeContentReviewMetadata.hasSatisfiedContentReview(pending))) {
            throw new BusinessException(PENDING_VERSION_INVALID);
        }
        List<String> sourceDocumentIds = entry.getSourceDocumentIds();
        if (sourceDocumentIds == null || !sourceDocumentIds.contains(pending.getSourceDocumentId())) {
            throw new BusinessException(PENDING_VERSION_INVALID);
        }
        KnowledgeDocumentVersion active = entry.getActiveVersion();
        if (active != null) {
            if (!isValidExistingActive(active) || !sourceDocumentIds.contains(active.getSourceDocumentId())
                    || expectedIngestionId.equals(active.getIngestionId())) {
                throw new BusinessException(PENDING_VERSION_INVALID);
            }
            if (!entry.hasVersionHistoryCapacity()) {
                throw new BusinessException(VERSION_HISTORY_FULL);
            }
        }
        return fingerprint;
    }

    private void requireConfiguredModel(String pendingModel) {
        try {
            String configuredModel = embeddingService.model();
            if (isBlank(configuredModel) || !configuredModel.equals(pendingModel)
                    || embeddingService.dimensions() != REQUIRED_DIMENSIONS) {
                throw new BusinessException(EMBEDDING_CONFIGURATION_INVALID);
            }
        } catch (BusinessException rejected) {
            throw rejected;
        } catch (RuntimeException unavailable) {
            log.warn("Knowledge embedding configuration could not be read ({})", unavailable.getClass().getSimpleName());
            throw new BusinessException(EMBEDDING_CONFIGURATION_INVALID);
        }
    }

    private KnowledgeDocument activatedCopy(KnowledgeDocument current, VerifiedMyHrActor actor, LocalDateTime activatedAt) {
        KnowledgeDocumentVersion active = copyVersion(current.getPendingVersion());
        active.setActivatedAt(activatedAt);
        active.setActivatedByUserId(actor.userId());

        List<KnowledgeVersionHistoryEntry> history = new ArrayList<>(current.getVersionHistory());
        if (current.getActiveVersion() != null) {
            history.add(KnowledgeVersionHistoryEntry.builder()
                    .version(copyVersion(current.getActiveVersion()))
                    .supersededAt(activatedAt)
                    .supersededByIngestionId(active.getIngestionId())
                    .supersededByUserId(actor.userId())
                    .build());
        }

        return KnowledgeDocument.builder()
                .id(current.getId())
                .organizationId(current.getOrganizationId())
                .title(current.getTitle())
                .status(current.getStatus())
                .activeVersion(active)
                .pendingVersion(null)
                .versionHistory(history)
                .sourceDocumentIds(new ArrayList<>(List.of(active.getSourceDocumentId())))
                .createdByUserId(current.getCreatedByUserId())
                .createdAt(current.getCreatedAt())
                .revokedByUserId(current.getRevokedByUserId())
                .revokedAt(current.getRevokedAt())
                .version(current.getVersion())
                .build();
    }

    private ActivationResult cleanupAfterConfirmedActivation(
            ActivationOutcome outcome, String organizationId, String knowledgeDocumentId) {
        try {
            KnowledgeChunkCleaner.CleanupResult cleanup =
                    knowledgeChunkCleaner.cleanObsoleteChunks(organizationId, knowledgeDocumentId);
            CleanupStatus status = cleanup != null && cleanup.discoveryCompleted()
                    && cleanup.failedIngestionIds().isEmpty()
                    ? CleanupStatus.COMPLETED : CleanupStatus.INCOMPLETE;
            return new ActivationResult(outcome, status);
        } catch (RuntimeException cleanupFailure) {
            log.warn("Knowledge document {} activated but ordinary cleanup was incomplete ({})", knowledgeDocumentId,
                    cleanupFailure.getClass().getSimpleName());
            return new ActivationResult(outcome, CleanupStatus.INCOMPLETE);
        }
    }

    private KnowledgeDocument requireEntry(String organizationId, String knowledgeDocumentId) {
        return knowledgeDocumentRepository.findByIdAndOrganizationId(knowledgeDocumentId, organizationId)
                .filter(found -> organizationId.equals(found.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(KNOWLEDGE_DOCUMENT_NOT_FOUND));
    }

    private static boolean isConfirmedActive(KnowledgeDocument entry, String expectedIngestionId) {
        if (entry == null || entry.getStatus() != KnowledgeDocumentStatus.LIVE || entry.getPendingVersion() != null) {
            return false;
        }
        KnowledgeDocumentVersion active = entry.getActiveVersion();
        List<String> sourceDocumentIds = entry.getSourceDocumentIds();
        return active != null
                && expectedIngestionId.equals(active.getIngestionId())
                && isValidActivatedVersion(active)
                && sourceDocumentIds != null
                && sourceDocumentIds.size() == 1
                && Objects.equals(active.getSourceDocumentId(), sourceDocumentIds.getFirst());
    }

    /** An uncertain save is successful only when every indexed input still matches the checked version. */
    private static boolean isConfirmedActive(
            KnowledgeDocument entry, String expectedIngestionId, VersionFingerprint expected) {
        return isConfirmedActive(entry, expectedIngestionId)
                && expected != null
                && expected.matchesActivated(entry.getActiveVersion());
    }

    private static boolean isValidActivatedVersion(KnowledgeDocumentVersion active) {
        return active.getStage() == KnowledgeIngestionStage.INDEXED
                && active.getDocumentType() == DocumentType.COMPANY_POLICY
                && ACTIVATABLE_ACCESS_LEVELS.contains(active.getAccessLevel())
                && !isBlank(active.getSourceDocumentId())
                && !isBlank(active.getSourceDocumentName())
                && active.getContentSha256() != null && SHA256_HEX.matcher(active.getContentSha256()).matches()
                && !isBlank(active.getApprovedByUserId()) && active.getApprovedAt() != null
                && active.getIngestionStartedAt() != null
                && active.getChunkCount() != null && active.getChunkCount() >= 1 && active.getChunkCount() <= MAX_CHUNKS
                && !isBlank(active.getEmbeddingModel())
                && active.getEmbeddingDimensions() != null && active.getEmbeddingDimensions() == REQUIRED_DIMENSIONS
                && active.getIndexedAt() != null && !active.getIndexedAt().isBefore(active.getIngestionStartedAt())
                && active.getActivatedAt() != null && !isBlank(active.getActivatedByUserId())
                && active.getFailureReason() == null && active.getExtractionFailureReason() == null && active.getFailedAt() == null
                && active.getPageCount() != null && active.getPagesWithoutText() != null
                && active.getPageCount() >= 0 && active.getPagesWithoutText() >= 0
                && active.getPagesWithoutText() <= active.getPageCount()
                && (active.getPagesWithoutText() == 0
                ? isBlank(active.getIncompleteExtractionAcceptedByUserId())
                && active.getIncompleteExtractionAcceptedAt() == null
                : !isBlank(active.getIncompleteExtractionAcceptedByUserId())
                && active.getIncompleteExtractionAcceptedAt() != null);
    }

    private static boolean isValidExistingActive(KnowledgeDocumentVersion active) {
        return active.getStage() == KnowledgeIngestionStage.INDEXED
                && active.getDocumentType() == DocumentType.COMPANY_POLICY
                && ACTIVATABLE_ACCESS_LEVELS.contains(active.getAccessLevel())
                && !isBlank(active.getSourceDocumentId())
                && !isBlank(active.getIngestionId());
    }

    private static KnowledgeDocumentVersion copyVersion(KnowledgeDocumentVersion source) {
        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(source.getSourceDocumentId())
                .sourceDocumentName(source.getSourceDocumentName())
                .documentType(source.getDocumentType())
                .accessLevel(source.getAccessLevel())
                .contentSha256(source.getContentSha256())
                .approvedByUserId(source.getApprovedByUserId())
                .approvedAt(source.getApprovedAt())
                .pageCount(source.getPageCount())
                .pagesWithoutText(source.getPagesWithoutText())
                .incompleteExtractionAcceptedByUserId(source.getIncompleteExtractionAcceptedByUserId())
                .incompleteExtractionAcceptedAt(source.getIncompleteExtractionAcceptedAt())
                .inspectionFormatVersion(source.getInspectionFormatVersion())
                .hiddenDocxRunsExcluded(source.getHiddenDocxRunsExcluded())
                .suspiciousTextFragments(source.getSuspiciousTextFragments())
                .invisibleUnicodeCharacters(source.getInvisibleUnicodeCharacters())
                .unicodeTagCharactersRemoved(source.getUnicodeTagCharactersRemoved())
                .hiddenCommentBlocks(source.getHiddenCommentBlocks())
                .instructionLikeContentMatches(source.getInstructionLikeContentMatches())
                .pagesRequiringOcrReview(source.getPagesRequiringOcrReview())
                .contentReviewRequired(source.getContentReviewRequired())
                .inspectionFindingsSha256(source.getInspectionFindingsSha256())
                .reviewablePassagesSha256(source.getReviewablePassagesSha256())
                .indexableTextSha256(source.getIndexableTextSha256())
                .contentReviewDecision(source.getContentReviewDecision())
                .contentReviewedByUserId(source.getContentReviewedByUserId())
                .contentReviewedAt(source.getContentReviewedAt())
                .reviewedSourceDocumentId(source.getReviewedSourceDocumentId())
                .reviewedFindingsSha256(source.getReviewedFindingsSha256())
                .reviewedIndexableTextSha256(source.getReviewedIndexableTextSha256())
                .stage(source.getStage())
                .ingestionId(source.getIngestionId())
                .ingestionStartedAt(source.getIngestionStartedAt())
                .chunkCount(source.getChunkCount())
                .embeddingModel(source.getEmbeddingModel())
                .embeddingDimensions(source.getEmbeddingDimensions())
                .indexedAt(source.getIndexedAt())
                .activatedAt(source.getActivatedAt())
                .activatedByUserId(source.getActivatedByUserId())
                .failureReason(source.getFailureReason())
                .extractionFailureReason(source.getExtractionFailureReason())
                .failedAt(source.getFailedAt())
                .build();
    }

    private static void requireKnowledgeManager(VerifiedMyHrActor actor) {
        if (actor == null) {
            throw new UnauthorizedException(AUTHENTICATION_REQUIRED);
        }
        if (!actor.canManageKnowledge()) {
            throw new ForbiddenException(NOT_A_KNOWLEDGE_MANAGER);
        }
    }

    private static void requireText(String value, String message) {
        if (isBlank(value)) {
            throw new BusinessException(message);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record VersionFingerprint(
            String sourceDocumentId,
            String sourceDocumentName,
            DocumentType documentType,
            ChunkAccessLevel accessLevel,
            String contentSha256,
            String approvedByUserId,
            LocalDateTime approvedAt,
            Integer pageCount,
            Integer pagesWithoutText,
            String incompleteExtractionAcceptedByUserId,
            LocalDateTime incompleteExtractionAcceptedAt,
            Integer inspectionFormatVersion,
            String inspectionFindingsSha256,
            String reviewablePassagesSha256,
            String indexableTextSha256,
            Boolean contentReviewRequired,
            Object contentReviewDecision,
            String contentReviewedByUserId,
            LocalDateTime contentReviewedAt,
            String reviewedSourceDocumentId,
            String reviewedFindingsSha256,
            String reviewedIndexableTextSha256,
            KnowledgeIngestionStage stage,
            String ingestionId,
            LocalDateTime ingestionStartedAt,
            Integer chunkCount,
            String embeddingModel,
            Integer embeddingDimensions,
            LocalDateTime indexedAt,
            LocalDateTime activatedAt,
            String activatedByUserId,
            Object failureReason,
            Object extractionFailureReason,
            LocalDateTime failedAt) {

        static VersionFingerprint of(KnowledgeDocumentVersion version) {
            return new VersionFingerprint(
                    version.getSourceDocumentId(), version.getSourceDocumentName(), version.getDocumentType(),
                    version.getAccessLevel(), version.getContentSha256(), version.getApprovedByUserId(),
                    version.getApprovedAt(), version.getPageCount(), version.getPagesWithoutText(),
                    version.getIncompleteExtractionAcceptedByUserId(), version.getIncompleteExtractionAcceptedAt(),
                    version.getInspectionFormatVersion(), version.getInspectionFindingsSha256(),
                    version.getReviewablePassagesSha256(), version.getIndexableTextSha256(),
                    version.getContentReviewRequired(), version.getContentReviewDecision(),
                    version.getContentReviewedByUserId(), version.getContentReviewedAt(), version.getReviewedSourceDocumentId(),
                    version.getReviewedFindingsSha256(), version.getReviewedIndexableTextSha256(),
                    version.getStage(), version.getIngestionId(), version.getIngestionStartedAt(), version.getChunkCount(),
                    version.getEmbeddingModel(), version.getEmbeddingDimensions(), version.getIndexedAt(),
                    version.getActivatedAt(), version.getActivatedByUserId(), version.getFailureReason(),
                    version.getExtractionFailureReason(), version.getFailedAt());
        }

        boolean isValid() {
            if (documentType != DocumentType.COMPANY_POLICY
                    || !ACTIVATABLE_ACCESS_LEVELS.contains(accessLevel)
                    || isBlank(sourceDocumentId)
                    || isBlank(sourceDocumentName)
                    || contentSha256 == null || !SHA256_HEX.matcher(contentSha256).matches()
                    || isBlank(approvedByUserId) || approvedAt == null
                    || stage != KnowledgeIngestionStage.INDEXED || isBlank(ingestionId)
                    || ingestionStartedAt == null || chunkCount == null || chunkCount < 1 || chunkCount > MAX_CHUNKS
                    || isBlank(embeddingModel) || embeddingDimensions == null
                    || embeddingDimensions != REQUIRED_DIMENSIONS || indexedAt == null
                    || indexedAt.isBefore(ingestionStartedAt)
                    || activatedAt != null || !isBlank(activatedByUserId)
                    || failureReason != null || extractionFailureReason != null || failedAt != null
                    || pageCount == null || pagesWithoutText == null || pageCount < 0 || pagesWithoutText < 0
                    || pagesWithoutText > pageCount) {
                return false;
            }
            if (pagesWithoutText > 0) {
                return !isBlank(incompleteExtractionAcceptedByUserId) && incompleteExtractionAcceptedAt != null;
            }
            return isBlank(incompleteExtractionAcceptedByUserId) && incompleteExtractionAcceptedAt == null;
        }

        boolean matchesActivated(KnowledgeDocumentVersion active) {
            return active != null
                    && Objects.equals(sourceDocumentId, active.getSourceDocumentId())
                    && Objects.equals(sourceDocumentName, active.getSourceDocumentName())
                    && documentType == active.getDocumentType()
                    && accessLevel == active.getAccessLevel()
                    && Objects.equals(contentSha256, active.getContentSha256())
                    && Objects.equals(approvedByUserId, active.getApprovedByUserId())
                    && Objects.equals(approvedAt, active.getApprovedAt())
                    && Objects.equals(pageCount, active.getPageCount())
                    && Objects.equals(pagesWithoutText, active.getPagesWithoutText())
                    && Objects.equals(incompleteExtractionAcceptedByUserId,
                    active.getIncompleteExtractionAcceptedByUserId())
                    && Objects.equals(incompleteExtractionAcceptedAt, active.getIncompleteExtractionAcceptedAt())
                    && Objects.equals(inspectionFormatVersion, active.getInspectionFormatVersion())
                    && Objects.equals(inspectionFindingsSha256, active.getInspectionFindingsSha256())
                    && Objects.equals(reviewablePassagesSha256, active.getReviewablePassagesSha256())
                    && Objects.equals(indexableTextSha256, active.getIndexableTextSha256())
                    && Objects.equals(contentReviewRequired, active.getContentReviewRequired())
                    && Objects.equals(contentReviewDecision, active.getContentReviewDecision())
                    && Objects.equals(contentReviewedByUserId, active.getContentReviewedByUserId())
                    && Objects.equals(contentReviewedAt, active.getContentReviewedAt())
                    && Objects.equals(reviewedSourceDocumentId, active.getReviewedSourceDocumentId())
                    && Objects.equals(reviewedFindingsSha256, active.getReviewedFindingsSha256())
                    && Objects.equals(reviewedIndexableTextSha256, active.getReviewedIndexableTextSha256())
                    && stage == active.getStage()
                    && Objects.equals(ingestionId, active.getIngestionId())
                    && Objects.equals(ingestionStartedAt, active.getIngestionStartedAt())
                    && Objects.equals(chunkCount, active.getChunkCount())
                    && Objects.equals(embeddingModel, active.getEmbeddingModel())
                    && Objects.equals(embeddingDimensions, active.getEmbeddingDimensions())
                    && Objects.equals(indexedAt, active.getIndexedAt())
                    && active.getActivatedAt() != null
                    && !isBlank(active.getActivatedByUserId())
                    && active.getFailureReason() == null
                    && active.getExtractionFailureReason() == null
                    && active.getFailedAt() == null;
        }
    }

    public record ActivationResult(ActivationOutcome outcome, CleanupStatus cleanupStatus) {
    }

    public enum ActivationOutcome {
        ACTIVATED,
        ALREADY_ACTIVE,
        AWAITING_SEARCH_INDEX
    }

    public enum CleanupStatus {
        NOT_ATTEMPTED,
        COMPLETED,
        INCOMPLETE
    }
}
