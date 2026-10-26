package com.waypoint.entity;

import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.repository.DocumentRequestRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRequestModelTest {

    @Test
    void usesDocumentRequestsCollectionAndContainsV1Schema() {
        assertThat(DocumentRequest.class.getAnnotation(Document.class).collection())
                .isEqualTo("document_requests");
        assertThat(List.of(DocumentRequest.class.getDeclaredFields()))
                .extracting(Field::getName)
                .containsExactlyInAnyOrder(
                        "id", "organizationId", "requestedByUserId", "requestedForUserId",
                        "documentType", "requestTitle", "requestedDocuments", "requestDescription",
                        "category", "priority", "requestDirection", "attachmentDocumentIds", "requiredBy",
                        "status", "fulfilledByUserId", "fulfilledDocumentId", "employeeSignatureDocumentId",
                        "signedResponseDocumentId", "signatureSubmittedAt", "rejectionReason",
                        "rejectedByUserId", "requestedAt", "fulfilledAt", "rejectedAt",
                        "originalDocumentId", "currentDocumentId", "organizationSignedDocumentId",
                        "documentVersions", "history", "submittedAt", "reviewedByUserId", "reviewedAt",
                        "returnReason", "approvedByUserId", "approvedAt", "offerLetterApproval", "createdAt", "updatedAt");
        assertThat(List.of(DocumentRequest.class.getDeclaredFields()))
                .extracting(Field::getName)
                .doesNotContain("details", "fulfilledFileName");
    }

    @Test
    void controlledEnumsContainApprovedV1Values() {
        assertThat(DocumentRequestStatus.values()).containsExactly(
                DocumentRequestStatus.PENDING,
                DocumentRequestStatus.SUBMITTED,
                DocumentRequestStatus.RETURNED_FOR_CORRECTION,
                DocumentRequestStatus.APPROVED,
                DocumentRequestStatus.FULFILLED,
                DocumentRequestStatus.REJECTED,
                DocumentRequestStatus.CANCELLED);
        assertThat(RequestedDocumentType.values()).containsExactly(
                RequestedDocumentType.I983,
                RequestedDocumentType.EMPLOYMENT_VERIFICATION,
                RequestedDocumentType.EXPERIENCE_LETTER,
                RequestedDocumentType.EMPLOYMENT_LETTER,
                RequestedDocumentType.OFFER_LETTER,
                RequestedDocumentType.OTHER);
    }

    @Test
    void repositoryAndIndexesSupportV1LookupsWithoutUniqueConstraints() {
        assertThat(MongoRepository.class).isAssignableFrom(DocumentRequestRepository.class);
        assertThat(DocumentRequestRepository.class.getDeclaredMethods()).hasSize(11);

        CompoundIndexes indexes = DocumentRequest.class.getAnnotation(CompoundIndexes.class);
        assertThat(indexes.value()).extracting(CompoundIndex::name).containsExactlyInAnyOrder(
                "organization_recipient_status", "organization_status_requestedAt",
                "organization_requestedAt", "organization_requester_requestedAt",
                "organization_recipient_requestedAt");
        assertThat(indexes.value()).allSatisfy(index -> assertThat(index.unique()).isFalse());
    }
}
