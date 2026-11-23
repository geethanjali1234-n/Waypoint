package com.waypoint.repository;

import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.enums.KnowledgeDocumentStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Optional;

/**
 * MyHR knowledge base entries, always looked up within one organization.
 *
 * <p>Every organizationId argument must come from the verified, server-side actor (the user's
 * organization as loaded from the database), never from request parameters, bodies or headers.
 * Inherited MongoRepository operations that are not organization-scoped (findById, findAll,
 * existsById, delete*, ...) must not be used by knowledge services. Entries are never deleted -
 * revocation is a status change - so no delete methods are declared.
 */
public interface KnowledgeDocumentRepository extends MongoRepository<KnowledgeDocument, String> {

    Optional<KnowledgeDocument> findByIdAndOrganizationId(String id, String organizationId);

    List<KnowledgeDocument> findByOrganizationIdOrderByCreatedAtDesc(String organizationId);

    List<KnowledgeDocument> findByOrganizationIdAndStatus(String organizationId, KnowledgeDocumentStatus status);

    /**
     * Whether an entry with this status already holds the stored document as its active or pending
     * version. Written as an explicit query because equality on an array field matches whole
     * elements only, so "doc123" never matches "doc1234"; a derived "Containing" method would
     * become a regex if sourceDocumentIds were ever a plain String.
     */
    @Query(value = "{ 'organizationId': ?0, 'status': ?1, 'sourceDocumentIds': ?2 }", exists = true)
    boolean existsWithSourceDocument(String organizationId, KnowledgeDocumentStatus status, String sourceDocumentId);

    /** Whether an entry with this status already serves exactly this content. */
    boolean existsByOrganizationIdAndStatusAndActiveVersionContentSha256(
            String organizationId, KnowledgeDocumentStatus status, String contentSha256);

    /** Whether an entry with this status is already preparing exactly this content. */
    boolean existsByOrganizationIdAndStatusAndPendingVersionContentSha256(
            String organizationId, KnowledgeDocumentStatus status, String contentSha256);
}
