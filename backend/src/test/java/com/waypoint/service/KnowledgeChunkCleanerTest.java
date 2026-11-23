package com.waypoint.service;

import com.waypoint.entity.KnowledgeChunk;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.repository.KnowledgeChunkRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KnowledgeChunkCleanerTest {

    private static final String ORG = "org-1";
    private static final String DOCUMENT = "knowledge-1";
    private static final String ACTIVE = "ingestion-active";
    private static final String PENDING = "ingestion-pending";
    private static final String OBSOLETE = "ingestion-obsolete";

    private final KnowledgeDocumentRepository documents = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
    private final KnowledgeChunkCleaner cleaner = new KnowledgeChunkCleaner(documents, chunks);

    @Test
    void g1ActiveIngestionDiscoveredBeforeTheFreshReadIsNeverDeleted() {
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT))
                .thenReturn(List.of(chunk(ACTIVE), chunk(OBSOLETE)));
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG))
                .thenReturn(Optional.of(live(version(ACTIVE, KnowledgeIngestionStage.INDEXED), null)));
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE)).thenReturn(2L);

        KnowledgeChunkCleaner.CleanupResult result = cleaner.cleanObsoleteChunks(ORG, DOCUMENT);

        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, ACTIVE);
        verify(chunks).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE);
        assertThat(result.cleanupCompletedFor(ACTIVE)).isFalse();
        assertThat(result.cleanupCompletedFor(OBSOLETE)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeIngestionStage.class, names = {"INGESTING", "INDEXED"})
    void g1LivePendingIngestionIsNeverDeleted(KnowledgeIngestionStage stage) {
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT)).thenReturn(List.of(chunk(PENDING)));
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG))
                .thenReturn(Optional.of(live(version(ACTIVE, KnowledgeIngestionStage.INDEXED), version(PENDING, stage))));

        KnowledgeChunkCleaner.CleanupResult result = cleaner.cleanObsoleteChunks(ORG, DOCUMENT);

        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(
                ORG, DOCUMENT, PENDING);
        assertThat(result.cleanupCompletedFor(PENDING)).isFalse();
    }

    @Test
    void g2PermanentlyObsoleteIdsAreDeletedExactlyAfterTheFreshRead() {
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT))
                .thenReturn(List.of(chunk(ACTIVE), chunk(OBSOLETE)));
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG))
                .thenReturn(Optional.of(live(version(ACTIVE, KnowledgeIngestionStage.INDEXED), null)));
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE)).thenReturn(4L);

        KnowledgeChunkCleaner.CleanupResult result = cleaner.cleanObsoleteChunks(ORG, DOCUMENT);

        verify(chunks).findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT);
        verify(documents).findByIdAndOrganizationId(DOCUMENT, ORG);
        verify(chunks).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE);
        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, ACTIVE);
        InOrder order = inOrder(chunks, documents);
        order.verify(chunks).findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT);
        order.verify(documents).findByIdAndOrganizationId(DOCUMENT, ORG);
        order.verify(chunks).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE);
        assertThat(result.discoveredIngestionIds()).containsExactlyInAnyOrder(ACTIVE, OBSOLETE);
        assertThat(result.cleanupCompletedFor(OBSOLETE)).isTrue();
    }

    @Test
    void revocationLeavesAllDocumentChunksForTheRevocationService() {
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT))
                .thenReturn(List.of(chunk(ACTIVE), chunk(OBSOLETE)));
        KnowledgeDocument revoked = live(version(ACTIVE, KnowledgeIngestionStage.INDEXED), null);
        revoked.setStatus(KnowledgeDocumentStatus.REVOKED);
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(revoked));

        KnowledgeChunkCleaner.CleanupResult result = cleaner.cleanObsoleteChunks(ORG, DOCUMENT);

        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, ACTIVE);
        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE);
        assertThat(result.cleanupCompletedFor(ACTIVE)).isFalse();
        assertThat(result.cleanupCompletedFor(OBSOLETE)).isFalse();
    }

    @Test
    void missingDocumentDefersCleanupRatherThanInferringChunksAreObsolete() {
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT)).thenReturn(List.of(chunk(OBSOLETE)));
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.empty());

        KnowledgeChunkCleaner.CleanupResult result = cleaner.cleanObsoleteChunks(ORG, DOCUMENT);

        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(
                ORG, DOCUMENT, OBSOLETE);
        assertThat(result.cleanupCompletedFor(OBSOLETE)).isFalse();
    }

    @Test
    void missingOrBlankIngestionIdsAreLeftForSeparateReview() {
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT))
                .thenReturn(List.of(chunk(null), chunk(" ")));

        KnowledgeChunkCleaner.CleanupResult result = cleaner.cleanObsoleteChunks(ORG, DOCUMENT);

        verify(documents, never()).findByIdAndOrganizationId(anyString(), anyString());
        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(
                anyString(), anyString(), anyString());
        assertThat(result.discoveredIngestionIds()).isEmpty();
    }

    @Test
    void oneDeleteFailureDoesNotPreventOtherPermanentlyObsoleteIdsFromBeingCleaned() {
        String second = "ingestion-old-2";
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(ORG, DOCUMENT))
                .thenReturn(List.of(chunk(OBSOLETE), chunk(second)));
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(live(null, null)));
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE))
                .thenThrow(new DataAccessResourceFailureException("mongodb+srv://user:secret@cluster.example"));
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, second)).thenReturn(3L);

        KnowledgeChunkCleaner.CleanupResult result = cleaner.cleanObsoleteChunks(ORG, DOCUMENT);

        verify(chunks).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, OBSOLETE);
        verify(chunks).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(ORG, DOCUMENT, second);
        assertThat(result.cleanupCompletedFor(OBSOLETE)).isFalse();
        assertThat(result.cleanupCompletedFor(second)).isTrue();
    }

    private static KnowledgeChunk chunk(String ingestionId) {
        return KnowledgeChunk.builder().ingestionId(ingestionId).build();
    }

    private static KnowledgeDocument live(KnowledgeDocumentVersion active, KnowledgeDocumentVersion pending) {
        return KnowledgeDocument.builder()
                .organizationId(ORG)
                .status(KnowledgeDocumentStatus.LIVE)
                .activeVersion(active)
                .pendingVersion(pending)
                .build();
    }

    private static KnowledgeDocumentVersion version(String ingestionId, KnowledgeIngestionStage stage) {
        return KnowledgeDocumentVersion.builder().ingestionId(ingestionId).stage(stage).build();
    }
}
