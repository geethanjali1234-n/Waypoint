package com.waypoint.service;

import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.setup.KnowledgeChunkVectorSearch;
import com.waypoint.setup.KnowledgeChunkVectorSearch.CandidateChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retrieves MyHR knowledge base chunks that a verified actor may see, for use as LLM context.
 *
 * <p>Employees see COMPANY_WIDE knowledge; HR and company administrators also see HR_ONLY
 * knowledge. Atlas Vector Search pre-filters by organization and those access levels, and every
 * candidate is then re-validated against its LIVE KnowledgeDocument: a chunk serves only if it was
 * written by the entry's activated, indexed version with the configured embedding model. Pending,
 * superseded, revoked, cross-organization and malformed chunks are therefore never returned.
 *
 * <p>The feature is disabled unless explicitly enabled by configuration. Authorization failures
 * throw. Embedding, search and lookup failures return {@link RetrievalStatus#UNAVAILABLE} with no
 * chunks - never partially validated results. Logs carry the role, counts and exception class
 * names only: no question text, policy text, embeddings or driver messages.
 */
@Service
public class KnowledgeRetrievalService {

    static final String AUTHENTICATION_REQUIRED = "Authentication is required";
    static final String RETRIEVAL_NOT_PERMITTED = "This account cannot use the MyHR knowledge base";
    static final String QUESTION_REQUIRED = "A question is required";
    static final String QUESTION_TOO_LONG = "Questions must be 4000 characters or fewer";

    /** Matches MyHrChatRequest's message limit. */
    static final int MAX_QUESTION_CHARS = 4000;
    static final int MAX_RESULTS_LIMIT = 10;
    static final int MIN_CONTEXT_CHARS = 1000;
    static final int MAX_CONTEXT_CHARS_LIMIT = 24_000;
    /** No chunker configuration can produce a longer chunk, so a longer one is not trusted. */
    static final int MAX_CHUNK_TEXT_CHARS = DocumentChunkingService.MAX_MAX_CHUNK_CHARS;
    /** Over-fetch, because candidates from pending or superseded versions are discarded afterwards. */
    static final int CANDIDATE_OVERSAMPLING = 4;
    static final int NUM_CANDIDATES_PER_RESULT = 20;
    static final Duration MAX_LOOKUP_TIME = Duration.ofSeconds(5);

    private static final int REQUIRED_DIMENSIONS = KnowledgeChunkIntegrityCheck.REQUIRED_EMBEDDING_DIMENSIONS;
    private static final Set<ChunkAccessLevel> EMPLOYEE_ACCESS_LEVELS = Set.of(ChunkAccessLevel.COMPANY_WIDE);
    private static final Set<ChunkAccessLevel> KNOWLEDGE_MANAGER_ACCESS_LEVELS =
            Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalService.class);

    private final EmbeddingService embeddingService;
    private final KnowledgeChunkVectorSearch vectorSearch;
    private final MongoOperations mongoOperations;
    private final boolean retrievalEnabled;
    private final int maxResults;
    private final int maxContextChars;
    private final double minScore;
    /**
     * Candidates scoring more than this below the best accepted candidate are dropped, so excerpts far less relevant
     * than the best match (typically from a different policy) are not supplied alongside it. 0 disables it.
     */
    private final double relativeScoreMargin;

    /** Without a relative score margin. */
    public KnowledgeRetrievalService(
            EmbeddingService embeddingService,
            KnowledgeChunkVectorSearch vectorSearch,
            MongoOperations mongoOperations,
            boolean retrievalEnabled,
            int maxResults,
            int maxContextChars,
            double minScore) {
        this(embeddingService, vectorSearch, mongoOperations, retrievalEnabled, maxResults, maxContextChars, minScore, 0.0);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeRetrievalService(
            EmbeddingService embeddingService,
            KnowledgeChunkVectorSearch vectorSearch,
            MongoOperations mongoOperations,
            @Value("${waypoint.myhr.knowledge.retrieval.enabled:false}") boolean retrievalEnabled,
            @Value("${waypoint.myhr.knowledge.retrieval.max-results:5}") int maxResults,
            @Value("${waypoint.myhr.knowledge.retrieval.max-context-chars:8000}") int maxContextChars,
            @Value("${waypoint.myhr.knowledge.retrieval.min-score:0.0}") double minScore,
            @Value("${waypoint.myhr.knowledge.retrieval.relative-score-margin:0.0}") double relativeScoreMargin) {
        if (!Double.isFinite(relativeScoreMargin) || relativeScoreMargin < 0.0 || relativeScoreMargin > 1.0) {
            throw new IllegalArgumentException("Knowledge retrieval relative-score-margin must be between 0 and 1");
        }
        this.relativeScoreMargin = relativeScoreMargin;
        if (maxResults < 1 || maxResults > MAX_RESULTS_LIMIT) {
            throw new IllegalArgumentException("Knowledge retrieval max-results must be between 1 and "
                    + MAX_RESULTS_LIMIT);
        }
        if (maxContextChars < MIN_CONTEXT_CHARS || maxContextChars > MAX_CONTEXT_CHARS_LIMIT) {
            throw new IllegalArgumentException("Knowledge retrieval max-context-chars must be between "
                    + MIN_CONTEXT_CHARS + " and " + MAX_CONTEXT_CHARS_LIMIT);
        }
        if (!Double.isFinite(minScore) || minScore < 0.0 || minScore > 1.0) {
            throw new IllegalArgumentException("Knowledge retrieval min-score must be between 0 and 1");
        }
        this.embeddingService = embeddingService;
        this.vectorSearch = vectorSearch;
        this.mongoOperations = mongoOperations;
        this.retrievalEnabled = retrievalEnabled;
        this.maxResults = maxResults;
        this.maxContextChars = maxContextChars;
        this.minScore = minScore;
    }

    /**
     * Returns at most max-results validated chunks, ordered by relevance, whose combined text fits
     * within max-context-chars. The organization and access levels always come from the actor.
     */
    public RetrievalResult retrieve(VerifiedMyHrActor actor, String question) {
        Set<ChunkAccessLevel> allowedAccessLevels = allowedAccessLevels(actor);
        if (!retrievalEnabled) {
            return RetrievalResult.empty(RetrievalStatus.DISABLED, 0);
        }
        String query = requireQuestion(question);
        String organizationId = actor.organizationId();
        StageClock clock = new StageClock();

        String model = configuredModel();
        if (model == null) {
            return RetrievalResult.empty(RetrievalStatus.UNAVAILABLE, 0, clock.timings());
        }
        clock.start();
        List<Double> queryVector = embedQuery(query);
        clock.embedded();
        if (queryVector == null) {
            return RetrievalResult.empty(RetrievalStatus.UNAVAILABLE, 0, clock.timings());
        }

        int searchLimit = Math.min(maxResults * CANDIDATE_OVERSAMPLING, KnowledgeChunkVectorSearch.MAX_LIMIT);
        int numCandidates = Math.min(searchLimit * NUM_CANDIDATES_PER_RESULT, KnowledgeChunkVectorSearch.MAX_NUM_CANDIDATES);
        List<CandidateChunk> candidates;
        try {
            candidates = vectorSearch.search(new KnowledgeChunkVectorSearch.SearchRequest(
                    organizationId, allowedAccessLevels, queryVector, searchLimit, numCandidates));
        } catch (RuntimeException unavailable) {
            // Never include a driver message: it can contain connection details.
            log.warn("Knowledge vector search was unavailable ({})", unavailable.getClass().getSimpleName());
            clock.searched();
            return RetrievalResult.empty(RetrievalStatus.UNAVAILABLE, 0, clock.timings());
        }
        clock.searched();
        if (candidates == null || candidates.isEmpty()) {
            return RetrievalResult.empty(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, 0, clock.timings());
        }

        Map<String, KnowledgeDocument> liveEntries;
        try {
            liveEntries = loadLiveEntries(organizationId, candidates);
        } catch (RuntimeException unavailable) {
            log.warn("Knowledge documents could not be verified for retrieval ({})",
                    unavailable.getClass().getSimpleName());
            clock.validated();
            return RetrievalResult.empty(RetrievalStatus.UNAVAILABLE, candidates.size(), clock.timings());
        }

        List<RetrievedChunk> accepted = select(candidates, liveEntries, organizationId, allowedAccessLevels, model);
        clock.validated();
        log.debug("Knowledge retrieval for {} accepted {} of {} candidates", actor.role(), accepted.size(),
                candidates.size());
        return accepted.isEmpty()
                ? RetrievalResult.empty(RetrievalStatus.NO_RELEVANT_KNOWLEDGE, candidates.size(), clock.timings())
                : new RetrievalResult(RetrievalStatus.RETRIEVED, accepted, candidates.size(), clock.timings());
    }

    /** Measures the retrieval stages in order; a stage that did not run stays unmeasured. */
    private static final class StageClock {
        private long mark;
        private long embeddingNanos = StageTimings.NOT_MEASURED;
        private long vectorSearchNanos = StageTimings.NOT_MEASURED;
        private long validationNanos = StageTimings.NOT_MEASURED;

        void start() {
            mark = System.nanoTime();
        }

        void embedded() {
            embeddingNanos = lap();
        }

        void searched() {
            vectorSearchNanos = lap();
        }

        void validated() {
            validationNanos = lap();
        }

        private long lap() {
            long now = System.nanoTime();
            long elapsed = Math.max(0, now - mark);
            mark = now;
            return elapsed;
        }

        StageTimings timings() {
            return new StageTimings(embeddingNanos, vectorSearchNanos, validationNanos);
        }
    }

    /** The only mapping from roles to knowledge access; every other role is refused. */
    static Set<ChunkAccessLevel> allowedAccessLevels(VerifiedMyHrActor actor) {
        if (actor == null) {
            throw new UnauthorizedException(AUTHENTICATION_REQUIRED);
        }
        if (actor.canManageKnowledge()) {
            return KNOWLEDGE_MANAGER_ACCESS_LEVELS;
        }
        if (actor.role() == UserRole.EMPLOYEE) {
            return EMPLOYEE_ACCESS_LEVELS;
        }
        throw new ForbiddenException(RETRIEVAL_NOT_PERMITTED);
    }

    private static String requireQuestion(String question) {
        String query = question == null ? "" : question.strip();
        if (query.isEmpty()) {
            throw new BusinessException(QUESTION_REQUIRED);
        }
        if (query.length() > MAX_QUESTION_CHARS) {
            throw new BusinessException(QUESTION_TOO_LONG);
        }
        return query;
    }

    private String configuredModel() {
        try {
            String model = embeddingService.model();
            if (isBlank(model) || embeddingService.dimensions() != REQUIRED_DIMENSIONS) {
                log.warn("Knowledge retrieval embedding configuration does not match the vector index");
                return null;
            }
            return model;
        } catch (RuntimeException unavailable) {
            log.warn("Knowledge retrieval embedding configuration could not be read ({})",
                    unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private List<Double> embedQuery(String query) {
        List<Double> vector;
        try {
            vector = embeddingService.embed(query, EmbeddingService.InputType.QUERY);
        } catch (EmbeddingService.EmbeddingRateLimitedException rateLimited) {
            // No retry: the caller reports knowledge as unavailable, exactly as for any other embedding failure.
            log.warn("Knowledge retrieval query embedding was rate limited by the embedding provider (HTTP 429)");
            return null;
        } catch (RuntimeException unavailable) {
            log.warn("Knowledge retrieval query embedding was unavailable ({})", unavailable.getClass().getSimpleName());
            return null;
        }
        if (vector == null || vector.size() != REQUIRED_DIMENSIONS) {
            log.warn("Knowledge retrieval query embedding had an unexpected shape");
            return null;
        }
        boolean nonZero = false;
        for (Double value : vector) {
            if (value == null || !Double.isFinite(value)) {
                log.warn("Knowledge retrieval query embedding had an unexpected shape");
                return null;
            }
            nonZero |= value != 0.0;
        }
        if (!nonZero) {
            log.warn("Knowledge retrieval query embedding had an unexpected shape");
            return null;
        }
        return List.copyOf(vector);
    }

    /**
     * One organization-scoped, LIVE-only lookup for the candidates' entries. The projection omits
     * version history and the pending version, which retrieval never needs.
     */
    private Map<String, KnowledgeDocument> loadLiveEntries(String organizationId, List<CandidateChunk> candidates) {
        Set<String> ids = new LinkedHashSet<>();
        candidates.forEach(candidate -> ids.add(candidate.knowledgeDocumentId()));
        Query query = new Query(Criteria.where("organizationId").is(organizationId)
                .and("status").is(KnowledgeDocumentStatus.LIVE)
                .and("id").in(ids))
                .limit(ids.size())
                .maxTime(MAX_LOOKUP_TIME);
        query.fields().include("organizationId", "title", "status", "activeVersion", "sourceDocumentIds");

        List<KnowledgeDocument> found = mongoOperations.find(query, KnowledgeDocument.class);
        Map<String, KnowledgeDocument> byId = new HashMap<>();
        if (found == null) {
            return byId;
        }
        for (KnowledgeDocument entry : found) {
            if (entry != null && entry.getId() != null && ids.contains(entry.getId())
                    && organizationId.equals(entry.getOrganizationId())
                    && entry.getStatus() == KnowledgeDocumentStatus.LIVE) {
                byId.putIfAbsent(entry.getId(), entry);
            }
        }
        return byId;
    }

    private List<RetrievedChunk> select(
            List<CandidateChunk> candidates,
            Map<String, KnowledgeDocument> liveEntries,
            String organizationId,
            Set<ChunkAccessLevel> allowedAccessLevels,
            String model) {
        List<CandidateChunk> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(CandidateChunk::score).reversed().thenComparing(CandidateChunk::id));

        List<RetrievedChunk> accepted = new ArrayList<>();
        Set<String> acceptedIds = new LinkedHashSet<>();
        int contextChars = 0;
        double bestScore = Double.NaN;
        for (CandidateChunk candidate : ranked) {
            if (accepted.size() == maxResults) {
                break;
            }
            KnowledgeDocument entry = liveEntries.get(candidate.knowledgeDocumentId());
            if (!isServable(candidate, entry, organizationId, allowedAccessLevels, model)
                    || candidate.score() < minScore
                    || acceptedIds.contains(candidate.id())) {
                continue;
            }
            // Ranked best first, so the first servable candidate sets the bar for the rest.
            if (relativeScoreMargin > 0.0 && !Double.isNaN(bestScore) && candidate.score() < bestScore - relativeScoreMargin) {
                continue;
            }
            int length = candidate.chunkText().length();
            if (contextChars + length > maxContextChars) {
                continue;
            }
            contextChars += length;
            if (Double.isNaN(bestScore)) bestScore = candidate.score();
            acceptedIds.add(candidate.id());
            accepted.add(RetrievedChunk.of(candidate, entry));
        }
        return List.copyOf(accepted);
    }

    /**
     * A candidate serves only when it is exactly a chunk of its LIVE entry's activated version:
     * same organization, ingestion, source document, access level and embedding model.
     */
    static boolean isServable(
            CandidateChunk candidate,
            KnowledgeDocument entry,
            String organizationId,
            Set<ChunkAccessLevel> allowedAccessLevels,
            String model) {
        if (candidate == null || entry == null
                || !organizationId.equals(candidate.organizationId())
                || !allowedAccessLevels.contains(candidate.accessLevel())
                || candidate.documentType() != DocumentType.COMPANY_POLICY
                || !model.equals(candidate.embeddingModel())
                || candidate.chunkIndex() < 0
                || isBlank(candidate.chunkText())
                || candidate.chunkText().length() > MAX_CHUNK_TEXT_CHARS
                || !KnowledgeIngestionService.chunkId(candidate.ingestionId(), candidate.chunkIndex()).equals(candidate.id())) {
            return false;
        }
        if (!organizationId.equals(entry.getOrganizationId())
                || entry.getStatus() != KnowledgeDocumentStatus.LIVE
                || !candidate.knowledgeDocumentId().equals(entry.getId())) {
            return false;
        }
        KnowledgeDocumentVersion active = entry.getActiveVersion();
        List<String> sourceDocumentIds = entry.getSourceDocumentIds();
        return active != null
                && candidate.ingestionId().equals(active.getIngestionId())
                && active.getStage() == KnowledgeIngestionStage.INDEXED
                && active.getActivatedAt() != null
                && !isBlank(active.getActivatedByUserId())
                && active.getDocumentType() == DocumentType.COMPANY_POLICY
                && active.getAccessLevel() == candidate.accessLevel()
                && candidate.sourceDocumentId().equals(active.getSourceDocumentId())
                && sourceDocumentIds != null && sourceDocumentIds.contains(active.getSourceDocumentId())
                && model.equals(active.getEmbeddingModel())
                && active.getEmbeddingDimensions() != null && active.getEmbeddingDimensions() == REQUIRED_DIMENSIONS
                && active.getChunkCount() != null && candidate.chunkIndex() < active.getChunkCount()
                && active.getFailureReason() == null
                && active.getExtractionFailureReason() == null
                && active.getFailedAt() == null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public enum RetrievalStatus {
        /** At least one validated chunk was returned. */
        RETRIEVED,
        /** The search ran, but no candidate passed validation and relevance checks. */
        NO_RELEVANT_KNOWLEDGE,
        /** Embedding, search or validation infrastructure failed; nothing may be used as context. */
        UNAVAILABLE,
        /** Retrieval is switched off by configuration. */
        DISABLED
    }

    /**
     * Chunks are non-empty only for RETRIEVED. candidateCount is the number of vector search hits
     * considered, for diagnostics; it reveals nothing about content.
     */
    public record RetrievalResult(RetrievalStatus status, List<RetrievedChunk> chunks, int candidateCount,
                                  StageTimings timings) {

        public RetrievalResult {
            chunks = chunks == null ? List.of() : List.copyOf(chunks);
            timings = timings == null ? StageTimings.UNMEASURED : timings;
        }

        /** A result whose stages were not measured. */
        public RetrievalResult(RetrievalStatus status, List<RetrievedChunk> chunks, int candidateCount) {
            this(status, chunks, candidateCount, StageTimings.UNMEASURED);
        }

        static RetrievalResult empty(RetrievalStatus status, int candidateCount) {
            return new RetrievalResult(status, List.of(), candidateCount);
        }

        static RetrievalResult empty(RetrievalStatus status, int candidateCount, StageTimings timings) {
            return new RetrievalResult(status, List.of(), candidateCount, timings);
        }

        public boolean hasKnowledge() {
            return status == RetrievalStatus.RETRIEVED && !chunks.isEmpty();
        }
    }

    /**
     * Elapsed time of each retrieval stage in nanoseconds, or {@link #NOT_MEASURED} for a stage that did not run:
     * the query embedding (Voyage), the Atlas vector search, and the validation of candidates against their LIVE,
     * activated knowledge document version. Durations only; never content.
     */
    public record StageTimings(long embeddingNanos, long vectorSearchNanos, long validationNanos) {

        public static final long NOT_MEASURED = -1;
        public static final StageTimings UNMEASURED = new StageTimings(NOT_MEASURED, NOT_MEASURED, NOT_MEASURED);
    }

    /**
     * A validated chunk plus the metadata needed to cite it. Title, source name and version come
     * from the LIVE entry's active version, not from the chunk row.
     */
    public record RetrievedChunk(
            String chunkId,
            String knowledgeDocumentId,
            String title,
            String sourceDocumentId,
            String sourceDocumentName,
            String ingestionId,
            int chunkIndex,
            ChunkAccessLevel accessLevel,
            LocalDateTime versionActivatedAt,
            double score,
            String text,
            /*
             * The organization of the LIVE entry this chunk was validated against. Consumers re-check it against the
             * verified actor (defense in depth beyond the search filter and live-entry validation). Null only for a
             * chunk built without it (the compatibility constructor), i.e. organization unknown.
             */
            String organizationId) {

        public RetrievedChunk(String chunkId, String knowledgeDocumentId, String title, String sourceDocumentId,
                              String sourceDocumentName, String ingestionId, int chunkIndex, ChunkAccessLevel accessLevel,
                              LocalDateTime versionActivatedAt, double score, String text) {
            this(chunkId, knowledgeDocumentId, title, sourceDocumentId, sourceDocumentName, ingestionId, chunkIndex,
                    accessLevel, versionActivatedAt, score, text, null);
        }

        static RetrievedChunk of(CandidateChunk candidate, KnowledgeDocument entry) {
            KnowledgeDocumentVersion active = entry.getActiveVersion();
            return new RetrievedChunk(
                    candidate.id(),
                    entry.getId(),
                    entry.getTitle(),
                    active.getSourceDocumentId(),
                    active.getSourceDocumentName(),
                    active.getIngestionId(),
                    candidate.chunkIndex(),
                    candidate.accessLevel(),
                    active.getActivatedAt(),
                    candidate.score(),
                    candidate.chunkText(),
                    entry.getOrganizationId());
        }

        /** Policy text stays out of logs. */
        @Override
        public String toString() {
            return "RetrievedChunk[chunkId=" + chunkId + ", knowledgeDocumentId=" + knowledgeDocumentId
                    + ", chunkIndex=" + chunkIndex + ", accessLevel=" + accessLevel + ", score=" + score + "]";
        }
    }
}
