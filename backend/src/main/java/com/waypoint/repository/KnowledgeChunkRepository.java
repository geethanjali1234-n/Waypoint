package com.waypoint.repository;

import com.waypoint.entity.KnowledgeChunk;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;

/**
 * All organizationId values must originate from authenticated, trusted server-side context
 * (e.g., the JWT-resolved organization of the calling user) — never from request parameters
 * or client-supplied input.
 *
 * <p>Inherited unscoped MongoRepository operations (findAll, deleteAll, etc.) must not be
 * used by employee-facing services. All employee-facing access must go through the org-scoped
 * methods below.
 */
public interface KnowledgeChunkRepository extends MongoRepository<KnowledgeChunk, String> {

    List<KnowledgeChunk> findByOrganizationIdAndSourceDocumentId(
            String organizationId, String sourceDocumentId);

    List<KnowledgeChunk> findByOrganizationId(String organizationId);

    /**
     * Discovers only non-null ingestion ids for one knowledge document. The projection deliberately
     * excludes chunk text and embeddings: cleanup needs identifiers, not document content.
     */
    @Query(value = "{ 'organizationId': ?0, 'knowledgeDocumentId': ?1, 'ingestionId': { '$exists': true, '$ne': null } }",
            fields = "{ 'ingestionId': 1 }")
    List<KnowledgeChunk> findByOrganizationIdAndKnowledgeDocumentId(
            String organizationId, String knowledgeDocumentId);

    /** Returns the count of deleted chunks so the ingestion service can log re-indexing progress. */
    long deleteByOrganizationIdAndSourceDocumentId(
            String organizationId, String sourceDocumentId);

    /**
     * Removes every chunk of the knowledge document except those written by activeIngestionId:
     * superseded versions, failed attempts, and any chunk without an ingestionId.
     */
    long deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionIdNot(
            String organizationId, String knowledgeDocumentId, String activeIngestionId);

    /** Removes the chunks of one ingestion attempt, e.g. after it failed or lost activation. */
    long deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(
            String organizationId, String knowledgeDocumentId, String ingestionId);

    /** Removes all chunks of a revoked knowledge document. */
    long deleteByOrganizationIdAndKnowledgeDocumentId(String organizationId, String knowledgeDocumentId);

    /** Confirms how many chunks an ingestion attempt actually wrote before it is activated. */
    long countByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(
            String organizationId, String knowledgeDocumentId, String ingestionId);
}
