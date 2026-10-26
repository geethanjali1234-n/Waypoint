package com.waypoint.repository;

import com.waypoint.entity.DocumentRequest;
import com.waypoint.enums.DocumentRequestStatus;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;
import org.springframework.data.mongodb.repository.Update;
import java.time.LocalDateTime;

import java.util.List;
import java.util.Optional;

public interface DocumentRequestRepository extends MongoRepository<DocumentRequest, String> {

    @Query("{ '_id': ?0, 'organizationId': ?1, 'fulfilledDocumentId': ?2, 'signedResponseDocumentId': null, 'employeeSignatureDocumentId': null, 'status': 'FULFILLED' }")
    @Update("{ '$set': { 'signedResponseDocumentId': ?3, 'signatureSubmittedAt': ?4, 'updatedAt': ?4 } }")
    long recordSignedResponse(String id, String organizationId, String originalDocumentId,
                              String signedDocumentId, LocalDateTime signedAt);

    /**
     * Atomic status transition used by the document approval workflow: it only succeeds
     * (returns 1) if the request is still in one of the expected statuses, so two concurrent
     * submissions/approvals cannot both win.
     */
    @Query("{ '_id': ?0, 'organizationId': ?1, 'status': { '$in': ?2 } }")
    @Update("{ '$set': { 'status': ?3, 'updatedAt': ?4 } }")
    long transitionOfferLetterStatus(String id, String organizationId, java.util.Collection<String> fromStatuses,
                                     String toStatus, LocalDateTime at);

    List<DocumentRequest> findByOrganizationIdAndOfferLetterApprovalNotNullOrderByUpdatedAtDesc(String organizationId);

    List<DocumentRequest> findByOrganizationId(String organizationId);

    List<DocumentRequest> findByOrganizationIdOrderByRequestedAtDesc(String organizationId);

    Optional<DocumentRequest> findByIdAndOrganizationId(String id, String organizationId);

    List<DocumentRequest> findByRequestedForUserId(String requestedForUserId);

    List<DocumentRequest> findByRequestedByUserId(String requestedByUserId);

    List<DocumentRequest> findByStatus(DocumentRequestStatus status);

    List<DocumentRequest> findByOrganizationIdAndRequestedForUserIdAndStatus(
            String organizationId, String requestedForUserId, DocumentRequestStatus status);

    List<DocumentRequest> findByOrganizationIdAndStatusOrderByRequestedAtDesc(
            String organizationId, DocumentRequestStatus status);
}
