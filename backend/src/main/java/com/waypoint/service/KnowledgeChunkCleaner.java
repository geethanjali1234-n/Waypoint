package com.waypoint.service;

import com.waypoint.entity.KnowledgeChunk;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.repository.KnowledgeChunkRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Removes chunks only after discovering candidate ingestion ids and then reading the current
 * knowledge document. The active ingestion is always protected here; revocation owns deletion of
 * all document chunks after it atomically changes the document status. A LIVE document also
 * protects an INGESTING or INDEXED pending ingestion. An id that is unreferenced at that fresh
 * read is permanently obsolete: claims mint a new UUID before writing, and no production
 * operation restores an old ingestion id.
 */
@Service
@RequiredArgsConstructor
public class KnowledgeChunkCleaner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeChunkCleaner.class);

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeChunkRepository knowledgeChunkRepository;

    /**
     * Sweeps one document's known ingestion ids. Missing or blank ids are intentionally not
     * candidates; they require separate data review rather than an inferred delete.
     */
    public CleanupResult cleanObsoleteChunks(String organizationId, String knowledgeDocumentId) {
        Set<String> candidates;
        try {
            candidates = discoveredIngestionIds(knowledgeChunkRepository, organizationId, knowledgeDocumentId);
        } catch (RuntimeException failure) {
            log.warn("Knowledge chunk cleanup could not discover ingestions for document {} ({})",
                    knowledgeDocumentId, failure.getClass().getSimpleName());
            return CleanupResult.discoveryFailed();
        }
        if (candidates.isEmpty()) {
            return CleanupResult.complete(Set.of(), Set.of(), Set.of());
        }

        Optional<KnowledgeDocument> current;
        try {
            current = knowledgeDocumentRepository.findByIdAndOrganizationId(knowledgeDocumentId, organizationId);
        } catch (RuntimeException failure) {
            log.warn("Knowledge chunk cleanup could not read document {} ({})",
                    knowledgeDocumentId, failure.getClass().getSimpleName());
            return CleanupResult.readFailed(candidates);
        }

        if (current.isEmpty() || !organizationId.equals(current.get().getOrganizationId())) {
            // An incomplete read cannot prove that a candidate is permanently obsolete.
            return CleanupResult.readFailed(candidates);
        }
        if (current.get().getStatus() != KnowledgeDocumentStatus.LIVE) {
            // KnowledgeApprovalService.revoke() owns full-document deletion after the status change.
            // Ordinary cleanup must not race it or report its work as complete.
            log.info("Deferred chunk cleanup for non-live knowledge document {}", knowledgeDocumentId);
            return CleanupResult.deferred(candidates);
        }

        Set<String> protectedIds = protectedIngestionIds(current.get(), organizationId);
        Set<String> failed = new LinkedHashSet<>();
        for (String ingestionId : candidates) {
            if (protectedIds.contains(ingestionId)) {
                log.info("Kept chunks of ingestion {} for knowledge document {} because it is still referenced",
                        ingestionId, knowledgeDocumentId);
                continue;
            }
            try {
                long removed = knowledgeChunkRepository.deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(
                        organizationId, knowledgeDocumentId, ingestionId);
                log.info("Removed {} obsolete chunks of ingestion {} for knowledge document {}",
                        removed, ingestionId, knowledgeDocumentId);
            } catch (RuntimeException failure) {
                failed.add(ingestionId);
                log.warn("Chunks of ingestion {} for knowledge document {} could not be removed ({})",
                        ingestionId, knowledgeDocumentId, failure.getClass().getSimpleName());
            }
        }
        return CleanupResult.complete(candidates, protectedIds, failed);
    }

    private static Set<String> discoveredIngestionIds(KnowledgeChunkRepository chunks,
                                                        String organizationId, String knowledgeDocumentId) {
        List<KnowledgeChunk> rows = chunks.findByOrganizationIdAndKnowledgeDocumentId(organizationId, knowledgeDocumentId);
        Set<String> ingestionIds = new LinkedHashSet<>();
        if (rows != null) {
            for (KnowledgeChunk row : rows) {
                if (row != null && hasText(row.getIngestionId())) {
                    ingestionIds.add(row.getIngestionId());
                }
            }
        }
        return ingestionIds;
    }

    private static Set<String> protectedIngestionIds(KnowledgeDocument document, String organizationId) {
        if (document == null || !organizationId.equals(document.getOrganizationId())) {
            return Set.of();
        }
        Set<String> protectedIds = new LinkedHashSet<>();
        addIfPresent(protectedIds, document.getActiveVersion());
        KnowledgeDocumentVersion pending = document.getPendingVersion();
        if (document.getStatus() == KnowledgeDocumentStatus.LIVE && pending != null
                && (pending.getStage() == KnowledgeIngestionStage.INGESTING
                || pending.getStage() == KnowledgeIngestionStage.INDEXED)) {
            addIfPresent(protectedIds, pending);
        }
        return protectedIds;
    }

    private static void addIfPresent(Set<String> ids, KnowledgeDocumentVersion version) {
        if (version != null && hasText(version.getIngestionId())) {
            ids.add(version.getIngestionId());
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record CleanupResult(Set<String> discoveredIngestionIds, Set<String> protectedIngestionIds,
                                Set<String> failedIngestionIds, boolean discoveryCompleted) {
        static CleanupResult complete(Set<String> discovered, Set<String> protectedIds, Set<String> failed) {
            return new CleanupResult(Set.copyOf(discovered), Set.copyOf(protectedIds), Set.copyOf(failed), true);
        }

        static CleanupResult discoveryFailed() {
            return new CleanupResult(Set.of(), Set.of(), Set.of(), false);
        }

        static CleanupResult readFailed(Set<String> discovered) {
            return new CleanupResult(Set.copyOf(discovered), Set.of(), Set.of(), false);
        }

        static CleanupResult deferred(Set<String> discovered) {
            return new CleanupResult(Set.copyOf(discovered), Set.of(), Set.of(), false);
        }

        /** True when this id was either absent at discovery or was deleted successfully. */
        public boolean cleanupCompletedFor(String ingestionId) {
            return discoveryCompleted && !protectedIngestionIds.contains(ingestionId)
                    && !failedIngestionIds.contains(ingestionId);
        }
    }
}
