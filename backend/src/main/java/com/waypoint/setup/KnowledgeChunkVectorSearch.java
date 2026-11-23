package com.waypoint.setup;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Read-only Atlas Vector Search over knowledge_chunks, pre-filtered by organization and access level.
 *
 * <p>Rows returned here are only candidates. The vector index has no knowledgeDocumentId or
 * ingestionId filter fields, so a candidate may belong to a pending, superseded or revoked version;
 * callers must validate every candidate against its LIVE KnowledgeDocument before using it.
 *
 * <p>The projection never includes embeddings. Rows that do not have the expected shape are
 * dropped rather than repaired. Driver exceptions propagate unchanged and must not be logged with
 * their messages, which can contain connection details.
 */
@Component
@RequiredArgsConstructor
public final class KnowledgeChunkVectorSearch {

    public static final int MAX_LIMIT = 50;
    public static final int MAX_NUM_CANDIDATES = 1000;
    static final long MAX_TIME_MS = 5_000;

    /** EMPLOYEE_PRIVATE chunks would also need an owner filter, which this search does not apply. */
    static final Set<ChunkAccessLevel> SEARCHABLE_ACCESS_LEVELS =
            EnumSet.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);

    private final MongoOperations mongoOperations;

    public List<CandidateChunk> search(SearchRequest request) {
        requireValid(request);
        List<Document> rows = mongoOperations.getCollection(KnowledgeChunkVectorIndexSetup.COLLECTION_NAME)
                .aggregate(vectorSearchPipeline(request))
                .maxTime(MAX_TIME_MS, TimeUnit.MILLISECONDS)
                .into(new ArrayList<>());
        if (rows == null) {
            return List.of();
        }
        List<CandidateChunk> candidates = new ArrayList<>(Math.min(rows.size(), request.limit()));
        for (Document row : rows) {
            if (candidates.size() == request.limit()) {
                break;
            }
            CandidateChunk candidate = asCandidate(row);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    static List<Document> vectorSearchPipeline(SearchRequest request) {
        List<String> accessLevels = request.accessLevels().stream().map(Enum::name).sorted().toList();
        Document filter = new Document("organizationId", request.organizationId())
                .append("accessLevel", new Document("$in", accessLevels));
        Document vectorSearch = new Document("index", KnowledgeChunkVectorIndexSetup.INDEX_NAME)
                .append("path", KnowledgeChunkVectorIndexSetup.EMBEDDING_PATH)
                .append("queryVector", request.queryVector())
                .append("numCandidates", request.numCandidates())
                .append("limit", request.limit())
                .append("filter", filter);
        Document projection = new Document("_id", 1)
                .append("organizationId", 1)
                .append("knowledgeDocumentId", 1)
                .append("ingestionId", 1)
                .append("sourceDocumentId", 1)
                .append("documentType", 1)
                .append("accessLevel", 1)
                .append("chunkIndex", 1)
                .append("chunkText", 1)
                .append("embeddingModel", 1)
                .append("score", new Document("$meta", "vectorSearchScore"));
        return List.of(
                new Document("$vectorSearch", vectorSearch),
                new Document("$project", projection));
    }

    private static void requireValid(SearchRequest request) {
        if (request == null || isBlank(request.organizationId())) {
            throw new IllegalArgumentException("A vector search requires an organization");
        }
        if (request.accessLevels() == null || request.accessLevels().isEmpty()
                || !SEARCHABLE_ACCESS_LEVELS.containsAll(request.accessLevels())) {
            throw new IllegalArgumentException("A vector search requires supported access levels");
        }
        if (request.limit() < 1 || request.limit() > MAX_LIMIT
                || request.numCandidates() < request.limit() || request.numCandidates() > MAX_NUM_CANDIDATES) {
            throw new IllegalArgumentException("A vector search requires bounded result limits");
        }
        List<Double> vector = request.queryVector();
        if (vector == null || vector.size() != KnowledgeChunkVectorIndexSetup.NUM_DIMENSIONS) {
            throw new IllegalArgumentException("A vector search requires a query vector of the indexed size");
        }
        for (Double value : vector) {
            if (value == null || !Double.isFinite(value)) {
                throw new IllegalArgumentException("A vector search requires a finite query vector");
            }
        }
    }

    private static CandidateChunk asCandidate(Document row) {
        if (row == null) {
            return null;
        }
        String id = stringValue(row.get("_id"));
        String organizationId = stringValue(row.get("organizationId"));
        String knowledgeDocumentId = stringValue(row.get("knowledgeDocumentId"));
        String ingestionId = stringValue(row.get("ingestionId"));
        String sourceDocumentId = stringValue(row.get("sourceDocumentId"));
        String chunkText = stringValue(row.get("chunkText"));
        String embeddingModel = stringValue(row.get("embeddingModel"));
        DocumentType documentType = enumValue(DocumentType.class, row.get("documentType"));
        ChunkAccessLevel accessLevel = enumValue(ChunkAccessLevel.class, row.get("accessLevel"));
        Integer chunkIndex = integerValue(row.get("chunkIndex"));
        Double score = scoreValue(row.get("score"));
        if (id == null || organizationId == null || knowledgeDocumentId == null || ingestionId == null
                || sourceDocumentId == null || chunkText == null || embeddingModel == null
                || documentType == null || accessLevel == null || chunkIndex == null || score == null) {
            return null;
        }
        return new CandidateChunk(id, organizationId, knowledgeDocumentId, ingestionId, sourceDocumentId,
                documentType, accessLevel, chunkIndex, chunkText, embeddingModel, score);
    }

    private static String stringValue(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, Object value) {
        if (!(value instanceof String name)) {
            return null;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Integer integerValue(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal) || decimal < 0 || decimal > Integer.MAX_VALUE) {
            return null;
        }
        return (int) decimal;
    }

    /** Cosine vectorSearchScore is normalized to [0, 1]; anything else is not a trustworthy result. */
    private static Double scoreValue(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double score = number.doubleValue();
        return Double.isFinite(score) && score >= 0.0 && score <= 1.0 ? score : null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record SearchRequest(
            String organizationId,
            Set<ChunkAccessLevel> accessLevels,
            List<Double> queryVector,
            int limit,
            int numCandidates) {

        /** Defensive copies that keep null elements, so validation rejects them explicitly. */
        public SearchRequest {
            accessLevels = accessLevels == null ? null : Collections.unmodifiableSet(new HashSet<>(accessLevels));
            queryVector = queryVector == null ? null : Collections.unmodifiableList(new ArrayList<>(queryVector));
        }

        /** The query vector and organization stay out of logs. */
        @Override
        public String toString() {
            return "SearchRequest[accessLevels=" + accessLevels + ", limit=" + limit
                    + ", numCandidates=" + numCandidates + "]";
        }
    }

    /** One vector search hit; unvalidated until checked against its LIVE KnowledgeDocument. */
    public record CandidateChunk(
            String id,
            String organizationId,
            String knowledgeDocumentId,
            String ingestionId,
            String sourceDocumentId,
            DocumentType documentType,
            ChunkAccessLevel accessLevel,
            int chunkIndex,
            String chunkText,
            String embeddingModel,
            double score) {

        /** Policy text stays out of logs. */
        @Override
        public String toString() {
            return "CandidateChunk[id=" + id + ", accessLevel=" + accessLevel + ", chunkIndex=" + chunkIndex
                    + ", score=" + score + "]";
        }
    }
}
