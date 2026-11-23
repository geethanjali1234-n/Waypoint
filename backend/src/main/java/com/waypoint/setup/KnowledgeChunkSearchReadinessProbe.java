package com.waypoint.setup;

import com.mongodb.client.MongoCollection;
import com.waypoint.enums.ChunkAccessLevel;
import lombok.RequiredArgsConstructor;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only, sampled check that Atlas Vector Search can discover representative chunks.
 *
 * <p>{@link SearchReadinessOutcome#READY} means every deterministic sample was discoverable; it
 * does not prove that every chunk has been indexed. In particular, the current vector index has
 * no knowledgeDocumentId or ingestionId filter fields, so unrelated chunks in the same
 * organization and access level can cause a conservative false negative.
 */
@Component
@RequiredArgsConstructor
public final class KnowledgeChunkSearchReadinessProbe {

    static final int MAX_SAMPLES = 8;
    static final int MAX_SUPPORTED_CHUNKS = 1000;
    static final int SEARCH_RESULT_LIMIT = 100;

    private final MongoOperations mongoOperations;

    public SearchReadinessReport probe(ProbeRequest request) {
        if (!isValid(request)) {
            return awaiting(0, 0);
        }

        MongoCollection<Document> collection;
        try {
            collection = mongoOperations.getCollection(KnowledgeChunkVectorIndexSetup.COLLECTION_NAME);
            Document index = collection.listSearchIndexes()
                    .name(KnowledgeChunkVectorIndexSetup.INDEX_NAME)
                    .first();
            if (!isReadyIndex(index)) {
                return awaiting(0, 0);
            }
        } catch (RuntimeException ignored) {
            return awaiting(0, 0);
        }

        List<Integer> sampleIndexes = sampleIndexes(request.expectedChunkCount());
        List<SampleChunk> samples;
        try {
            samples = readSamples(collection, request, sampleIndexes);
        } catch (RuntimeException ignored) {
            return awaiting(sampleIndexes.size(), 0);
        }
        if (samples == null || samples.size() != sampleIndexes.size()) {
            return awaiting(sampleIndexes.size(), 0);
        }

        int discoverable = 0;
        for (SampleChunk sample : samples) {
            try {
                List<Document> matches = collection.aggregate(vectorSearchPipeline(request, sample.embedding()))
                        .into(new ArrayList<>());
                if (matches == null || !containsOnlyExpectedIds(matches, sample.id())) {
                    return awaiting(sampleIndexes.size(), discoverable);
                }
                discoverable++;
            } catch (RuntimeException ignored) {
                return awaiting(sampleIndexes.size(), discoverable);
            }
        }
        return new SearchReadinessReport(SearchReadinessOutcome.READY, sampleIndexes.size(), discoverable);
    }

    static List<Integer> sampleIndexes(int expectedChunkCount) {
        if (expectedChunkCount <= 0) {
            return List.of();
        }
        int sampleCount = Math.min(expectedChunkCount, MAX_SAMPLES);
        List<Integer> indexes = new ArrayList<>(sampleCount);
        if (sampleCount == 1) {
            return List.of(0);
        }
        for (int position = 0; position < sampleCount; position++) {
            indexes.add((int) ((long) position * (expectedChunkCount - 1) / (sampleCount - 1)));
        }
        return List.copyOf(indexes);
    }

    static List<Document> sourceSamplePipeline(ProbeRequest request, List<Integer> sampleIndexes) {
        Document match = new Document("organizationId", request.organizationId())
                .append("knowledgeDocumentId", request.knowledgeDocumentId())
                .append("ingestionId", request.ingestionId())
                .append("chunkIndex", new Document("$in", sampleIndexes));
        Document projection = new Document("_id", 1)
                .append("chunkIndex", 1)
                .append("embedding", 1);
        return List.of(
                new Document("$match", match),
                new Document("$project", projection),
                new Document("$sort", new Document("chunkIndex", 1).append("_id", 1)));
    }

    static List<Document> vectorSearchPipeline(ProbeRequest request, List<Double> embedding) {
        Document filter = new Document("organizationId", request.organizationId())
                .append("accessLevel", request.accessLevel().name());
        Document vectorSearch = new Document("index", KnowledgeChunkVectorIndexSetup.INDEX_NAME)
                .append("path", KnowledgeChunkVectorIndexSetup.EMBEDDING_PATH)
                .append("queryVector", embedding)
                .append("exact", true)
                .append("limit", SEARCH_RESULT_LIMIT)
                .append("filter", filter);
        return List.of(
                new Document("$vectorSearch", vectorSearch),
                new Document("$project", new Document("_id", 1)));
    }

    private static boolean isValid(ProbeRequest request) {
        return request != null
                && isNonBlank(request.organizationId())
                && isNonBlank(request.knowledgeDocumentId())
                && isNonBlank(request.ingestionId())
                && request.expectedChunkCount() > 0
                && request.expectedChunkCount() <= MAX_SUPPORTED_CHUNKS
                && request.accessLevel() != null;
    }

    private static boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean isReadyIndex(Document index) {
        return index != null
                && "READY".equals(index.get("status"))
                && Boolean.TRUE.equals(index.get("queryable"))
                && KnowledgeChunkVectorIndexSetup.definitionMismatches(index).isEmpty();
    }

    private static List<SampleChunk> readSamples(
            MongoCollection<Document> collection, ProbeRequest request, List<Integer> sampleIndexes) {
        List<Document> rows = collection.aggregate(sourceSamplePipeline(request, sampleIndexes)).into(new ArrayList<>());
        if (rows == null) {
            return null;
        }

        Set<Integer> expectedIndexes = new LinkedHashSet<>(sampleIndexes);
        Map<Integer, SampleChunk> byIndex = new HashMap<>();
        for (Document row : rows) {
            SampleChunk sample = asValidSample(row, request.ingestionId(), expectedIndexes);
            if (sample == null || byIndex.putIfAbsent(sample.chunkIndex(), sample) != null) {
                return null;
            }
        }
        if (!byIndex.keySet().equals(expectedIndexes)) {
            return null;
        }
        return sampleIndexes.stream().map(byIndex::get).toList();
    }

    private static SampleChunk asValidSample(Document row, String ingestionId, Set<Integer> expectedIndexes) {
        if (row == null || !(row.get("_id") instanceof String id)
                || !(row.get("chunkIndex") instanceof Number number)) {
            return null;
        }
        double decimal = number.doubleValue();
        if (!Double.isFinite(decimal) || decimal != Math.rint(decimal)) {
            return null;
        }
        int index = (int) decimal;
        if (!expectedIndexes.contains(index) || !chunkId(ingestionId, index).equals(id)) {
            return null;
        }
        if (!(row.get("embedding") instanceof List<?> rawEmbedding)
                || rawEmbedding.size() != KnowledgeChunkVectorIndexSetup.NUM_DIMENSIONS) {
            return null;
        }
        List<Double> embedding = new ArrayList<>(rawEmbedding.size());
        for (Object value : rawEmbedding) {
            if (!(value instanceof Number numberValue) || !Double.isFinite(numberValue.doubleValue())) {
                return null;
            }
            embedding.add(numberValue.doubleValue());
        }
        return new SampleChunk(id, index, List.copyOf(embedding));
    }

    private static boolean containsOnlyExpectedIds(List<Document> matches, String expectedId) {
        if (matches.isEmpty()) {
            return false;
        }
        for (Document match : matches) {
            if (match != null && expectedId.equals(match.get("_id"))) {
                return true;
            }
        }
        return false;
    }

    private static String chunkId(String ingestionId, int chunkIndex) {
        return "%s-%05d".formatted(ingestionId, chunkIndex);
    }

    private static SearchReadinessReport awaiting(int samples, int discoverable) {
        return new SearchReadinessReport(SearchReadinessOutcome.AWAITING_SEARCH_INDEX, samples, discoverable);
    }

    private record SampleChunk(String id, int chunkIndex, List<Double> embedding) {
    }

    public record ProbeRequest(
            String organizationId,
            String knowledgeDocumentId,
            String ingestionId,
            int expectedChunkCount,
            ChunkAccessLevel accessLevel) {
    }

    public record SearchReadinessReport(
            SearchReadinessOutcome outcome,
            int sampledChunkCount,
            int discoverableChunkCount) {

        public boolean ready() {
            return outcome == SearchReadinessOutcome.READY;
        }
    }

    public enum SearchReadinessOutcome {
        READY,
        AWAITING_SEARCH_INDEX
    }
}
