package com.waypoint.service;

import com.waypoint.entity.KnowledgeChunk;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
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
import com.waypoint.repository.KnowledgeChunkRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.DocumentChunkingService.TextChunk;
import com.waypoint.service.DocumentTextExtractor.ExtractedText;
import com.waypoint.service.KnowledgeSourceDocumentReader.SourceDocumentException;
import com.waypoint.service.KnowledgeSourceDocumentReader.VerifiedSourceContent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns an approved pending knowledge version into stored, embedded chunks, leaving it INDEXED:
 * written and complete, but not serving. A chunk serves only once its ingestionId is the LIVE
 * entry's activeVersion.ingestionId, and activation is a separate step, so nothing written here is
 * ever exposed; the active version is never touched.
 *
 * <p>The pending version is claimed with one {@code @Version}-checked save (INGESTING, a new
 * ingestionId, ingestionStartedAt on the shared Clock), so of two concurrent requests only one
 * proceeds. Every later state change re-reads the entry and is version-checked too, and it applies
 * only while the entry is LIVE and still holds this ingestionId at INGESTING: a replacement or
 * revocation always wins, and recording a failure can never overwrite one. Ownership is also
 * re-checked before every embedding batch and every chunk-write batch, so work stops as soon as it
 * is lost.
 *
 * <p>The stored bytes are re-verified against the approved hash and file name, every embedding is
 * prepared and validated before the first chunk is written, and chunks are only ever deleted by
 * one exact obsolete ingestionId, never one the entry holds as active or as an INDEXED pending.
 *
 * <p>Failures that are understood are recorded as reason codes on the version. Anything else is
 * reported with a fixed message and no cause, and leaves the version INGESTING for the 30-minute
 * lease after which ingestion can be retried. Logs carry knowledge document ids, ingestion ids,
 * counts, reason codes and exception class names only.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeIngestionService {

    static final int EMBEDDING_BATCH_SIZE = 32;
    static final int CHUNK_WRITE_BATCH_SIZE = 100;
    static final int MAX_CHUNKS = 1000;
    /** Must equal numDimensions of the knowledge_chunks Atlas vector index. */
    static final int REQUIRED_DIMENSIONS = 1024;

    /** Failures an explicitly requested ingest may retry; any other failure needs a replacement approval. */
    static final Set<KnowledgeIngestionFailure> RETRYABLE_FAILURES = Collections.unmodifiableSet(EnumSet.of(
            KnowledgeIngestionFailure.STORAGE_UNAVAILABLE,
            KnowledgeIngestionFailure.EMBEDDING_CONFIGURATION,
            KnowledgeIngestionFailure.EMBEDDING_REQUEST_REJECTED,
            KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE,
            KnowledgeIngestionFailure.INVALID_EMBEDDING,
            KnowledgeIngestionFailure.CHUNK_WRITE_FAILED,
            KnowledgeIngestionFailure.EXTERNAL_PROCESSING_NOT_APPROVED));

    private static final Set<ChunkAccessLevel> INGESTIBLE_ACCESS_LEVELS =
            Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    static final String AUTHENTICATION_REQUIRED = "Authentication is required";
    static final String NOT_A_KNOWLEDGE_MANAGER = "Only HR or company administrators can manage the knowledge base";
    static final String DOCUMENT_ID_REQUIRED = "Knowledge document ID is required";
    static final String KNOWLEDGE_DOCUMENT_NOT_FOUND = "Knowledge document not found";
    static final String KNOWLEDGE_DOCUMENT_REVOKED = "This knowledge document has been revoked";
    static final String NOTHING_TO_INGEST = "This knowledge document has no approved version waiting for ingestion";
    static final String NEEDS_REVIEW = "The pending version needs HR review of its incomplete text before it can be ingested";
    static final String INCOMPLETE_EXTRACTION_NOT_ACCEPTED =
            "The pending version has pages without text that HR has not accepted";
    static final String CONTENT_INSPECTION_REQUIRED =
            "The pending version has not completed the required content inspection; approve a replacement document instead";
    static final String CONTENT_REVIEW_NOT_ACCEPTED =
            "The pending version has hidden or suspicious content that has not received a valid review decision";
    static final String INGESTION_IN_PROGRESS = "The pending version is already being ingested";
    static final String INGESTION_STATE_UNRESOLVED =
            "The pending version's ingestion state is unresolved, so it cannot be ingested";
    static final String ALREADY_INDEXED = "The pending version is already ingested and awaiting activation";
    static final String FAILURE_NOT_RETRYABLE =
            "The pending version's last ingestion failure cannot be retried; approve a replacement document instead";
    static final String PENDING_VERSION_INVALID = "The pending version's approval record is incomplete or invalid";
    static final String EMBEDDING_NOT_CONFIGURED = "Document embedding is not configured for the knowledge base";
    static final String EMBEDDING_NOT_APPROVED = "Your organization has not approved sending HR-only policy text to "
            + "the external embedding provider (Voyage AI), so this version cannot be indexed. A Company Admin must "
            + "record that approval first.";
    static final String CLAIM_CONFLICT =
            "This knowledge document changed or is already being ingested; reload it and try again";
    static final String NOT_STARTED = "Knowledge ingestion could not be started; try again later";
    static final String STOPPED_UNEXPECTEDLY =
            "Knowledge ingestion stopped unexpectedly; it can be retried once its 30-minute ingestion lease expires";

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestionService.class);

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;
    private final KnowledgeSourceDocumentReader sourceDocumentReader;
    private final DocumentTextExtractor textExtractor;
    private final DocumentChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final KnowledgeChunkCleaner knowledgeChunkCleaner;
    private final Clock clock;
    /** Whether the pending version's text may be sent to Voyage AI; checked before claiming and before embedding. */
    private final VoyageEmbeddingAuthorization voyageEmbeddingAuthorization;

    /**
     * Ingests the entry's pending version: APPROVED, or an explicit retry of a FAILED version with a
     * retryable reason or of an INGESTING version whose lease has expired. Requests that cannot
     * start change nothing and throw; once the version is claimed, the outcome is returned.
     *
     * @throws IngestionFailedException with a fixed message when something unexpected stops ingestion
     */
    public IngestionResult ingest(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        requireKnowledgeManager(actor);
        if (isBlank(knowledgeDocumentId)) {
            throw new BusinessException(DOCUMENT_ID_REQUIRED);
        }
        Claim claim;
        try {
            claim = claim(actor, knowledgeDocumentId);
        } catch (BusinessException | ResourceNotFoundException rejected) {
            throw rejected;
        } catch (RuntimeException unexpected) {
            // Class name only: driver messages can include connection details.
            log.warn("Knowledge ingestion could not be started ({})", unexpected.getClass().getSimpleName());
            throw new IngestionFailedException(NOT_STARTED);
        }
        return run(claim);
    }

    /**
     * The entry as last read or saved (null when it could not be read), what happened, and whether
     * every chunk this attempt wrote was removed again; chunkCount is set only when INDEXED.
     */
    public record IngestionResult(KnowledgeDocument document, Outcome outcome, String ingestionId, int chunkCount,
                                  KnowledgeIngestionFailure failure, boolean chunkCleanupCompleted) {
    }

    public enum Outcome {
        /** Every chunk is written and the pending version awaits activation. */
        INDEXED,
        /** The failure is recorded on the version (or, if it could not be saved, the lease will expire). */
        FAILED,
        /** A replacement or another ingestion took over the pending version; it was left as found. */
        SUPERSEDED,
        /** The entry was revoked during ingestion; it was left as found. */
        REVOKED
    }

    /** Fixed message and no cause, so nothing from storage, providers or the document can leak through it. */
    public static class IngestionFailedException extends RuntimeException {
        IngestionFailedException(String message) {
            super(message);
        }
    }

    // --- Claim --------------------------------------------------------------------------------------

    private Claim claim(VerifiedMyHrActor actor, String knowledgeDocumentId) {
        String organizationId = actor.organizationId();
        KnowledgeDocument entry = findEntry(organizationId, knowledgeDocumentId)
                .orElseThrow(() -> new ResourceNotFoundException(KNOWLEDGE_DOCUMENT_NOT_FOUND));
        if (entry.getStatus() != KnowledgeDocumentStatus.LIVE) {
            throw new BusinessException(KNOWLEDGE_DOCUMENT_REVOKED);
        }
        LocalDateTime now = LocalDateTime.now(clock);
        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        String obsoleteIngestionId = requireClaimable(pending, now);
        requireIngestible(entry, pending);
        if (!voyageEmbeddingAuthorization.permitsDocumentEmbedding(organizationId, pending.getAccessLevel())) {
            // Nothing is claimed or sent: HR-only text leaves Waypoint only with the organization's Voyage approval.
            log.info("Knowledge document {} not ingested: external embedding of {} content is not approved",
                    entry.getId(), pending.getAccessLevel());
            throw new BusinessException(EMBEDDING_NOT_APPROVED);
        }
        String model = requireEmbeddingConfiguration();

        String ingestionId = UUID.randomUUID().toString();
        pending.setStage(KnowledgeIngestionStage.INGESTING);
        pending.setIngestionId(ingestionId);
        pending.setIngestionStartedAt(now);
        pending.setChunkCount(null);
        pending.setEmbeddingModel(null);
        pending.setEmbeddingDimensions(null);
        pending.setIndexedAt(null);
        pending.setActivatedAt(null);
        pending.setFailureReason(null);
        pending.setExtractionFailureReason(null);
        pending.setFailedAt(null);

        KnowledgeDocument claimed;
        try {
            claimed = knowledgeDocumentRepository.save(entry);
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw new BusinessException(CLAIM_CONFLICT);
        }
        log.info("Knowledge document {} ingestion {} claimed by {}", claimed.getId(), ingestionId, actor.role());
        return new Claim(organizationId, claimed.getId(), ingestionId, model, pending.getSourceDocumentId(),
                pending.getSourceDocumentName(), pending.getContentSha256(), pending.getAccessLevel(),
                pending.getPageCount(), pending.getPagesWithoutText(), pending.getInspectionFindingsSha256(),
                pending.getIndexableTextSha256(), pending.getReviewablePassagesSha256(), obsoleteIngestionId);
    }

    /** Returns the ingestionId of a previous attempt whose chunks are now obsolete, if any. */
    private static String requireClaimable(KnowledgeDocumentVersion pending, LocalDateTime now) {
        if (pending == null) {
            throw new BusinessException(NOTHING_TO_INGEST);
        }
        if (pending.getStage() == null) {
            throw new BusinessException(INGESTION_STATE_UNRESOLVED);
        }
        return switch (pending.getStage()) {
            case APPROVED -> null;
            case NEEDS_REVIEW -> throw new BusinessException(NEEDS_REVIEW);
            case INDEXED -> throw new BusinessException(ALREADY_INDEXED);
            case FAILED -> {
                if (pending.getFailureReason() == null || !RETRYABLE_FAILURES.contains(pending.getFailureReason())) {
                    throw new BusinessException(FAILURE_NOT_RETRYABLE);
                }
                yield pending.getIngestionId();
            }
            case INGESTING -> {
                // Without a start there is no lease to expire, so this is never treated as abandoned.
                if (pending.getIngestionStartedAt() == null || isBlank(pending.getIngestionId())) {
                    throw new BusinessException(INGESTION_STATE_UNRESOLVED);
                }
                if (now.isBefore(pending.getIngestionStartedAt().plus(KnowledgeApprovalService.INGESTION_LEASE))) {
                    throw new BusinessException(INGESTION_IN_PROGRESS);
                }
                yield pending.getIngestionId();
            }
        };
    }

    private static void requireIngestible(KnowledgeDocument entry, KnowledgeDocumentVersion pending) {
        if (pending.getDocumentType() != DocumentType.COMPANY_POLICY
                || pending.getAccessLevel() == null
                || !INGESTIBLE_ACCESS_LEVELS.contains(pending.getAccessLevel())
                || pending.getContentSha256() == null
                || !SHA256_HEX.matcher(pending.getContentSha256()).matches()
                || isBlank(pending.getApprovedByUserId())
                || isBlank(pending.getSourceDocumentId())
                || isBlank(pending.getSourceDocumentName())
                || entry.getSourceDocumentIds() == null
                || !entry.getSourceDocumentIds().contains(pending.getSourceDocumentId())
                || pending.getPageCount() == null
                || pending.getPagesWithoutText() == null
                || pending.getPageCount() < 0
                || pending.getPagesWithoutText() < 0
                || pending.getPagesWithoutText() > pending.getPageCount()) {
            throw new BusinessException(PENDING_VERSION_INVALID);
        }
        if (pending.getPagesWithoutText() > 0 && isBlank(pending.getIncompleteExtractionAcceptedByUserId())) {
            throw new BusinessException(INCOMPLETE_EXTRACTION_NOT_ACCEPTED);
        }
        if (!KnowledgeContentReviewMetadata.hasValidInspection(pending)) {
            throw new BusinessException(CONTENT_INSPECTION_REQUIRED);
        }
        if (Boolean.TRUE.equals(pending.getContentReviewRequired())
                && !KnowledgeContentReviewMetadata.hasSatisfiedContentReview(pending)) {
            throw new BusinessException(CONTENT_REVIEW_NOT_ACCEPTED);
        }
    }

    private String requireEmbeddingConfiguration() {
        String model = embeddingService.model();
        if (isBlank(model) || embeddingService.dimensions() != REQUIRED_DIMENSIONS) {
            throw new BusinessException(EMBEDDING_NOT_CONFIGURED);
        }
        return model;
    }

    // --- Claimed work -------------------------------------------------------------------------------

    private IngestionResult run(Claim claim) {
        if (claim.obsoleteIngestionId() != null) {
            knowledgeChunkCleaner.cleanObsoleteChunks(claim.organizationId(), claim.knowledgeDocumentId());
        }
        Attempt attempt = new Attempt(claim);
        try {
            return process(attempt);
        } catch (Stopped stopped) {
            log.info("Knowledge document {} ingestion {} stopped ({})",
                    claim.knowledgeDocumentId(), claim.ingestionId(), stopped.outcome);
            boolean cleaned = !attempt.writesStarted || cleanupAttempt(claim);
            return new IngestionResult(stopped.document, stopped.outcome, claim.ingestionId(), 0, null, cleaned);
        } catch (Failed failed) {
            Recorded recorded = recordFailure(claim, failed);
            boolean cleaned = !attempt.writesStarted || cleanupAttempt(claim);
            return new IngestionResult(recorded.document, recorded.outcome, claim.ingestionId(), 0, failed.failure, cleaned);
        } catch (RuntimeException unexpected) {
            // Class name only, and no cause on what is thrown: messages can quote providers, storage or the document.
            log.warn("Knowledge document {} ingestion {} stopped unexpectedly ({}); it can be retried once its lease expires",
                    claim.knowledgeDocumentId(), claim.ingestionId(), unexpected.getClass().getSimpleName());
            if (attempt.writesStarted) {
                cleanupAttempt(claim);
            }
            throw new IngestionFailedException(STOPPED_UNEXPECTEDLY);
        }
    }

    private IngestionResult process(Attempt attempt) {
        Claim claim = attempt.claim;
        VerifiedSourceContent source = readSource(claim);
        List<TextChunk> chunks = chunk(extract(claim, source));
        List<List<Double>> embeddings = embed(claim, chunks);
        List<KnowledgeChunk> records = buildChunks(claim, chunks, embeddings);
        write(attempt, records);
        return markIndexed(claim, records.size());
    }

    private VerifiedSourceContent readSource(Claim claim) {
        VerifiedSourceContent source;
        try {
            source = sourceDocumentReader.read(claim.organizationId(), claim.sourceDocumentId(), claim.contentSha256());
        } catch (SourceDocumentException rejected) {
            throw new Failed(rejected.getFailure(), null);
        }
        if (source == null || source.metadata() == null || source.content() == null) {
            throw new Failed(KnowledgeIngestionFailure.STORAGE_UNAVAILABLE, null);
        }
        // The extension decides how bytes are extracted, so it must still be the name HR approved.
        if (!Objects.equals(source.metadata().getFileName(), claim.sourceDocumentName())) {
            throw new Failed(KnowledgeIngestionFailure.CONTENT_CHANGED, null);
        }
        return source;
    }

    private String extract(Claim claim, VerifiedSourceContent source) {
        ExtractedText extracted;
        try {
            extracted = textExtractor.extract(source.content(), claim.sourceDocumentName());
        } catch (DocumentExtractionException failure) {
            throw new Failed(KnowledgeIngestionFailure.EXTRACTION_FAILED, failure.getReason());
        }
        // Same bytes must extract as they did at approval; HR accepted exactly these missing pages.
        if (extracted == null || isBlank(extracted.text())
                || extracted.pageCount() != claim.pageCount()
                || extracted.pagesWithoutText() != claim.pagesWithoutText()
                || extracted.inspection() == null
                || !Objects.equals(claim.inspectionFindingsSha256(),
                KnowledgeContentReviewMetadata.fingerprint(extracted.inspection()))
                || !Objects.equals(claim.indexableTextSha256(),
                KnowledgeContentReviewMetadata.textFingerprint(extracted.text()))
                || (claim.reviewablePassagesSha256() != null && !Objects.equals(claim.reviewablePassagesSha256(),
                KnowledgeContentReviewMetadata.passagesFingerprint(
                        extracted.inspection().reviewablePassages(extracted.text()))))) {
            throw new Failed(KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
        }
        return extracted.text();
    }

    private List<TextChunk> chunk(String text) {
        List<TextChunk> chunks = chunkingService.chunk(text);
        if (chunks == null || chunks.isEmpty()) {
            throw new Failed(KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
        }
        if (chunks.size() > MAX_CHUNKS) {
            throw new Failed(KnowledgeIngestionFailure.TOO_MANY_CHUNKS, null);
        }
        for (int position = 0; position < chunks.size(); position++) {
            TextChunk chunk = chunks.get(position);
            if (chunk == null || chunk.index() != position || isBlank(chunk.text())) {
                throw new Failed(KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
            }
        }
        return chunks;
    }

    /** All embeddings, in chunk order, before anything is written. */
    private List<List<Double>> embed(Claim claim, List<TextChunk> chunks) {
        // Rechecked immediately before any text is sent, in case the approval was revoked after the claim.
        if (!voyageEmbeddingAuthorization.permitsDocumentEmbedding(claim.organizationId(), claim.accessLevel())) {
            throw new Failed(KnowledgeIngestionFailure.EXTERNAL_PROCESSING_NOT_APPROVED, null);
        }
        List<List<Double>> embeddings = new ArrayList<>(chunks.size());
        for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
            requireOwnership(claim);
            List<String> texts = chunks.subList(start, Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size()))
                    .stream().map(TextChunk::text).toList();
            List<List<Double>> batch;
            try {
                batch = embeddingService.embedBatch(texts, EmbeddingService.InputType.DOCUMENT);
            } catch (EmbeddingService.EmbeddingConfigurationException failure) {
                throw embeddingFailure(claim, KnowledgeIngestionFailure.EMBEDDING_CONFIGURATION, failure);
            } catch (EmbeddingService.EmbeddingRequestException | IllegalArgumentException failure) {
                throw embeddingFailure(claim, KnowledgeIngestionFailure.EMBEDDING_REQUEST_REJECTED, failure);
            } catch (EmbeddingService.EmbeddingDimensionMismatchException failure) {
                throw embeddingFailure(claim, KnowledgeIngestionFailure.INVALID_EMBEDDING, failure);
            } catch (EmbeddingService.EmbeddingException failure) {
                throw embeddingFailure(claim, KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE, failure);
            }
            embeddings.addAll(validated(batch, texts.size()));
        }
        if (!claim.model().equals(embeddingService.model())) {
            throw new Failed(KnowledgeIngestionFailure.INVALID_EMBEDDING, null);
        }
        return embeddings;
    }

    private static Failed embeddingFailure(Claim claim, KnowledgeIngestionFailure failure, RuntimeException cause) {
        log.info("Knowledge document {} ingestion {} embedding failed ({}, {})",
                claim.knowledgeDocumentId(), claim.ingestionId(), failure, cause.getClass().getSimpleName());
        return new Failed(failure, null);
    }

    /** One vector per input, each REQUIRED_DIMENSIONS finite values and not all zero (cosine needs a direction). */
    private static List<List<Double>> validated(List<List<Double>> batch, int expected) {
        if (batch == null || batch.size() != expected) {
            throw new Failed(KnowledgeIngestionFailure.INVALID_EMBEDDING, null);
        }
        List<List<Double>> vectors = new ArrayList<>(expected);
        for (List<Double> vector : batch) {
            if (vector == null || vector.size() != REQUIRED_DIMENSIONS) {
                throw new Failed(KnowledgeIngestionFailure.INVALID_EMBEDDING, null);
            }
            boolean nonZero = false;
            for (Double value : vector) {
                if (value == null || !Double.isFinite(value)) {
                    throw new Failed(KnowledgeIngestionFailure.INVALID_EMBEDDING, null);
                }
                nonZero |= value != 0.0;
            }
            if (!nonZero) {
                throw new Failed(KnowledgeIngestionFailure.INVALID_EMBEDDING, null);
            }
            vectors.add(List.copyOf(vector));
        }
        return vectors;
    }

    private List<KnowledgeChunk> buildChunks(Claim claim, List<TextChunk> chunks, List<List<Double>> embeddings) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<KnowledgeChunk> records = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            records.add(KnowledgeChunk.builder()
                    .id(chunkId(claim.ingestionId(), chunk.index()))
                    .organizationId(claim.organizationId())
                    .sourceDocumentId(claim.sourceDocumentId())
                    .sourceDocumentName(claim.sourceDocumentName())
                    .knowledgeDocumentId(claim.knowledgeDocumentId())
                    .ingestionId(claim.ingestionId())
                    .documentType(DocumentType.COMPANY_POLICY)
                    .ownerUserId(null)
                    .accessLevel(claim.accessLevel())
                    .chunkIndex(chunk.index())
                    .chunkText(chunk.text())
                    .embedding(embeddings.get(chunk.index()))
                    .embeddingModel(claim.model())
                    .contentHash(KnowledgeApprovalService.sha256(chunk.text().getBytes(StandardCharsets.UTF_8)))
                    .indexedAt(now)
                    .build());
        }
        return records;
    }

    /** Deterministic, so a chunk written twice fails as a duplicate instead of being stored twice. */
    static String chunkId(String ingestionId, int chunkIndex) {
        return String.format(Locale.ROOT, "%s-%05d", ingestionId, chunkIndex);
    }

    private void write(Attempt attempt, List<KnowledgeChunk> records) {
        Claim claim = attempt.claim;
        for (int start = 0; start < records.size(); start += CHUNK_WRITE_BATCH_SIZE) {
            requireOwnership(claim);
            List<KnowledgeChunk> batch =
                    new ArrayList<>(records.subList(start, Math.min(start + CHUNK_WRITE_BATCH_SIZE, records.size())));
            attempt.writesStarted = true;
            try {
                knowledgeChunkRepository.insert(batch);
            } catch (RuntimeException failure) {
                throw chunkWriteFailure(claim, failure);
            }
        }
        long written;
        try {
            written = knowledgeChunkRepository.countByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(
                    claim.organizationId(), claim.knowledgeDocumentId(), claim.ingestionId());
        } catch (RuntimeException failure) {
            throw chunkWriteFailure(claim, failure);
        }
        if (written != records.size()) {
            log.warn("Knowledge document {} ingestion {} wrote {} of {} chunks",
                    claim.knowledgeDocumentId(), claim.ingestionId(), written, records.size());
            throw new Failed(KnowledgeIngestionFailure.CHUNK_WRITE_FAILED, null);
        }
    }

    private static Failed chunkWriteFailure(Claim claim, RuntimeException failure) {
        log.warn("Knowledge document {} ingestion {} could not write its chunks ({})",
                claim.knowledgeDocumentId(), claim.ingestionId(), failure.getClass().getSimpleName());
        return new Failed(KnowledgeIngestionFailure.CHUNK_WRITE_FAILED, null);
    }

    /** Updates the pending version only; the active version and sourceDocumentIds are left as they are. */
    private IngestionResult markIndexed(Claim claim, int chunkCount) {
        KnowledgeDocument current = requireOwnership(claim);
        KnowledgeDocumentVersion pending = current.getPendingVersion();
        pending.setStage(KnowledgeIngestionStage.INDEXED);
        pending.setChunkCount(chunkCount);
        pending.setEmbeddingModel(claim.model());
        pending.setEmbeddingDimensions(REQUIRED_DIMENSIONS);
        pending.setIndexedAt(LocalDateTime.now(clock));
        KnowledgeDocument saved;
        try {
            saved = knowledgeDocumentRepository.save(current);
        } catch (OptimisticLockingFailureException concurrentChange) {
            throw stoppedByCurrentState(claim);
        }
        log.info("Knowledge document {} ingestion {} indexed {} chunks; awaiting activation",
                claim.knowledgeDocumentId(), claim.ingestionId(), chunkCount);
        return new IngestionResult(saved, Outcome.INDEXED, claim.ingestionId(), chunkCount, null, true);
    }

    // --- Ownership, failure recording and cleanup ---------------------------------------------------

    /** The entry, if it is LIVE and its pending version is still this attempt at INGESTING; otherwise work stops. */
    private KnowledgeDocument requireOwnership(Claim claim) {
        KnowledgeDocument current = findEntry(claim.organizationId(), claim.knowledgeDocumentId()).orElse(null);
        if (current == null || current.getStatus() != KnowledgeDocumentStatus.LIVE) {
            throw new Stopped(Outcome.REVOKED, current);
        }
        if (!holdsAttempt(current, claim)) {
            throw new Stopped(Outcome.SUPERSEDED, current);
        }
        return current;
    }

    private Stopped stoppedByCurrentState(Claim claim) {
        KnowledgeDocument current = findEntry(claim.organizationId(), claim.knowledgeDocumentId()).orElse(null);
        boolean revoked = current == null || current.getStatus() != KnowledgeDocumentStatus.LIVE;
        return new Stopped(revoked ? Outcome.REVOKED : Outcome.SUPERSEDED, current);
    }

    private static boolean holdsAttempt(KnowledgeDocument entry, Claim claim) {
        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        return pending != null
                && claim.ingestionId().equals(pending.getIngestionId())
                && pending.getStage() == KnowledgeIngestionStage.INGESTING;
    }

    /**
     * Writes FAILED only while the entry is LIVE and still holds this attempt at INGESTING, under its
     * {@code @Version}. A conflict is not retried: whoever changed the entry owns its state now.
     */
    private Recorded recordFailure(Claim claim, Failed failed) {
        KnowledgeDocument current;
        try {
            current = findEntry(claim.organizationId(), claim.knowledgeDocumentId()).orElse(null);
        } catch (RuntimeException unreadable) {
            log.warn("Knowledge document {} ingestion {} failed ({}) but the failure could not be recorded ({})",
                    claim.knowledgeDocumentId(), claim.ingestionId(), failed.failure, unreadable.getClass().getSimpleName());
            return new Recorded(Outcome.FAILED, null);
        }
        if (current == null || current.getStatus() != KnowledgeDocumentStatus.LIVE) {
            return new Recorded(Outcome.REVOKED, current);
        }
        if (!holdsAttempt(current, claim)) {
            return new Recorded(Outcome.SUPERSEDED, current);
        }
        KnowledgeDocumentVersion pending = current.getPendingVersion();
        pending.setStage(KnowledgeIngestionStage.FAILED);
        pending.setFailureReason(failed.failure);
        pending.setExtractionFailureReason(failed.extractionReason);
        pending.setFailedAt(LocalDateTime.now(clock));
        try {
            KnowledgeDocument saved = knowledgeDocumentRepository.save(current);
            log.info("Knowledge document {} ingestion {} failed ({}{})", claim.knowledgeDocumentId(), claim.ingestionId(),
                    failed.failure, failed.extractionReason == null ? "" : ", " + failed.extractionReason);
            return new Recorded(Outcome.FAILED, saved);
        } catch (OptimisticLockingFailureException concurrentChange) {
            try {
                Stopped stopped = stoppedByCurrentState(claim);
                return new Recorded(stopped.outcome, stopped.document);
            } catch (RuntimeException unreadable) {
                return new Recorded(Outcome.SUPERSEDED, null);
            }
        } catch (RuntimeException unsaved) {
            log.warn("Knowledge document {} ingestion {} failed ({}) but the failure could not be recorded ({});"
                            + " it can be retried once its lease expires",
                    claim.knowledgeDocumentId(), claim.ingestionId(), failed.failure, unsaved.getClass().getSimpleName());
            return new Recorded(Outcome.FAILED, null);
        }
    }

    private boolean cleanupAttempt(Claim claim) {
        return knowledgeChunkCleaner.cleanObsoleteChunks(claim.organizationId(), claim.knowledgeDocumentId())
                .cleanupCompletedFor(claim.ingestionId());
    }

    // --- Helpers ------------------------------------------------------------------------------------

    /** Looked up within the given organization only, and re-checked in case the query ever widens. */
    private Optional<KnowledgeDocument> findEntry(String organizationId, String knowledgeDocumentId) {
        return knowledgeDocumentRepository.findByIdAndOrganizationId(knowledgeDocumentId, organizationId)
                .filter(found -> organizationId.equals(found.getOrganizationId()));
    }

    private static void requireKnowledgeManager(VerifiedMyHrActor actor) {
        if (actor == null) {
            throw new UnauthorizedException(AUTHENTICATION_REQUIRED);
        }
        if (!actor.canManageKnowledge()) {
            throw new ForbiddenException(NOT_A_KNOWLEDGE_MANAGER);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** What the claimed attempt works on, fixed at claim time. */
    private record Claim(String organizationId, String knowledgeDocumentId, String ingestionId, String model,
                         String sourceDocumentId, String sourceDocumentName, String contentSha256,
                         ChunkAccessLevel accessLevel, int pageCount, int pagesWithoutText,
                         String inspectionFindingsSha256, String indexableTextSha256,
                         String reviewablePassagesSha256, String obsoleteIngestionId) {
    }

    private static final class Attempt {
        private final Claim claim;
        private boolean writesStarted;

        private Attempt(Claim claim) {
            this.claim = claim;
        }
    }

    private record Recorded(Outcome outcome, KnowledgeDocument document) {
    }

    /** Control flow only: a classified failure to record on the version. Never leaves this class. */
    private static final class Failed extends RuntimeException {
        private final KnowledgeIngestionFailure failure;
        private final DocumentExtractionException.Reason extractionReason;

        private Failed(KnowledgeIngestionFailure failure, DocumentExtractionException.Reason extractionReason) {
            super(null, null, false, false);
            this.failure = failure;
            this.extractionReason = extractionReason;
        }
    }

    /** Control flow only: the entry was revoked or its pending version taken over. Never leaves this class. */
    private static final class Stopped extends RuntimeException {
        private final Outcome outcome;
        private final KnowledgeDocument document;

        private Stopped(Outcome outcome, KnowledgeDocument document) {
            super(null, null, false, false);
            this.outcome = outcome;
            this.document = document;
        }
    }
}
