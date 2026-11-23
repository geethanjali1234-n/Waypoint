package com.waypoint.entity;

import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeChunkModelTest {

    @Test
    void usesKnowledgeChunksCollectionName() {
        assertThat(KnowledgeChunk.class.getAnnotation(Document.class).collection())
                .isEqualTo("knowledge_chunks");
    }

    @Test
    void chunkAccessLevelHasExactlyThreeValues() {
        assertThat(ChunkAccessLevel.values()).containsExactly(
                ChunkAccessLevel.COMPANY_WIDE,
                ChunkAccessLevel.HR_ONLY,
                ChunkAccessLevel.EMPLOYEE_PRIVATE);
    }

    @Test
    void builderProducesFullyPopulatedChunk() {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .id("chunk-1")
                .organizationId("org-abc")
                .sourceDocumentId("doc-xyz")
                .sourceDocumentName("HR Policy 2026")
                .documentType(DocumentType.REPORT)
                .ownerUserId(null)
                .accessLevel(ChunkAccessLevel.HR_ONLY)
                .chunkIndex(0)
                .chunkText("Employees may request up to 5 days of bereavement leave.")
                .embedding(List.of(0.12, -0.34, 0.56))
                .contentHash("a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2")
                .indexedAt(LocalDateTime.of(2026, 10, 16, 12, 0))
                .build();

        assertThat(chunk.getOrganizationId()).isEqualTo("org-abc");
        assertThat(chunk.getAccessLevel()).isEqualTo(ChunkAccessLevel.HR_ONLY);
        assertThat(chunk.getEmbedding()).hasSize(3).containsExactly(0.12, -0.34, 0.56);
        assertThat(chunk.getOwnerUserId()).isNull();
        assertThat(chunk.getChunkIndex()).isZero();
        assertThat(chunk.getContentHash())
                .isEqualTo("a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2");
    }

    @Test
    void employeePrivateChunkCarriesOwnerUserIdAndContentHash() {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .organizationId("org-abc")
                .sourceDocumentId("doc-private")
                .accessLevel(ChunkAccessLevel.EMPLOYEE_PRIVATE)
                .ownerUserId("user-123")
                .chunkIndex(0)
                .chunkText("Paystub for October 2026.")
                .embedding(List.of(0.99, 0.01))
                .contentHash("b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5")
                .build();

        assertThat(chunk.getAccessLevel()).isEqualTo(ChunkAccessLevel.EMPLOYEE_PRIVATE);
        assertThat(chunk.getOwnerUserId()).isEqualTo("user-123");
        assertThat(chunk.getContentHash()).hasSize(64);
    }

    @Test
    void contentHashIsNullableForInitialIngestion() {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .organizationId("org-abc")
                .sourceDocumentId("doc-xyz")
                .accessLevel(ChunkAccessLevel.COMPANY_WIDE)
                .chunkIndex(0)
                .chunkText("Some policy text.")
                .embedding(List.of(0.5))
                .build();

        assertThat(chunk.getContentHash()).isNull();
    }

    @Test
    void noArgConstructorProducesNullSafeInstance() {
        KnowledgeChunk chunk = new KnowledgeChunk();
        assertThat(chunk.getId()).isNull();
        assertThat(chunk.getEmbedding()).isNull();
        assertThat(chunk.getAccessLevel()).isNull();
        assertThat(chunk.getContentHash()).isNull();
    }

    @Test
    void carriesKnowledgeDocumentIngestionAndEmbeddingModel() {
        KnowledgeChunk chunk = KnowledgeChunk.builder()
                .organizationId("org-abc")
                .knowledgeDocumentId("kd-1")
                .ingestionId("ingestion-2")
                .embeddingModel("voyage-4-lite")
                .build();

        assertThat(chunk.getKnowledgeDocumentId()).isEqualTo("kd-1");
        assertThat(chunk.getIngestionId()).isEqualTo("ingestion-2");
        assertThat(chunk.getEmbeddingModel()).isEqualTo("voyage-4-lite");

        KnowledgeChunk empty = new KnowledgeChunk();
        assertThat(empty.getKnowledgeDocumentId()).isNull();
        assertThat(empty.getIngestionId()).isNull();
        assertThat(empty.getEmbeddingModel()).isNull();
    }

    @Test
    void versionTrackingFieldsAreStringsWithNoIndexAnnotations() throws Exception {
        for (String fieldName : List.of("knowledgeDocumentId", "ingestionId", "embeddingModel")) {
            Field field = KnowledgeChunk.class.getDeclaredField(fieldName);
            assertThat(field.getType()).as(fieldName).isEqualTo(String.class);
            assertThat(field.getAnnotations()).as(fieldName).isEmpty();
        }
    }

    private Indexed index(String fieldName) throws Exception {
        Field field = KnowledgeChunk.class.getDeclaredField(fieldName);
        return field.getAnnotation(Indexed.class);
    }
}
