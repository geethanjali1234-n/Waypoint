package com.waypoint.service;

import com.waypoint.dto.myhr.KnowledgeDocumentResponse;
import com.waypoint.dto.myhr.KnowledgeManagementSettingsResponse;
import com.waypoint.dto.myhr.KnowledgeOperationResponse;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.entity.KnowledgeVersionHistoryEntry;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.DocumentExtractionException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.VerifiedMyHrActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The HR and company-admin workflow over the MyHR knowledge base: upload and approve a company
 * policy, replace it, accept incomplete PDF text, ingest, activate and revoke.
 *
 * <p>This class orchestrates the existing services, which keep enforcing their own rules
 * (organization scoping, version checks, leases, hashes, readiness). It adds only what they lack:
 * the HR-only COMPANY_POLICY upload, the management and ingestion switches, and a status view
 * that never contains policy text, hashes, embeddings, storage keys or user ids.
 *
 * <p>Nothing here runs on its own: every upload, ingestion and activation is an explicit HR
 * request. Adding knowledge is off unless waypoint.myhr.knowledge.management.enabled is set, and
 * ingestion - which sends policy text to the embedding provider - also needs
 * waypoint.myhr.knowledge.ingestion.enabled. Listing and revocation are never switched off, so
 * knowledge can always be reviewed and withdrawn.
 */
@Service
public class KnowledgeManagementService {

    static final List<String> ACCEPTED_FILE_EXTENSIONS = List.of("pdf", "docx", "xlsx", "txt", "md");
    /** Local text extraction refuses anything larger, so larger uploads could never be approved. */
    static final long MAX_FILE_BYTES = DocumentTextExtractor.MAX_FILE_BYTES;
    static final String RELATED_ENTITY_TYPE = "KNOWLEDGE_BASE";
    static final List<ChunkAccessLevel> ACCESS_LEVELS = List.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);

    static final String AUTHENTICATION_REQUIRED = "Authentication is required";
    static final String NOT_A_KNOWLEDGE_MANAGER = "Only HR or company administrators can manage the knowledge base";
    static final String MANAGEMENT_DISABLED = "Knowledge base management is not enabled";
    static final String INGESTION_DISABLED = "Knowledge ingestion is not enabled";
    static final String DOCUMENT_ID_REQUIRED = "Knowledge document ID is required";
    static final String FILE_REQUIRED = "A policy file is required";
    static final String FILE_TOO_LARGE = "Policy files cannot exceed 5 MB";
    static final String FILE_TYPE_NOT_SUPPORTED = "Policy files must be PDF, DOCX, XLSX, TXT or Markdown";
    static final String FILE_UNREADABLE = "The uploaded policy file could not be read";
    static final String STORAGE_FAILED = "The policy file could not be stored; try again later";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeManagementService.class);

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeApprovalService approvalService;
    private final KnowledgeIngestionService ingestionService;
    private final KnowledgeActivationService activationService;
    private final DocumentService documentService;
    private final Clock clock;
    private final boolean managementEnabled;
    private final boolean ingestionEnabled;
    private final boolean activationEnabled;
    private final boolean retrievalEnabled;
    private final boolean chatGroundingEnabled;

    public KnowledgeManagementService(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeApprovalService approvalService,
            KnowledgeIngestionService ingestionService,
            KnowledgeActivationService activationService,
            DocumentService documentService,
            Clock clock,
            @Value("${waypoint.myhr.knowledge.management.enabled:false}") boolean managementEnabled,
            @Value("${waypoint.myhr.knowledge.ingestion.enabled:false}") boolean ingestionEnabled,
            @Value("${waypoint.myhr.knowledge.activation.enabled:false}") boolean activationEnabled,
            @Value("${waypoint.myhr.knowledge.retrieval.enabled:false}") boolean retrievalEnabled,
            @Value("${waypoint.myhr.chat.knowledge-grounding.enabled:false}") boolean chatGroundingEnabled) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.approvalService = approvalService;
        this.ingestionService = ingestionService;
        this.activationService = activationService;
        this.documentService = documentService;
        this.clock = clock;
        this.managementEnabled = managementEnabled;
        this.ingestionEnabled = ingestionEnabled;
        this.activationEnabled = activationEnabled;
        this.retrievalEnabled = retrievalEnabled;
        this.chatGroundingEnabled = chatGroundingEnabled;
    }

    public KnowledgeManagementSettingsResponse settings(VerifiedMyHrActor actor) {
        requireKnowledgeManager(actor);
        return new KnowledgeManagementSettingsResponse(
                managementEnabled,
                managementEnabled && ingestionEnabled,
                managementEnabled && activationEnabled,
                retrievalEnabled,
                chatGroundingEnabled,
                ACCEPTED_FILE_EXTENSIONS,
                MAX_FILE_BYTES,
                ACCESS_LEVELS);
    }

    /** The actor's organization's entries, newest first, LIVE and REVOKED. */
    public List<KnowledgeDocumentResponse> list(VerifiedMyHrActor actor) {
        requireKnowledgeManager(actor);
        LocalDateTime now = LocalDateTime.now(clock);
        return knowledgeDocumentRepository.findByOrganizationIdOrderByCreatedAtDesc(actor.organizationId()).stream()
                .filter(entry -> entry != null && actor.organizationId().equals(entry.getOrganizationId()))
                .map(entry -> toResponse(entry, now))
                .toList();
    }

    /**
     * Stores the file as a COMPANY_POLICY document of the actor's organization and approves it as a
     * new entry. When approval rejects the file - wrong format, no text, duplicate content - the
     * upload is removed again, so rejected files never linger in storage.
     */
    public KnowledgeDocumentResponse addPolicy(
            VerifiedMyHrActor actor, MultipartFile file, String title, ChunkAccessLevel accessLevel) {
        requireKnowledgeManager(actor);
        requireManagementEnabled();
        requireTitle(title);
        requireAccessLevel(accessLevel);
        PolicyFile policy = readPolicyFile(file);

        StoredDocument stored = store(actor, policy);
        KnowledgeDocument approved;
        try {
            approved = approvalService.approve(actor, stored.getId(), title, accessLevel);
        } catch (BusinessException | ResourceNotFoundException rejected) {
            discardUpload(stored.getId());
            throw rejected;
        }
        return toResponse(approved, LocalDateTime.now(clock));
    }

    /**
     * Stores the file and approves it as the entry's pending replacement; the active version keeps
     * serving until the replacement is ingested and activated.
     */
    public KnowledgeDocumentResponse replacePolicy(
            VerifiedMyHrActor actor, String knowledgeDocumentId, MultipartFile file, ChunkAccessLevel accessLevel) {
        requireKnowledgeManager(actor);
        requireManagementEnabled();
        requireId(knowledgeDocumentId);
        requireAccessLevel(accessLevel);
        KnowledgeDocument entry = requireEntry(actor, knowledgeDocumentId);
        if (entry.getStatus() != KnowledgeDocumentStatus.LIVE) {
            throw new BusinessException(KnowledgeApprovalService.KNOWLEDGE_DOCUMENT_REVOKED);
        }
        PolicyFile policy = readPolicyFile(file);

        StoredDocument stored = store(actor, policy);
        KnowledgeDocument replaced;
        try {
            replaced = approvalService.approveReplacement(actor, knowledgeDocumentId, stored.getId(), accessLevel);
        } catch (BusinessException | ResourceNotFoundException rejected) {
            discardUpload(stored.getId());
            throw rejected;
        }
        return toResponse(replaced, LocalDateTime.now(clock));
    }

    /**
     * Re-approves a formerly active source as a fresh pending version. The approval service proves
     * that the stored bytes still belong to this organization and reruns extraction and G2 review;
     * historic chunks and ingestion ids cannot be revived through this operation.
     */
    public KnowledgeDocumentResponse restoreHistoricalVersion(
            VerifiedMyHrActor actor, String knowledgeDocumentId, String expectedHistoricalSourceDocumentId) {
        requireKnowledgeManager(actor);
        requireManagementEnabled();
        requireId(knowledgeDocumentId);
        requireId(expectedHistoricalSourceDocumentId);
        KnowledgeDocument restored = approvalService.restoreHistoricalVersion(
                actor, knowledgeDocumentId, expectedHistoricalSourceDocumentId);
        return toResponse(restored, LocalDateTime.now(clock));
    }

    public KnowledgeDocumentResponse acceptIncompleteText(
            VerifiedMyHrActor actor, String knowledgeDocumentId, String expectedSourceDocumentId) {
        requireKnowledgeManager(actor);
        requireManagementEnabled();
        KnowledgeDocument accepted =
                approvalService.acceptIncompleteExtraction(actor, knowledgeDocumentId, expectedSourceDocumentId);
        return toResponse(accepted, LocalDateTime.now(clock));
    }

    /** Runs ingestion for an explicit HR request only; nothing ingests on its own. */
    public KnowledgeOperationResponse ingest(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        requireKnowledgeManager(actor);
        requireManagementEnabled();
        if (!ingestionEnabled) {
            throw new BusinessException(INGESTION_DISABLED);
        }
        KnowledgeIngestionService.IngestionResult result;
        try {
            result = ingestionService.ingest(actor, knowledgeDocumentId);
        } catch (KnowledgeIngestionService.IngestionFailedException stopped) {
            // Its message is fixed and safe; it never carries a cause.
            throw new BusinessException(stopped.getMessage());
        }
        KnowledgeDocument document = result.document() != null
                ? result.document() : reload(actor, knowledgeDocumentId).orElse(null);
        return new KnowledgeOperationResponse(
                document == null ? null : toResponse(document, LocalDateTime.now(clock)),
                result.outcome().name(),
                ingestionMessage(result));
    }

    /** Activates exactly the ingestion HR reviewed; the activation service keeps its own switch and checks. */
    public KnowledgeOperationResponse activate(
            VerifiedMyHrActor actor, String knowledgeDocumentId, String expectedIngestionId) {
        requireKnowledgeManager(actor);
        requireManagementEnabled();
        KnowledgeActivationService.ActivationResult result =
                activationService.activate(actor, knowledgeDocumentId, expectedIngestionId);
        KnowledgeDocument document = reload(actor, knowledgeDocumentId).orElse(null);
        return new KnowledgeOperationResponse(
                document == null ? null : toResponse(document, LocalDateTime.now(clock)),
                result.outcome().name(),
                activationMessage(result));
    }

    /**
     * Never switched off: revoking immediately stops every chunk of the entry from serving. Calling
     * it again on a revoked entry retries removal of its indexed chunks.
     */
    public KnowledgeOperationResponse revoke(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        requireKnowledgeManager(actor);
        KnowledgeApprovalService.RevocationResult result = approvalService.revoke(actor, knowledgeDocumentId);
        String message = result.chunkCleanupCompleted()
                ? "The document was revoked and its indexed sections were removed."
                : "The document was revoked and no longer informs answers, but some indexed sections could not be "
                + "removed yet. Retry the cleanup later.";
        return new KnowledgeOperationResponse(
                toResponse(result.document(), LocalDateTime.now(clock)),
                result.chunkCleanupCompleted() ? "REVOKED" : "REVOKED_CLEANUP_INCOMPLETE",
                message);
    }

    // --- Status view ----------------------------------------------------------------------------

    KnowledgeDocumentResponse toResponse(KnowledgeDocument entry, LocalDateTime now) {
        boolean live = entry.getStatus() == KnowledgeDocumentStatus.LIVE;
        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        KnowledgeDocumentResponse.Actions actions = new KnowledgeDocumentResponse.Actions(
                live && managementEnabled && needsIncompleteExtractionAcceptance(pending),
                live && managementEnabled && ingestionEnabled && isIngestible(pending, now),
                live && managementEnabled && activationEnabled
                        && pending != null && pending.getStage() == KnowledgeIngestionStage.INDEXED,
                live && managementEnabled && isReplaceable(pending, now),
                live,
                entry.getStatus() == KnowledgeDocumentStatus.REVOKED,
                live && managementEnabled && needsContentReview(pending),
                live && managementEnabled && isReplaceable(pending, now) && hasRestorableHistory(entry));
        return new KnowledgeDocumentResponse(
                entry.getId(),
                entry.getTitle(),
                entry.getStatus(),
                version(entry.getActiveVersion()),
                version(pending),
                entry.getVersionHistory().size(),
                historicalVersions(entry),
                entry.getCreatedAt(),
                entry.getRevokedAt(),
                actions);
    }

    private static List<KnowledgeDocumentResponse.HistoricalVersion> historicalVersions(KnowledgeDocument entry) {
        List<KnowledgeDocumentResponse.HistoricalVersion> versions = new java.util.ArrayList<>();
        for (KnowledgeVersionHistoryEntry history : entry.getVersionHistory()) {
            if (history == null || history.getVersion() == null || history.getVersion().getSourceDocumentId() == null) {
                continue;
            }
            KnowledgeDocumentVersion version = history.getVersion();
            versions.add(new KnowledgeDocumentResponse.HistoricalVersion(version.getSourceDocumentId(),
                    version.getSourceDocumentName(), version.getAccessLevel(), version.getActivatedAt(),
                    history.getSupersededAt(), isRestorableHistoryVersion(entry, version)));
        }
        java.util.Collections.reverse(versions);
        return List.copyOf(versions);
    }

    private static boolean hasRestorableHistory(KnowledgeDocument entry) {
        return entry.getVersionHistory().stream().anyMatch(history -> {
            KnowledgeDocumentVersion version = history == null ? null : history.getVersion();
            return isRestorableHistoryVersion(entry, version);
        });
    }

    private static boolean isRestorableHistoryVersion(KnowledgeDocument entry, KnowledgeDocumentVersion version) {
        return version != null && version.getSourceDocumentId() != null
                && version.getDocumentType() == DocumentType.COMPANY_POLICY
                && version.getAccessLevel() != null && ACCESS_LEVELS.contains(version.getAccessLevel())
                && (entry.getActiveVersion() == null
                || !version.getSourceDocumentId().equals(entry.getActiveVersion().getSourceDocumentId()));
    }

    private static KnowledgeDocumentResponse.Version version(KnowledgeDocumentVersion version) {
        if (version == null) {
            return null;
        }
        return new KnowledgeDocumentResponse.Version(
                version.getSourceDocumentId(),
                version.getSourceDocumentName(),
                version.getAccessLevel(),
                version.getStage(),
                version.getPageCount(),
                version.getPagesWithoutText(),
                version.getIncompleteExtractionAcceptedAt() != null,
                version.getIngestionId(),
                version.getChunkCount(),
                version.getApprovedAt(),
                version.getIngestionStartedAt(),
                version.getIndexedAt(),
                version.getActivatedAt(),
                version.getFailedAt(),
                problem(version),
                version.getStage() == KnowledgeIngestionStage.FAILED
                        && KnowledgeIngestionService.RETRYABLE_FAILURES.contains(version.getFailureReason()),
                contentReview(version));
    }

    /** Counts and decision only; null for a version without a G2 inspection (for example legacy LIVE ones). */
    private static KnowledgeDocumentResponse.ContentReview contentReview(KnowledgeDocumentVersion version) {
        if (version.getInspectionFormatVersion() == null || version.getContentReviewRequired() == null) {
            return null;
        }
        return new KnowledgeDocumentResponse.ContentReview(
                KnowledgeContentReviewMetadata.hasValidInspection(version),
                version.getContentReviewRequired(),
                KnowledgeContentReviewMetadata.hasSatisfiedContentReview(version),
                version.getContentReviewDecision(),
                count(version.getHiddenDocxRunsExcluded()),
                count(version.getSuspiciousTextFragments()),
                count(version.getInvisibleUnicodeCharacters()),
                count(version.getUnicodeTagCharactersRemoved()),
                count(version.getHiddenCommentBlocks()),
                count(version.getInstructionLikeContentMatches()),
                count(version.getPagesRequiringOcrReview()));
    }

    private static int count(Integer value) {
        return value == null || value < 0 ? 0 : value;
    }

    /**
     * The bound content-review decision is still outstanding. Mirrors the review service's own
     * checks, which remain the authority: a version without a valid inspection is never offered.
     */
    private static boolean needsContentReview(KnowledgeDocumentVersion pending) {
        return pending != null && pending.getStage() == KnowledgeIngestionStage.NEEDS_REVIEW
                && Boolean.TRUE.equals(pending.getContentReviewRequired())
                && KnowledgeContentReviewMetadata.hasValidInspection(pending)
                && !KnowledgeContentReviewMetadata.hasSatisfiedContentReview(pending);
    }

    /** Fixed text per recorded reason code; never provider or storage messages. */
    static String problem(KnowledgeDocumentVersion version) {
        KnowledgeIngestionFailure failure = version.getFailureReason();
        if (failure == null) {
            return null;
        }
        String message = switch (failure) {
            case SOURCE_DOCUMENT_INELIGIBLE -> "The approved file is no longer an eligible company policy. Approve a replacement.";
            case STORAGE_UNAVAILABLE -> "The approved file could not be read from storage. Try ingesting again.";
            case CONTENT_CHANGED -> "The stored file no longer matches the approved version. Approve a replacement.";
            case EXTRACTION_FAILED -> "Text could not be extracted from the file. Approve a replacement.";
            case TOO_MANY_CHUNKS -> "The document is too long to index. Split it into smaller policies.";
            case EMBEDDING_CONFIGURATION -> "Document embedding is not configured. Ask an administrator, then try again.";
            case EMBEDDING_REQUEST_REJECTED -> "The embedding service rejected the document. Try again later.";
            case EMBEDDING_UNAVAILABLE -> "The embedding service is unavailable. Try again later.";
            case INVALID_EMBEDDING -> "The embedding service returned an unusable result. Try again.";
            case CHUNK_WRITE_FAILED -> "The indexed sections could not be saved. Try again.";
            case ACTIVATION_CONFLICT -> "The version changed while it was being processed. Reload and try again.";
            case CONTENT_REVIEW_REJECTED -> "The content review did not approve this version. Approve a replacement.";
            case EXTERNAL_PROCESSING_NOT_APPROVED -> "External embedding of this content is not approved for your "
                    + "organization. A Company Admin must approve it, then try again.";
        };
        DocumentExtractionException.Reason reason = version.getExtractionFailureReason();
        return reason == null ? message : message + " (" + new DocumentExtractionException(reason).getMessage() + ")";
    }

    private static String ingestionMessage(KnowledgeIngestionService.IngestionResult result) {
        String message = switch (result.outcome()) {
            case INDEXED -> "Indexed " + result.chunkCount() + " sections. Activate this version to make it available.";
            case FAILED -> {
                KnowledgeIngestionFailure failure = result.failure();
                yield failure == null ? "Ingestion failed. Try again later."
                        : problem(KnowledgeDocumentVersion.builder().failureReason(failure).build());
            }
            case SUPERSEDED -> "Another change replaced this version during ingestion. Reload to see the current version.";
            case REVOKED -> "The document was revoked during ingestion.";
        };
        return result.chunkCleanupCompleted() || result.outcome() == KnowledgeIngestionService.Outcome.INDEXED
                ? message : message + " Some partially written sections are cleaned up automatically later.";
    }

    private static String activationMessage(KnowledgeActivationService.ActivationResult result) {
        String message = switch (result.outcome()) {
            case ACTIVATED -> "This version is now active.";
            case ALREADY_ACTIVE -> "This version is already active.";
            case AWAITING_SEARCH_INDEX ->
                    "Indexing is complete, but the search index has not caught up yet. Try activating again in a minute.";
        };
        return result.cleanupStatus() == KnowledgeActivationService.CleanupStatus.INCOMPLETE
                ? message + " Sections from older versions will be removed on a later attempt." : message;
    }

    private static boolean isIngestible(KnowledgeDocumentVersion pending, LocalDateTime now) {
        if (pending == null || pending.getStage() == null || !KnowledgeContentReviewMetadata.hasValidInspection(pending)
                || (Boolean.TRUE.equals(pending.getContentReviewRequired())
                && !KnowledgeContentReviewMetadata.hasSatisfiedContentReview(pending))) {
            return false;
        }
        return switch (pending.getStage()) {
            case APPROVED -> true;
            case FAILED -> KnowledgeIngestionService.RETRYABLE_FAILURES.contains(pending.getFailureReason());
            case INGESTING -> leaseExpired(pending, now);
            case NEEDS_REVIEW, INDEXED -> false;
        };
    }

    /** The existing endpoint accepts only incomplete PDF text, never G2 suspicious-content findings. */
    private static boolean needsIncompleteExtractionAcceptance(KnowledgeDocumentVersion pending) {
        return pending != null && pending.getStage() == KnowledgeIngestionStage.NEEDS_REVIEW
                && pending.getPagesWithoutText() != null && pending.getPagesWithoutText() > 0
                && pending.getIncompleteExtractionAcceptedAt() == null;
    }

    private static boolean isReplaceable(KnowledgeDocumentVersion pending, LocalDateTime now) {
        if (pending == null) {
            return true;
        }
        if (pending.getStage() == null) {
            return false;
        }
        return pending.getStage() != KnowledgeIngestionStage.INGESTING || leaseExpired(pending, now);
    }

    private static boolean leaseExpired(KnowledgeDocumentVersion pending, LocalDateTime now) {
        LocalDateTime startedAt = pending.getIngestionStartedAt();
        return startedAt != null && !now.isBefore(startedAt.plus(KnowledgeApprovalService.INGESTION_LEASE));
    }

    // --- Upload ---------------------------------------------------------------------------------

    private static PolicyFile readPolicyFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(FILE_REQUIRED);
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(FILE_TOO_LARGE);
        }
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().strip();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!ACCEPTED_FILE_EXTENSIONS.contains(extension)) {
            throw new BusinessException(FILE_TYPE_NOT_SUPPORTED);
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException unreadable) {
            throw new BusinessException(FILE_UNREADABLE);
        }
        if (content.length == 0) {
            throw new BusinessException(FILE_REQUIRED);
        }
        if (content.length > MAX_FILE_BYTES) {
            throw new BusinessException(FILE_TOO_LARGE);
        }
        String contentType = file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream" : file.getContentType();
        return new PolicyFile(fileName, contentType, content);
    }

    private StoredDocument store(VerifiedMyHrActor actor, PolicyFile policy) {
        try {
            return documentService.store(new DocumentUploadRequest(
                    actor.organizationId(),
                    actor.userId(),
                    DocumentType.COMPANY_POLICY,
                    RELATED_ENTITY_TYPE,
                    null,
                    policy.fileName(),
                    policy.contentType(),
                    policy.content(),
                    null,
                    actor.userId()));
        } catch (BusinessException invalid) {
            throw invalid;
        } catch (RuntimeException storageFailure) {
            // Class name only: storage messages can name storage keys and connection details.
            log.warn("A knowledge policy file could not be stored ({})", storageFailure.getClass().getSimpleName());
            throw new BusinessException(STORAGE_FAILED);
        }
    }

    /** Removes an upload its approval rejected. Unexpected failures keep it: the approval may have been saved. */
    private void discardUpload(String storedDocumentId) {
        try {
            documentService.delete(storedDocumentId);
        } catch (RuntimeException cleanupFailure) {
            log.warn("A rejected knowledge policy upload could not be removed ({})",
                    cleanupFailure.getClass().getSimpleName());
        }
    }

    // --- Entries and checks ---------------------------------------------------------------------

    private KnowledgeDocument requireEntry(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        return knowledgeDocumentRepository.findByIdAndOrganizationId(knowledgeDocumentId, actor.organizationId())
                .filter(found -> actor.organizationId().equals(found.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException(KnowledgeApprovalService.KNOWLEDGE_DOCUMENT_NOT_FOUND));
    }

    private Optional<KnowledgeDocument> reload(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        try {
            return knowledgeDocumentRepository.findByIdAndOrganizationId(knowledgeDocumentId, actor.organizationId())
                    .filter(found -> actor.organizationId().equals(found.getOrganizationId()));
        } catch (RuntimeException unavailable) {
            log.warn("Knowledge document {} could not be re-read ({})", knowledgeDocumentId,
                    unavailable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static void requireKnowledgeManager(VerifiedMyHrActor actor) {
        if (actor == null) {
            throw new UnauthorizedException(AUTHENTICATION_REQUIRED);
        }
        if (!actor.canManageKnowledge()) {
            throw new ForbiddenException(NOT_A_KNOWLEDGE_MANAGER);
        }
    }

    private void requireManagementEnabled() {
        if (!managementEnabled) {
            throw new BusinessException(MANAGEMENT_DISABLED);
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessException(DOCUMENT_ID_REQUIRED);
        }
    }

    /** Checked before upload so an invalid request never writes to storage; approval checks again. */
    private static void requireTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new BusinessException(KnowledgeApprovalService.TITLE_REQUIRED);
        }
        if (title.strip().length() > KnowledgeApprovalService.MAX_TITLE_LENGTH) {
            throw new BusinessException(KnowledgeApprovalService.TITLE_TOO_LONG);
        }
    }

    private static void requireAccessLevel(ChunkAccessLevel accessLevel) {
        if (accessLevel == null || !ACCESS_LEVELS.contains(accessLevel)) {
            throw new BusinessException(KnowledgeApprovalService.ACCESS_LEVEL_NOT_ALLOWED);
        }
    }

    private record PolicyFile(String fileName, String contentType, byte[] content) {

        @Override
        public String toString() {
            return "PolicyFile[bytes=" + content.length + "]";
        }
    }
}
