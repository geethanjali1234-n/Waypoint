package com.waypoint.setup;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.ListSearchIndexesIterable;
import com.mongodb.client.MongoCollection;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.setup.KnowledgeChunkSearchReadinessProbe.ProbeRequest;
import com.waypoint.setup.KnowledgeChunkSearchReadinessProbe.SearchReadinessOutcome;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** All Atlas and MongoDB calls are mocked. No Spring context or external connection is created. */
@ExtendWith(MockitoExtension.class)
class KnowledgeChunkSearchReadinessProbeTest {

    private static final String ORG = "org-1";
    private static final String DOCUMENT = "knowledge-1";
    private static final String INGESTION = "ingestion-1";
    private static final String DATABASE_CANARY = "mongodb+srv://secret.example";

    @Mock MongoOperations mongoOperations;
    @Mock MongoCollection<Document> collection;
    @Mock ListSearchIndexesIterable<Document> searchIndexes;

    private KnowledgeChunkSearchReadinessProbe probe;

    @BeforeEach
    void setUp() {
        probe = new KnowledgeChunkSearchReadinessProbe(mongoOperations);
    }

    @Test
    void reportsReadyOnlyWhenEverySampleIsDiscoverable() {
        givenReadyIndex();
        Document readyDefinition = readyIndex();
        readyDefinition.get("latestDefinition", Document.class).append("numPartitions", 1);
        when(searchIndexes.first()).thenReturn(readyDefinition);
        AtomicInteger searchCall = new AtomicInteger();
        when(collection.aggregate(anyList())).thenAnswer(invocation -> {
            List<Document> pipeline = invocation.getArgument(0);
            if (pipeline.getFirst().containsKey("$vectorSearch")) {
                int index = searchCall.getAndIncrement();
                return iterable(List.of(new Document("_id", chunkId(index))));
            }
            return iterable(List.of(sample(0), sample(1), sample(2)));
        });

        var result = probe.probe(request(3));

        assertThat(result.outcome()).isEqualTo(SearchReadinessOutcome.READY);
        assertThat(result.ready()).isTrue();
        assertThat(result.sampledChunkCount()).isEqualTo(3);
        assertThat(result.discoverableChunkCount()).isEqualTo(3);

        ArgumentCaptor<List<Document>> pipelines = pipelineCaptor();
        List<Document> vectorPipeline = pipelines.getAllValues().stream()
                .filter(pipeline -> pipeline.getFirst().containsKey("$vectorSearch"))
                .findFirst().orElseThrow();
        Document vectorSearch = vectorPipeline.getFirst().get("$vectorSearch", Document.class);
        assertThat(vectorSearch).containsEntry("index", KnowledgeChunkVectorIndexSetup.INDEX_NAME)
                .containsEntry("path", KnowledgeChunkVectorIndexSetup.EMBEDDING_PATH)
                .containsEntry("exact", true)
                .containsEntry("limit", KnowledgeChunkSearchReadinessProbe.SEARCH_RESULT_LIMIT);
        assertThat(vectorSearch.get("filter", Document.class)).containsExactlyEntriesOf(new Document("organizationId", ORG)
                .append("accessLevel", ChunkAccessLevel.COMPANY_WIDE.name()));
        assertNoWrites();
    }

    @Test
    void returnsAwaitingForMissingPendingOrNonQueryableIndex() {
        givenReadyIndex();
        when(searchIndexes.first()).thenReturn(null);
        assertAwaitingWithoutChunkReads();

        when(searchIndexes.first()).thenReturn(readyIndex().append("status", "PENDING"));
        assertAwaitingWithoutChunkReads();

        when(searchIndexes.first()).thenReturn(readyIndex().append("queryable", false));
        assertAwaitingWithoutChunkReads();
    }

    @Test
    void failsClosedForSearchDelayAndDocumentsExistingFilterFalseNegative() {
        givenReadyIndex();
        when(collection.aggregate(anyList())).thenAnswer(invocation -> {
            List<Document> pipeline = invocation.getArgument(0);
            if (pipeline.getFirst().containsKey("$vectorSearch")) {
                // The source sample exists, but another same-org/same-access result hides it because
                // the present index cannot pre-filter by knowledgeDocumentId or ingestionId.
                return iterable(List.of(new Document("_id", "other-ingestion-00000")));
            }
            return iterable(List.of(sample(0)));
        });

        var result = probe.probe(request(1));

        assertThat(result.outcome()).isEqualTo(SearchReadinessOutcome.AWAITING_SEARCH_INDEX);
        assertThat(result.ready()).isFalse();
        assertThat(result.discoverableChunkCount()).isZero();
        assertNoWrites();
    }

    @Test
    void failsClosedForSearchFailureWithoutLeakingDriverDetails() {
        givenReadyIndex();
        when(collection.aggregate(anyList())).thenAnswer(invocation -> {
            List<Document> pipeline = invocation.getArgument(0);
            if (pipeline.getFirst().containsKey("$vectorSearch")) {
                throw new IllegalStateException(DATABASE_CANARY);
            }
            return iterable(List.of(sample(0)));
        });

        var result = probe.probe(request(1));

        assertThat(result.outcome()).isEqualTo(SearchReadinessOutcome.AWAITING_SEARCH_INDEX);
        assertThat(result.toString()).doesNotContain(DATABASE_CANARY);
        assertNoWrites();
    }

    @Test
    void returnsAwaitingForInvalidOrWrongDimensionSourceSample() {
        givenReadyIndex();
        AggregateIterable<Document> invalidSample = iterable(List.of(sampleWithDimensions(0, 1023)));
        when(collection.aggregate(anyList())).thenReturn(invalidSample);

        var result = probe.probe(request(1));

        assertThat(result.outcome()).isEqualTo(SearchReadinessOutcome.AWAITING_SEARCH_INDEX);
        assertNoWrites();
    }

    @ParameterizedTest
    @MethodSource("sampleSelections")
    void selectsBoundedDeterministicSamplesIncludingFirstAndLast(int chunks, List<Integer> expected) {
        assertThat(KnowledgeChunkSearchReadinessProbe.sampleIndexes(chunks)).containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> sampleSelections() {
        return Stream.of(
                Arguments.of(1, List.of(0)),
                Arguments.of(2, List.of(0, 1)),
                Arguments.of(8, List.of(0, 1, 2, 3, 4, 5, 6, 7)),
                Arguments.of(9, List.of(0, 1, 2, 3, 4, 5, 6, 8)),
                Arguments.of(1000, List.of(0, 142, 285, 428, 570, 713, 856, 999)));
    }

    private void assertAwaitingWithoutChunkReads() {
        var result = probe.probe(request(1));
        assertThat(result.outcome()).isEqualTo(SearchReadinessOutcome.AWAITING_SEARCH_INDEX);
        assertThat(result.sampledChunkCount()).isZero();
        verify(collection, never()).aggregate(anyList());
        assertNoWrites();
    }

    private void givenReadyIndex() {
        when(mongoOperations.getCollection(KnowledgeChunkVectorIndexSetup.COLLECTION_NAME)).thenReturn(collection);
        when(collection.listSearchIndexes()).thenReturn(searchIndexes);
        when(searchIndexes.name(KnowledgeChunkVectorIndexSetup.INDEX_NAME)).thenReturn(searchIndexes);
        when(searchIndexes.first()).thenReturn(readyIndex());
    }

    private ArgumentCaptor<List<Document>> pipelineCaptor() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Document>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(collection, org.mockito.Mockito.times(4)).aggregate(captor.capture());
        return captor;
    }

    private static AggregateIterable<Document> iterable(List<Document> rows) {
        @SuppressWarnings("unchecked")
        AggregateIterable<Document> iterable = org.mockito.Mockito.mock(AggregateIterable.class);
        when(iterable.into(any())).thenAnswer(invocation -> {
            Collection<Document> target = invocation.getArgument(0);
            target.addAll(rows);
            return target;
        });
        return iterable;
    }

    private void assertNoWrites() {
        verify(collection, never()).insertOne(any(Document.class));
        verify(collection, never()).insertMany(anyList());
        verify(collection, never()).deleteMany(any(Document.class));
        verify(collection, never()).replaceOne(any(Document.class), any(Document.class));
        verify(collection, never()).createSearchIndexes(anyList());
    }

    private static ProbeRequest request(int count) {
        return new ProbeRequest(ORG, DOCUMENT, INGESTION, count, ChunkAccessLevel.COMPANY_WIDE);
    }

    private static Document readyIndex() {
        return new Document("name", KnowledgeChunkVectorIndexSetup.INDEX_NAME)
                .append("type", "vectorSearch")
                .append("status", "READY")
                .append("queryable", true)
                .append("latestDefinition", KnowledgeChunkVectorIndexSetup.vectorIndexDefinition());
    }

    private static Document sample(int index) {
        return sampleWithDimensions(index, KnowledgeChunkVectorIndexSetup.NUM_DIMENSIONS);
    }

    private static Document sampleWithDimensions(int index, int dimensions) {
        List<Double> embedding = new ArrayList<>(dimensions);
        for (int dimension = 0; dimension < dimensions; dimension++) {
            embedding.add(dimension == 0 ? index + 1.0 : 0.0);
        }
        return new Document("_id", chunkId(index))
                .append("chunkIndex", index)
                .append("embedding", embedding);
    }

    private static String chunkId(int index) {
        return "%s-%05d".formatted(INGESTION, index);
    }
}
