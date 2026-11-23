package com.waypoint.service;

import com.mongodb.client.AggregateIterable;
import com.mongodb.client.MongoCollection;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.service.KnowledgeChunkIntegrityCheck.Expectation;
import com.waypoint.service.KnowledgeChunkIntegrityCheck.IntegrityOutcome;
import com.waypoint.service.KnowledgeChunkIntegrityCheck.IntegrityViolation;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoOperations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** All database interaction is mocked. No Spring context or MongoDB connection is created. */
@ExtendWith(MockitoExtension.class)
class KnowledgeChunkIntegrityCheckTest {

    private static final String ORG = "org-1";
    private static final String DOCUMENT = "knowledge-1";
    private static final String INGESTION = "ingestion-1";
    private static final String SOURCE = "source-1";
    private static final String MODEL = "voyage-4-lite";
    private static final String DATABASE_CANARY = "mongodb://secret.example";

    @Mock MongoOperations mongoOperations;
    @Mock MongoCollection<Document> collection;
    @Mock AggregateIterable<Document> aggregate;

    private KnowledgeChunkIntegrityCheck check;

    @BeforeEach
    void setUp() {
        check = new KnowledgeChunkIntegrityCheck(mongoOperations);
    }

    @Test
    void verifiesExactChunkSetAndUsesContentSafeProjection() {
        givenRows(List.of(chunk(0), chunk(1), chunk(2)));

        var result = check.check(expectation(3));

        assertThat(result.outcome()).isEqualTo(IntegrityOutcome.VERIFIED);
        assertThat(result.verified()).isTrue();
        assertThat(result.violations()).isEmpty();

        ArgumentCaptor<List<Document>> pipeline = pipelineCaptor();
        Document match = pipeline.getValue().getFirst().get("$match", Document.class);
        Document projection = pipeline.getValue().get(1).get("$project", Document.class);
        assertThat(match).containsEntry("organizationId", ORG)
                .containsEntry("knowledgeDocumentId", DOCUMENT)
                .containsEntry("ingestionId", INGESTION);
        assertThat(projection.keySet()).containsExactlyInAnyOrder(
                "_id", "chunkIndex", "sourceDocumentId", "documentType", "accessLevel", "embeddingModel",
                "embeddingDimension");
        assertThat(projection).doesNotContainKeys("chunkText", "sourceDocumentName", "contentHash", "embedding");
        assertNoWrites();
    }

    @Test
    void detectsMissingExtraDuplicateAndMalformedChunks() {
        Document duplicateIndex = chunk(0).append("_id", chunkId(2));
        Document extra = chunk(3);
        Document malformed = new Document("_id", chunkId(1));
        givenRows(List.of(chunk(0), chunk(0), duplicateIndex, extra, malformed));

        var result = check.check(expectation(3));

        assertThat(result.outcome()).isEqualTo(IntegrityOutcome.NOT_VERIFIED);
        assertThat(result.violations()).contains(
                IntegrityViolation.COUNT_MISMATCH,
                IntegrityViolation.MISSING_CHUNK,
                IntegrityViolation.EXTRA_CHUNK,
                IntegrityViolation.DUPLICATE_CHUNK_ID,
                IntegrityViolation.DUPLICATE_CHUNK_INDEX,
                IntegrityViolation.UNEXPECTED_CHUNK_ID,
                IntegrityViolation.UNEXPECTED_CHUNK_INDEX,
                IntegrityViolation.MALFORMED_CHUNK);
        assertNoWrites();
    }

    @Test
    void detectsWrongMetadataModelAndDimensions() {
        Document wrong = chunk(0)
                .append("sourceDocumentId", "other-source")
                .append("documentType", DocumentType.REQUESTED_DOCUMENT.name())
                .append("accessLevel", ChunkAccessLevel.HR_ONLY.name())
                .append("embeddingModel", "other-model")
                .append("embeddingDimension", 1023);
        givenRows(List.of(wrong));

        var result = check.check(expectation(1));

        assertThat(result.violations()).containsExactlyInAnyOrder(
                IntegrityViolation.SOURCE_DOCUMENT_MISMATCH,
                IntegrityViolation.DOCUMENT_TYPE_MISMATCH,
                IntegrityViolation.ACCESS_LEVEL_MISMATCH,
                IntegrityViolation.EMBEDDING_MODEL_MISMATCH,
                IntegrityViolation.EMBEDDING_DIMENSION_MISMATCH);
        assertNoWrites();
    }

    @Test
    void failsClosedWithoutLeakingDriverDetails() {
        givenCollection();
        when(collection.aggregate(anyList())).thenThrow(new IllegalStateException(DATABASE_CANARY));

        var result = check.check(expectation(1));

        assertThat(result.outcome()).isEqualTo(IntegrityOutcome.NOT_VERIFIED);
        assertThat(result.violations()).containsExactly(IntegrityViolation.QUERY_UNAVAILABLE);
        assertThat(result.toString()).doesNotContain(DATABASE_CANARY);
        assertNoWrites();
    }

    @Test
    void rejectsInvalidExpectationWithoutDatabaseAccess() {
        var invalid = new Expectation(ORG, DOCUMENT, INGESTION, 1001, SOURCE,
                DocumentType.COMPANY_POLICY, ChunkAccessLevel.COMPANY_WIDE, MODEL);

        var result = check.check(invalid);

        assertThat(result.violations()).containsExactly(IntegrityViolation.INVALID_EXPECTATION);
        verify(mongoOperations, never()).getCollection(any(String.class));
        assertNoWrites();
    }

    private ArgumentCaptor<List<Document>> pipelineCaptor() {
        @SuppressWarnings({"unchecked", "rawtypes"})
        ArgumentCaptor<List<Document>> captor = (ArgumentCaptor) ArgumentCaptor.forClass(List.class);
        verify(collection).aggregate(captor.capture());
        return captor;
    }

    private void givenRows(List<Document> rows) {
        givenCollection();
        when(aggregate.into(any())).thenAnswer(invocation -> {
            Collection<Document> target = invocation.getArgument(0);
            target.addAll(rows);
            return target;
        });
    }

    private void givenCollection() {
        when(mongoOperations.getCollection("knowledge_chunks")).thenReturn(collection);
        when(collection.aggregate(anyList())).thenReturn(aggregate);
    }

    private void assertNoWrites() {
        verify(collection, never()).insertOne(any(Document.class));
        verify(collection, never()).insertMany(anyList());
        verify(collection, never()).deleteMany(any(Document.class));
        verify(collection, never()).replaceOne(any(Document.class), any(Document.class));
    }

    private static Expectation expectation(int count) {
        return new Expectation(ORG, DOCUMENT, INGESTION, count, SOURCE,
                DocumentType.COMPANY_POLICY, ChunkAccessLevel.COMPANY_WIDE, MODEL);
    }

    private static Document chunk(int index) {
        return new Document("_id", chunkId(index))
                .append("chunkIndex", index)
                .append("sourceDocumentId", SOURCE)
                .append("documentType", DocumentType.COMPANY_POLICY.name())
                .append("accessLevel", ChunkAccessLevel.COMPANY_WIDE.name())
                .append("embeddingModel", MODEL)
                .append("embeddingDimension", 1024);
    }

    private static String chunkId(int index) {
        return "%s-%05d".formatted(INGESTION, index);
    }
}
