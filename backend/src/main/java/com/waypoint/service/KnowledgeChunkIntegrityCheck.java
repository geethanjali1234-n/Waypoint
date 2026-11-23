package com.waypoint.service;

import com.mongodb.client.MongoCollection;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only validation of one ingestion's persisted chunks before a future activation step.
 *
 * <p>The aggregation deliberately omits chunk text, source names, content hashes, and embedding
 * values. It asks MongoDB only for the embedding array size. Any malformed response or query
 * failure is reported as not verified; callers must never treat that outcome as safe to activate.
 */
@Component
@RequiredArgsConstructor
public final class KnowledgeChunkIntegrityCheck {

    static final int REQUIRED_EMBEDDING_DIMENSIONS = 1024;
    static final int MAX_SUPPORTED_CHUNKS = 1000;
    private static final String COLLECTION_NAME = "knowledge_chunks";

    private final MongoOperations mongoOperations;

    public IntegrityCheckResult check(Expectation expected) {
        if (!isValid(expected)) {
            return failed(0, 0, IntegrityViolation.INVALID_EXPECTATION);
        }

        List<Document> chunks;
        try {
            chunks = mongoOperations.getCollection(COLLECTION_NAME)
                    .aggregate(integrityPipeline(expected))
                    .into(new ArrayList<>());
        } catch (RuntimeException ignored) {
            // Driver details can expose connection information. The result is intentionally opaque.
            return failed(expected.expectedChunkCount(), 0, IntegrityViolation.QUERY_UNAVAILABLE);
        }
        if (chunks == null) {
            return failed(expected.expectedChunkCount(), 0, IntegrityViolation.MALFORMED_CHUNK);
        }

        Set<IntegrityViolation> violations = EnumSet.noneOf(IntegrityViolation.class);
        Set<String> expectedIds = new HashSet<>();
        Set<String> observedIds = new HashSet<>();
        Set<Integer> observedIndexes = new HashSet<>();

        for (int index = 0; index < expected.expectedChunkCount(); index++) {
            expectedIds.add(chunkId(expected.ingestionId(), index));
        }

        for (Document chunk : chunks) {
            if (chunk == null) {
                violations.add(IntegrityViolation.MALFORMED_CHUNK);
                continue;
            }

            String id = stringValue(chunk.get("_id"));
            Integer chunkIndex = integerValue(chunk.get("chunkIndex"));
            if (id == null || chunkIndex == null) {
                violations.add(IntegrityViolation.MALFORMED_CHUNK);
                continue;
            }
            if (!observedIds.add(id)) {
                violations.add(IntegrityViolation.DUPLICATE_CHUNK_ID);
            }
            if (!observedIndexes.add(chunkIndex)) {
                violations.add(IntegrityViolation.DUPLICATE_CHUNK_INDEX);
            }
            if (!expectedIds.contains(id)) {
                violations.add(IntegrityViolation.UNEXPECTED_CHUNK_ID);
                violations.add(IntegrityViolation.EXTRA_CHUNK);
            }
            if (chunkIndex < 0 || chunkIndex >= expected.expectedChunkCount()) {
                violations.add(IntegrityViolation.UNEXPECTED_CHUNK_INDEX);
                violations.add(IntegrityViolation.EXTRA_CHUNK);
            } else if (!chunkId(expected.ingestionId(), chunkIndex).equals(id)) {
                violations.add(IntegrityViolation.UNEXPECTED_CHUNK_ID);
            }
            if (!expected.sourceDocumentId().equals(stringValue(chunk.get("sourceDocumentId")))) {
                violations.add(IntegrityViolation.SOURCE_DOCUMENT_MISMATCH);
            }
            if (!expected.documentType().name().equals(stringValue(chunk.get("documentType")))) {
                violations.add(IntegrityViolation.DOCUMENT_TYPE_MISMATCH);
            }
            if (!expected.accessLevel().name().equals(stringValue(chunk.get("accessLevel")))) {
                violations.add(IntegrityViolation.ACCESS_LEVEL_MISMATCH);
            }
            if (!expected.embeddingModel().equals(stringValue(chunk.get("embeddingModel")))) {
                violations.add(IntegrityViolation.EMBEDDING_MODEL_MISMATCH);
            }
            if (!hasRequiredDimension(chunk.get("embeddingDimension"))) {
                violations.add(IntegrityViolation.EMBEDDING_DIMENSION_MISMATCH);
            }
        }

        if (chunks.size() != expected.expectedChunkCount()) {
            violations.add(IntegrityViolation.COUNT_MISMATCH);
        }
        if (!observedIds.containsAll(expectedIds) || !observedIndexes.containsAll(expectedIndexes(expected.expectedChunkCount()))) {
            violations.add(IntegrityViolation.MISSING_CHUNK);
        }
        if (chunks.size() > expected.expectedChunkCount()) {
            violations.add(IntegrityViolation.EXTRA_CHUNK);
        }

        return violations.isEmpty()
                ? new IntegrityCheckResult(IntegrityOutcome.VERIFIED, expected.expectedChunkCount(), chunks.size(), Set.of())
                : new IntegrityCheckResult(IntegrityOutcome.NOT_VERIFIED, expected.expectedChunkCount(), chunks.size(), violations);
    }

    static List<Document> integrityPipeline(Expectation expected) {
        Document match = new Document("organizationId", expected.organizationId())
                .append("knowledgeDocumentId", expected.knowledgeDocumentId())
                .append("ingestionId", expected.ingestionId());
        Document embeddingDimension = new Document("$cond", List.of(
                new Document("$isArray", "$embedding"),
                new Document("$size", "$embedding"),
                -1));
        Document projection = new Document("_id", 1)
                .append("chunkIndex", 1)
                .append("sourceDocumentId", 1)
                .append("documentType", 1)
                .append("accessLevel", 1)
                .append("embeddingModel", 1)
                .append("embeddingDimension", embeddingDimension);
        return List.of(
                new Document("$match", match),
                new Document("$project", projection),
                new Document("$sort", new Document("chunkIndex", 1).append("_id", 1)));
    }

    private static boolean isValid(Expectation expected) {
        return expected != null
                && isNonBlank(expected.organizationId())
                && isNonBlank(expected.knowledgeDocumentId())
                && isNonBlank(expected.ingestionId())
                && expected.expectedChunkCount() > 0
                && expected.expectedChunkCount() <= MAX_SUPPORTED_CHUNKS
                && isNonBlank(expected.sourceDocumentId())
                && expected.documentType() != null
                && expected.accessLevel() != null
                && isNonBlank(expected.embeddingModel());
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static Set<Integer> expectedIndexes(int count) {
        Set<Integer> indexes = new HashSet<>();
        for (int index = 0; index < count; index++) {
            indexes.add(index);
        }
        return indexes;
    }

    private static String chunkId(String ingestionId, int chunkIndex) {
        return String.format(Locale.ROOT, "%s-%05d", ingestionId, chunkIndex);
    }

    private static String stringValue(Object value) {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static Integer integerValue(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)
                || decimal < Integer.MIN_VALUE || decimal > Integer.MAX_VALUE) {
            return null;
        }
        return (int) decimal;
    }

    private static boolean hasRequiredDimension(Object value) {
        return value instanceof Number number && number.doubleValue() == REQUIRED_EMBEDDING_DIMENSIONS;
    }

    private static IntegrityCheckResult failed(int expectedCount, int observedCount, IntegrityViolation violation) {
        return new IntegrityCheckResult(IntegrityOutcome.NOT_VERIFIED, expectedCount, observedCount, Set.of(violation));
    }

    public record Expectation(
            String organizationId,
            String knowledgeDocumentId,
            String ingestionId,
            int expectedChunkCount,
            String sourceDocumentId,
            DocumentType documentType,
            ChunkAccessLevel accessLevel,
            String embeddingModel) {
    }

    public record IntegrityCheckResult(
            IntegrityOutcome outcome,
            int expectedChunkCount,
            int observedChunkCount,
            Set<IntegrityViolation> violations) {

        public IntegrityCheckResult {
            violations = violations == null ? Set.of() : Set.copyOf(violations);
        }

        public boolean verified() {
            return outcome == IntegrityOutcome.VERIFIED;
        }
    }

    public enum IntegrityOutcome {
        VERIFIED,
        NOT_VERIFIED
    }

    public enum IntegrityViolation {
        INVALID_EXPECTATION,
        QUERY_UNAVAILABLE,
        MALFORMED_CHUNK,
        COUNT_MISMATCH,
        MISSING_CHUNK,
        EXTRA_CHUNK,
        DUPLICATE_CHUNK_ID,
        DUPLICATE_CHUNK_INDEX,
        UNEXPECTED_CHUNK_ID,
        UNEXPECTED_CHUNK_INDEX,
        SOURCE_DOCUMENT_MISMATCH,
        ACCESS_LEVEL_MISMATCH,
        DOCUMENT_TYPE_MISMATCH,
        EMBEDDING_MODEL_MISMATCH,
        EMBEDDING_DIMENSION_MISMATCH
    }
}
