package com.waypoint.setup;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.setup.KnowledgeChunkVectorSearch.CandidateChunk;
import com.waypoint.setup.KnowledgeChunkVectorSearch.SearchRequest;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure Mockito tests: no MongoDB, Atlas Vector Search or embedding provider is contacted. */
@ExtendWith(MockitoExtension.class)
class KnowledgeChunkVectorSearchTest {

    private static final String ORG = "org-acme-77";
    private static final String POLICY_TEXT = "Employees accrue 1.5 vacation days per month.";

    @Mock MongoOperations mongoOperations;
    @Mock MongoCollection<Document> collection;

    @Test
    void pipelinePreFiltersByOrganizationAndAccessLevelAndNeverProjectsEmbeddings() {
        SearchRequest request = request(Set.of(ChunkAccessLevel.HR_ONLY, ChunkAccessLevel.COMPANY_WIDE), 20, 400);

        List<Document> pipeline = KnowledgeChunkVectorSearch.vectorSearchPipeline(request);

        assertThat(pipeline).hasSize(2);
        Document vectorSearch = pipeline.get(0).get("$vectorSearch", Document.class);
        assertThat(vectorSearch.getString("index")).isEqualTo(KnowledgeChunkVectorIndexSetup.INDEX_NAME);
        assertThat(vectorSearch.getString("path")).isEqualTo(KnowledgeChunkVectorIndexSetup.EMBEDDING_PATH);
        assertThat(vectorSearch.getInteger("limit")).isEqualTo(20);
        assertThat(vectorSearch.getInteger("numCandidates")).isEqualTo(400);
        assertThat(vectorSearch).doesNotContainKey("exact");
        assertThat(vectorSearch.get("filter", Document.class)).isEqualTo(new Document("organizationId", ORG)
                .append("accessLevel", new Document("$in", List.of("COMPANY_WIDE", "HR_ONLY"))));
        // Every filter path must be a filter field of the existing index, or Atlas rejects the query.
        assertThat(KnowledgeChunkVectorIndexSetup.FILTER_PATHS)
                .containsAll(vectorSearch.get("filter", Document.class).keySet());

        Document projection = pipeline.get(1).get("$project", Document.class);
        assertThat(projection).doesNotContainKey("embedding");
        assertThat(projection.get("score")).isEqualTo(new Document("$meta", "vectorSearchScore"));
        assertThat(projection.keySet()).containsExactlyInAnyOrder("_id", "organizationId", "knowledgeDocumentId",
                "ingestionId", "sourceDocumentId", "documentType", "accessLevel", "chunkIndex", "chunkText",
                "embeddingModel", "score");
    }

    @Test
    void rejectsUnsupportedOrUnboundedRequestsBeforeTouchingMongo() {
        KnowledgeChunkVectorSearch search = new KnowledgeChunkVectorSearch(mongoOperations);
        List<SearchRequest> invalid = new ArrayList<>(List.of(
                new SearchRequest(" ", Set.of(ChunkAccessLevel.COMPANY_WIDE), vector(), 10, 100),
                request(Set.of(ChunkAccessLevel.EMPLOYEE_PRIVATE), 10, 100),
                request(Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.EMPLOYEE_PRIVATE), 10, 100),
                request(Set.of(), 10, 100),
                request(Set.of(ChunkAccessLevel.COMPANY_WIDE), 0, 100),
                request(Set.of(ChunkAccessLevel.COMPANY_WIDE), KnowledgeChunkVectorSearch.MAX_LIMIT + 1, 1000),
                request(Set.of(ChunkAccessLevel.COMPANY_WIDE), 10, 9),
                request(Set.of(ChunkAccessLevel.COMPANY_WIDE), 10, KnowledgeChunkVectorSearch.MAX_NUM_CANDIDATES + 1),
                new SearchRequest(ORG, Set.of(ChunkAccessLevel.COMPANY_WIDE), List.of(0.1, 0.2), 10, 100),
                new SearchRequest(ORG, Set.of(ChunkAccessLevel.COMPANY_WIDE), withValue(Double.NaN), 10, 100),
                new SearchRequest(ORG, Set.of(ChunkAccessLevel.COMPANY_WIDE), withValue(null), 10, 100),
                new SearchRequest(ORG, Collections.singleton(null), vector(), 10, 100)));
        invalid.add(null);

        for (SearchRequest request : invalid) {
            assertThatThrownBy(() -> search.search(request)).as(String.valueOf(request))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        verifyNoInteractions(mongoOperations);
    }

    @Test
    void mapsWellFormedRowsDropsMalformedOnesAndAppliesTheTimeLimit() {
        AggregateIterable<Document> iterable = iterable(List.of(
                row(0, 0.91),
                row(1, 0.88, r -> r.remove("knowledgeDocumentId")),
                row(2, 0.87, r -> r.put("accessLevel", "EVERYONE")),
                row(3, 0.86, r -> r.put("chunkIndex", 2.5)),
                row(4, 0.85, r -> r.put("chunkIndex", -1)),
                row(5, 1.5),
                row(6, 0.84, r -> r.put("chunkText", " ")),
                row(7, 0.83, r -> r.put("documentType", 3)),
                row(8, 0.82)));
        givenSearchReturns(iterable);

        List<CandidateChunk> candidates = new KnowledgeChunkVectorSearch(mongoOperations)
                .search(request(Set.of(ChunkAccessLevel.COMPANY_WIDE), 10, 200));

        assertThat(candidates).extracting(CandidateChunk::chunkIndex).containsExactly(0, 8);
        CandidateChunk first = candidates.getFirst();
        assertThat(first.id()).isEqualTo("ingestion-1-00000");
        assertThat(first.organizationId()).isEqualTo(ORG);
        assertThat(first.knowledgeDocumentId()).isEqualTo("knowledge-1");
        assertThat(first.ingestionId()).isEqualTo("ingestion-1");
        assertThat(first.sourceDocumentId()).isEqualTo("stored-1");
        assertThat(first.documentType()).isEqualTo(DocumentType.COMPANY_POLICY);
        assertThat(first.accessLevel()).isEqualTo(ChunkAccessLevel.COMPANY_WIDE);
        assertThat(first.embeddingModel()).isEqualTo("voyage-4-lite");
        assertThat(first.score()).isEqualTo(0.91);
        verify(iterable).maxTime(KnowledgeChunkVectorSearch.MAX_TIME_MS, TimeUnit.MILLISECONDS);
    }

    @Test
    void neverReturnsMoreRowsThanTheRequestedLimit() {
        List<Document> rows = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            rows.add(row(index, 0.9 - index * 0.01));
        }
        givenSearchReturns(iterable(rows));

        List<CandidateChunk> candidates = new KnowledgeChunkVectorSearch(mongoOperations)
                .search(request(Set.of(ChunkAccessLevel.COMPANY_WIDE), 3, 60));

        assertThat(candidates).hasSize(3);
    }

    @Test
    void sendsTheBuiltPipelineAndPropagatesDriverFailuresWithoutWriting() {
        AggregateIterable<Document> iterable = iterable(List.of());
        givenSearchReturns(iterable);
        SearchRequest request = request(Set.of(ChunkAccessLevel.COMPANY_WIDE), 5, 100);

        new KnowledgeChunkVectorSearch(mongoOperations).search(request);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Document>> pipeline = ArgumentCaptor.forClass(List.class);
        verify(collection).aggregate(pipeline.capture());
        assertThat(pipeline.getValue()).isEqualTo(KnowledgeChunkVectorSearch.vectorSearchPipeline(request));
        verify(collection, never()).insertOne(any(Document.class));
        verify(collection, never()).deleteMany(any(Document.class));

        when(collection.aggregate(anyList())).thenThrow(new IllegalStateException("mongodb+srv://user:s3cret@host"));
        assertThatThrownBy(() -> new KnowledgeChunkVectorSearch(mongoOperations).search(request))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void stringFormsKeepVectorsAndPolicyTextOutOfLogs() {
        SearchRequest request = request(Set.of(ChunkAccessLevel.COMPANY_WIDE), 5, 100);
        CandidateChunk candidate = new CandidateChunk("ingestion-1-00000", ORG, "knowledge-1", "ingestion-1",
                "stored-1", DocumentType.COMPANY_POLICY, ChunkAccessLevel.COMPANY_WIDE, 0, POLICY_TEXT,
                "voyage-4-lite", 0.9);

        assertThat(request.toString()).doesNotContain(ORG, "0.123");
        assertThat(candidate.toString()).doesNotContain(POLICY_TEXT, ORG);
    }

    private void givenSearchReturns(AggregateIterable<Document> iterable) {
        when(mongoOperations.getCollection(KnowledgeChunkVectorIndexSetup.COLLECTION_NAME)).thenReturn(collection);
        when(collection.aggregate(anyList())).thenReturn(iterable);
    }

    private static AggregateIterable<Document> iterable(List<Document> rows) {
        @SuppressWarnings("unchecked")
        AggregateIterable<Document> iterable = mock(AggregateIterable.class);
        when(iterable.maxTime(anyLong(), any(TimeUnit.class))).thenReturn(iterable);
        when(iterable.into(any())).thenAnswer(invocation -> {
            Collection<Document> target = invocation.getArgument(0);
            target.addAll(rows);
            return target;
        });
        return iterable;
    }

    private static Document row(int index, double score) {
        return row(index, score, r -> { });
    }

    private static Document row(int index, double score, Consumer<Document> change) {
        Document row = new Document("_id", "ingestion-1-%05d".formatted(index))
                .append("organizationId", ORG)
                .append("knowledgeDocumentId", "knowledge-1")
                .append("ingestionId", "ingestion-1")
                .append("sourceDocumentId", "stored-1")
                .append("documentType", "COMPANY_POLICY")
                .append("accessLevel", "COMPANY_WIDE")
                .append("chunkIndex", index)
                .append("chunkText", POLICY_TEXT)
                .append("embeddingModel", "voyage-4-lite")
                .append("score", score);
        change.accept(row);
        return row;
    }

    private static SearchRequest request(Set<ChunkAccessLevel> levels, int limit, int numCandidates) {
        return new SearchRequest(ORG, levels, vector(), limit, numCandidates);
    }

    private static List<Double> vector() {
        return Collections.nCopies(KnowledgeChunkVectorIndexSetup.NUM_DIMENSIONS, 0.123);
    }

    private static List<Double> withValue(Double value) {
        List<Double> vector = new ArrayList<>(vector());
        vector.set(7, value);
        return vector;
    }
}
