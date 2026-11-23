package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.dto.myhr.KnowledgeDocumentResponse;
import com.waypoint.dto.myhr.KnowledgeManagementSettingsResponse;
import com.waypoint.dto.myhr.KnowledgeOperationResponse;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.entity.KnowledgeVersionHistoryEntry;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.DocumentExtractionException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure Mockito tests: no MongoDB, object storage, Voyage or Atlas Vector Search is contacted. */
class KnowledgeManagementServiceTest {

    private static final String ORG = "org-acme-77";
    private static final String DOCUMENT = "knowledge-77";
    private static final String SHA = "a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    private final KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeApprovalService approval = mock(KnowledgeApprovalService.class);
    private final KnowledgeIngestionService ingestion = mock(KnowledgeIngestionService.class);
    private final KnowledgeActivationService activation = mock(KnowledgeActivationService.class);
    private final DocumentService documents = mock(DocumentService.class);
    private final VerifiedMyHrActor hr = TestActors.hr("hr-user-71", ORG);
    private final VerifiedMyHrActor employee = TestActors.actor("employee-71", ORG, UserRole.EMPLOYEE);

    private final Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeManagementService.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopCapturingLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    // --- Authorization and switches -------------------------------------------------------------

    @Test
    void everyOperationRequiresAVerifiedHrOrCompanyAdmin() {
        KnowledgeManagementService service = enabled();
        for (VerifiedMyHrActor actor : new VerifiedMyHrActor[]{null, employee}) {
            Class<? extends RuntimeException> expected = actor == null ? UnauthorizedException.class : ForbiddenException.class;
            for (Executable call : allOperations(service, actor)) {
                assertThrows(expected, call);
            }
        }
        verifyNoInteractions(repository, approval, ingestion, activation, documents);
    }

    @Test
    void addingKnowledgeIsOffByDefaultWhileListingAndRevocationStayAvailable() {
        KnowledgeManagementService service = service(false, false, false);
        List<Executable> adding = List.of(
                () -> service.addPolicy(hr, pdf(), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.replacePolicy(hr, DOCUMENT, pdf(), ChunkAccessLevel.COMPANY_WIDE),
                () -> service.restoreHistoricalVersion(hr, DOCUMENT, "stored-old"),
                () -> service.acceptIncompleteText(hr, DOCUMENT, "stored-1"),
                () -> service.ingest(hr, DOCUMENT),
                () -> service.activate(hr, DOCUMENT, "ingestion-1"));
        for (Executable call : adding) {
            assertThatThrownBy(call::execute).isInstanceOf(BusinessException.class)
                    .hasMessage(KnowledgeManagementService.MANAGEMENT_DISABLED);
        }
        verifyNoInteractions(documents, approval, ingestion, activation);

        when(repository.findByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of(entry(e -> { })));
        when(approval.revoke(hr, DOCUMENT)).thenReturn(new KnowledgeApprovalService.RevocationResult(revoked(), 3, true));

        assertThat(service.list(hr)).singleElement().satisfies(document ->
                assertThat(document.actions()).isEqualTo(new KnowledgeDocumentResponse.Actions(false, false, false, false, true, false)));
        assertThat(service.revoke(hr, DOCUMENT).outcome()).isEqualTo("REVOKED");
        KnowledgeManagementSettingsResponse settings = service.settings(hr);
        assertThat(settings.managementEnabled()).isFalse();
        assertThat(settings.ingestionEnabled()).isFalse();
        assertThat(settings.activationEnabled()).isFalse();
    }

    @Test
    void ingestionNeedsItsOwnSwitchAndSettingsReportEffectiveValues() {
        KnowledgeManagementService service = service(true, false, true);

        assertThatThrownBy(() -> service.ingest(hr, DOCUMENT))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeManagementService.INGESTION_DISABLED);
        verifyNoInteractions(ingestion);

        KnowledgeManagementSettingsResponse settings = service.settings(hr);
        assertThat(settings.managementEnabled()).isTrue();
        assertThat(settings.ingestionEnabled()).isFalse();
        assertThat(settings.activationEnabled()).isTrue();
        assertThat(settings.acceptedFileExtensions()).containsExactly("pdf", "docx", "xlsx", "txt", "md");
        assertThat(settings.maxFileSizeBytes()).isEqualTo(5L * 1024 * 1024);
        assertThat(settings.accessLevels()).containsExactly(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY);
        assertThat(service(false, true, true).settings(hr).ingestionEnabled()).isFalse();
    }

    // --- Upload and approval --------------------------------------------------------------------

    @Test
    void invalidRequestsAreRejectedBeforeAnythingIsStored() {
        KnowledgeManagementService service = enabled();
        List<Executable> invalid = List.of(
                () -> service.addPolicy(hr, null, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.addPolicy(hr, file("leave.pdf", new byte[0]), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.addPolicy(hr, file("leave.pdf", new byte[(int) KnowledgeManagementService.MAX_FILE_BYTES + 1]),
                        "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.addPolicy(hr, file("leave.exe", bytes("MZ")), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.addPolicy(hr, file("leave", bytes("text")), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.addPolicy(hr, pdf(), " ", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.addPolicy(hr, pdf(), "x".repeat(201), ChunkAccessLevel.COMPANY_WIDE),
                () -> service.addPolicy(hr, pdf(), "Leave Policy", ChunkAccessLevel.EMPLOYEE_PRIVATE),
                () -> service.addPolicy(hr, pdf(), "Leave Policy", null),
                () -> service.replacePolicy(hr, " ", pdf(), ChunkAccessLevel.COMPANY_WIDE));
        for (Executable call : invalid) {
            assertThrows(BusinessException.class, call);
        }
        verifyNoInteractions(documents, approval);
    }

    @Test
    void addPolicyStoresACompanyPolicyForTheActorsOrganizationThenApprovesIt() {
        KnowledgeManagementService service = enabled();
        when(documents.store(any(DocumentUploadRequest.class))).thenReturn(stored("stored-1"));
        when(approval.approve(hr, "stored-1", "Leave Policy", ChunkAccessLevel.HR_ONLY)).thenReturn(entry(e -> { }));

        KnowledgeDocumentResponse added = service.addPolicy(hr, pdf(), "Leave Policy", ChunkAccessLevel.HR_ONLY);

        ArgumentCaptor<DocumentUploadRequest> upload = ArgumentCaptor.forClass(DocumentUploadRequest.class);
        verify(documents).store(upload.capture());
        DocumentUploadRequest request = upload.getValue();
        assertThat(request.organizationId()).isEqualTo(ORG);
        assertThat(request.ownerUserId()).isEqualTo("hr-user-71");
        assertThat(request.uploadedByUserId()).isEqualTo("hr-user-71");
        assertThat(request.documentType()).isEqualTo(DocumentType.COMPANY_POLICY);
        assertThat(request.relatedEntityType()).isEqualTo(KnowledgeManagementService.RELATED_ENTITY_TYPE);
        assertThat(request.fileName()).isEqualTo("leave-policy.pdf");
        assertThat(request.content()).isEqualTo(bytes("%PDF-1.7 leave policy"));
        assertThat(added.id()).isEqualTo(DOCUMENT);
        assertThat(added.pendingVersion().stage()).isEqualTo(KnowledgeIngestionStage.APPROVED);
        assertThat(added.actions().ingest()).isTrue();
        verify(documents, never()).delete(anyString());
    }

    @Test
    void rejectedApprovalsRemoveTheUploadButAmbiguousFailuresKeepIt() {
        KnowledgeManagementService service = enabled();
        when(documents.store(any(DocumentUploadRequest.class))).thenReturn(stored("stored-1"));
        when(approval.approve(hr, "stored-1", "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .thenThrow(new BusinessException("Document contains no extractable text"))
                .thenThrow(new IllegalStateException("unexpected"));

        assertThatThrownBy(() -> service.addPolicy(hr, pdf(), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage("Document contains no extractable text");
        verify(documents).delete("stored-1");

        org.mockito.Mockito.clearInvocations(documents);
        assertThatThrownBy(() -> service.addPolicy(hr, pdf(), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(IllegalStateException.class);
        verify(documents, never()).delete(anyString());
    }

    @Test
    void storageFailuresBecomeAFixedMessageWithoutStorageDetails() {
        when(documents.store(any(DocumentUploadRequest.class))).thenThrow(new ObjectStorageException(
                "Upload failed for organizations/org-acme-77/COMPANY_POLICY/secret-key", new RuntimeException("s3cret")));

        assertThatThrownBy(() -> enabled().addPolicy(hr, pdf(), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeManagementService.STORAGE_FAILED).hasNoCause();
        verifyNoInteractions(approval);
        assertThat(logText()).contains("ObjectStorageException").doesNotContain("secret-key", "s3cret", ORG);
    }

    @Test
    void replacementChecksTheEntryBeforeUploadingAndRemovesRejectedUploads() {
        KnowledgeManagementService service = enabled();
        when(repository.findByIdAndOrganizationId("missing", ORG)).thenReturn(Optional.empty());
        when(repository.findByIdAndOrganizationId("revoked", ORG)).thenReturn(Optional.of(revoked()));
        assertThrows(ResourceNotFoundException.class,
                () -> service.replacePolicy(hr, "missing", pdf(), ChunkAccessLevel.COMPANY_WIDE));
        assertThatThrownBy(() -> service.replacePolicy(hr, "revoked", pdf(), ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeApprovalService.KNOWLEDGE_DOCUMENT_REVOKED);
        verifyNoInteractions(documents, approval);

        when(repository.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(entry(e -> { })));
        when(documents.store(any(DocumentUploadRequest.class))).thenReturn(stored("stored-2"));
        when(approval.approveReplacement(hr, DOCUMENT, "stored-2", ChunkAccessLevel.HR_ONLY))
                .thenReturn(entry(e -> e.getPendingVersion().setSourceDocumentId("stored-2")))
                .thenThrow(new BusinessException(KnowledgeApprovalService.INGESTION_IN_PROGRESS));

        assertThat(service.replacePolicy(hr, DOCUMENT, pdf(), ChunkAccessLevel.HR_ONLY).pendingVersion().sourceDocumentId())
                .isEqualTo("stored-2");
        assertThatThrownBy(() -> service.replacePolicy(hr, DOCUMENT, pdf(), ChunkAccessLevel.HR_ONLY))
                .hasMessage(KnowledgeApprovalService.INGESTION_IN_PROGRESS);
        verify(documents).delete("stored-2");
    }

    @Test
    void acceptIncompleteTextPassesTheReviewedSourceDocument() {
        when(approval.acceptIncompleteExtraction(hr, DOCUMENT, "stored-1")).thenReturn(entry(e -> { }));

        assertThat(enabled().acceptIncompleteText(hr, DOCUMENT, "stored-1").id()).isEqualTo(DOCUMENT);
        verify(approval).acceptIncompleteExtraction(hr, DOCUMENT, "stored-1");
    }

    @Test
    void restorationDelegatesOnlyAfterManagementAuthorizationAndExposesSafeHistoryMetadata() {
        KnowledgeDocument restored = entry(e -> {
            KnowledgeDocumentVersion historical = KnowledgeDocumentVersion.builder()
                    .sourceDocumentId("stored-old").sourceDocumentName("leave-policy-previous.pdf")
                    .documentType(DocumentType.COMPANY_POLICY).accessLevel(ChunkAccessLevel.HR_ONLY)
                    .activatedAt(NOW.minusDays(4)).build();
            e.setVersionHistory(List.of(KnowledgeVersionHistoryEntry.builder().version(historical)
                    .supersededAt(NOW.minusDays(2)).build()));
        });
        when(approval.restoreHistoricalVersion(hr, DOCUMENT, "stored-old")).thenReturn(restored);

        KnowledgeDocumentResponse response = enabled().restoreHistoricalVersion(hr, DOCUMENT, "stored-old");

        verify(approval).restoreHistoricalVersion(hr, DOCUMENT, "stored-old");
        assertThat(response.previousVersions()).containsExactly(new KnowledgeDocumentResponse.HistoricalVersion(
                "stored-old", "leave-policy-previous.pdf", ChunkAccessLevel.HR_ONLY,
                NOW.minusDays(4), NOW.minusDays(2), true));
        assertThat(response.actions().restoreHistoricalVersion()).isTrue();
        assertThat(response.toString()).doesNotContain("approver-user-9", SHA, ORG);
    }

    // --- Ingestion, activation and revocation ---------------------------------------------------

    @Test
    void ingestionOutcomesBecomeFixedMessages() {
        KnowledgeManagementService service = enabled();
        KnowledgeDocument indexed = entry(e -> e.getPendingVersion().setStage(KnowledgeIngestionStage.INDEXED));
        when(ingestion.ingest(hr, DOCUMENT))
                .thenReturn(new KnowledgeIngestionService.IngestionResult(indexed, KnowledgeIngestionService.Outcome.INDEXED,
                        "ingestion-1", 12, null, true))
                .thenReturn(new KnowledgeIngestionService.IngestionResult(null, KnowledgeIngestionService.Outcome.FAILED,
                        "ingestion-2", 0, KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE, true))
                .thenThrow(new KnowledgeIngestionService.IngestionFailedException(KnowledgeIngestionService.STOPPED_UNEXPECTEDLY));
        when(repository.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(entry(e -> { })));

        KnowledgeOperationResponse first = service.ingest(hr, DOCUMENT);
        assertThat(first.outcome()).isEqualTo("INDEXED");
        assertThat(first.message()).contains("Indexed 12 sections");
        assertThat(first.document().actions().activate()).isTrue();

        KnowledgeOperationResponse failed = service.ingest(hr, DOCUMENT);
        assertThat(failed.outcome()).isEqualTo("FAILED");
        assertThat(failed.message()).isEqualTo("The embedding service is unavailable. Try again later.");
        assertThat(failed.document()).isNotNull();

        assertThatThrownBy(() -> service.ingest(hr, DOCUMENT)).isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeIngestionService.STOPPED_UNEXPECTEDLY).hasNoCause();
    }

    @Test
    void activationUsesTheReviewedIngestionAndExplainsEachOutcome() {
        KnowledgeManagementService service = enabled();
        when(repository.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(entry(e -> { })));
        when(activation.activate(hr, DOCUMENT, "ingestion-1"))
                .thenReturn(new KnowledgeActivationService.ActivationResult(
                        KnowledgeActivationService.ActivationOutcome.AWAITING_SEARCH_INDEX,
                        KnowledgeActivationService.CleanupStatus.NOT_ATTEMPTED))
                .thenReturn(new KnowledgeActivationService.ActivationResult(
                        KnowledgeActivationService.ActivationOutcome.ACTIVATED,
                        KnowledgeActivationService.CleanupStatus.INCOMPLETE));

        KnowledgeOperationResponse awaiting = service.activate(hr, DOCUMENT, "ingestion-1");
        KnowledgeOperationResponse activated = service.activate(hr, DOCUMENT, "ingestion-1");

        assertThat(awaiting.outcome()).isEqualTo("AWAITING_SEARCH_INDEX");
        assertThat(awaiting.message()).contains("search index has not caught up");
        assertThat(activated.outcome()).isEqualTo("ACTIVATED");
        assertThat(activated.message()).startsWith("This version is now active.").contains("older versions");
    }

    @Test
    void revocationReportsWhetherIndexedSectionsWereRemoved() {
        when(approval.revoke(hr, DOCUMENT))
                .thenReturn(new KnowledgeApprovalService.RevocationResult(revoked(), 0, false));

        KnowledgeOperationResponse result = enabled().revoke(hr, DOCUMENT);

        assertThat(result.outcome()).isEqualTo("REVOKED_CLEANUP_INCOMPLETE");
        assertThat(result.message()).contains("no longer informs answers");
        assertThat(result.document().actions()).isEqualTo(new KnowledgeDocumentResponse.Actions(false, false, false, false, false, true));
    }

    // --- Status view ----------------------------------------------------------------------------

    @Test
    void statusViewOffersOnlyActionsTheStateAndSwitchesAllow() {
        KnowledgeManagementService service = enabled();

        assertThat(actions(service, e -> {
            e.getPendingVersion().setStage(KnowledgeIngestionStage.NEEDS_REVIEW);
            e.getPendingVersion().setPagesWithoutText(1);
        }))
                .isEqualTo(new KnowledgeDocumentResponse.Actions(true, false, false, true, true, false));
        assertThat(actions(service, e -> { })).isEqualTo(new KnowledgeDocumentResponse.Actions(false, true, false, true, true, false));
        assertThat(actions(service, e -> failed(e, KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE)).ingest()).isTrue();
        assertThat(actions(service, e -> failed(e, KnowledgeIngestionFailure.CONTENT_CHANGED)).ingest()).isFalse();
        assertThat(actions(service, e -> ingesting(e, NOW.minusMinutes(5))))
                .isEqualTo(new KnowledgeDocumentResponse.Actions(false, false, false, false, true, false));
        assertThat(actions(service, e -> ingesting(e, NOW.minusMinutes(31))))
                .isEqualTo(new KnowledgeDocumentResponse.Actions(false, true, false, true, true, false));
        assertThat(actions(service, e -> e.getPendingVersion().setStage(KnowledgeIngestionStage.INDEXED)).activate()).isTrue();
        assertThat(actions(service(true, true, false), e -> e.getPendingVersion().setStage(KnowledgeIngestionStage.INDEXED))
                .activate()).isFalse();
        assertThat(actions(service, e -> e.setPendingVersion(null)))
                .isEqualTo(new KnowledgeDocumentResponse.Actions(false, false, false, true, true, false));
    }

    @Test
    void contentReviewIsOfferedOnlyWhileABoundDecisionIsOutstanding() {
        DocumentTextExtractor.ContentInspection flagged = new DocumentTextExtractor.ContentInspection(0, 0, 0, 0, 0, 2, 0);
        Consumer<KnowledgeDocument> suspicious = e -> {
            KnowledgeDocumentVersion pending = e.getPendingVersion();
            pending.setInstructionLikeContentMatches(2);
            pending.setContentReviewRequired(true);
            pending.setInspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(flagged));
            pending.setStage(KnowledgeIngestionStage.NEEDS_REVIEW);
        };

        KnowledgeDocumentResponse response = enabled().toResponse(entry(suspicious), NOW);
        assertThat(response.actions()).isEqualTo(
                new KnowledgeDocumentResponse.Actions(false, false, false, true, true, false, true));
        KnowledgeDocumentResponse.ContentReview review = response.pendingVersion().contentReview();
        assertThat(review.inspected()).isTrue();
        assertThat(review.required()).isTrue();
        assertThat(review.satisfied()).isFalse();
        assertThat(review.decision()).isNull();
        assertThat(review.instructionLikeContentMatches()).isEqualTo(2);
        // Counts and decisions only: no fingerprints, hashes or reviewer ids.
        assertThat(response.toString()).doesNotContain(SHA, flagged.canonicalForm(),
                KnowledgeContentReviewMetadata.fingerprint(flagged), "approver-user-9");

        // An incomplete PDF that also needs a content review offers both, independently.
        assertThat(actions(enabled(), suspicious.andThen(e -> e.getPendingVersion().setPagesWithoutText(1))))
                .isEqualTo(new KnowledgeDocumentResponse.Actions(true, false, false, true, true, false, true));
        // Once the bound decision is recorded it is no longer offered; the incomplete-text acceptance still is.
        Consumer<KnowledgeDocument> reviewed = suspicious.andThen(e -> {
            KnowledgeDocumentVersion pending = e.getPendingVersion();
            pending.setPagesWithoutText(1);
            pending.setContentReviewDecision(KnowledgeContentReviewDecision.INCLUDE_FLAGGED_TEXT);
            pending.setContentReviewedByUserId("hr-user-2");
            pending.setContentReviewedAt(NOW);
            pending.setReviewedSourceDocumentId(pending.getSourceDocumentId());
            pending.setReviewedFindingsSha256(pending.getInspectionFindingsSha256());
            pending.setReviewedIndexableTextSha256(pending.getIndexableTextSha256());
        });
        KnowledgeDocumentResponse afterReview = enabled().toResponse(entry(reviewed), NOW);
        assertThat(afterReview.actions().reviewContent()).isFalse();
        assertThat(afterReview.actions().acceptIncompleteText()).isTrue();
        assertThat(afterReview.pendingVersion().contentReview().satisfied()).isTrue();
        assertThat(afterReview.pendingVersion().contentReview().decision())
                .isEqualTo(KnowledgeContentReviewDecision.INCLUDE_FLAGGED_TEXT);

        // Never offered with management off, for a revoked entry, or for an inconsistent inspection.
        assertThat(actions(service(false, true, true), suspicious).reviewContent()).isFalse();
        assertThat(actions(enabled(), suspicious.andThen(e -> e.setStatus(KnowledgeDocumentStatus.REVOKED)))
                .reviewContent()).isFalse();
        assertThat(actions(enabled(), suspicious.andThen(e -> e.getPendingVersion()
                .setInspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(
                        DocumentTextExtractor.ContentInspection.none())))).reviewContent()).isFalse();
        // A clean version has a summary but nothing to review; a legacy version has no summary at all.
        assertThat(enabled().toResponse(entry(e -> { }), NOW).pendingVersion().contentReview().required()).isFalse();
        assertThat(enabled().toResponse(entry(e -> e.getPendingVersion().setInspectionFormatVersion(null)), NOW)
                .pendingVersion().contentReview()).isNull();
    }

    @Test
    void statusViewExplainsFailuresAndNeverExposesHashesUserIdsOrEmbeddingDetails() {
        KnowledgeDocument entry = entry(e -> {
            failed(e, KnowledgeIngestionFailure.EXTRACTION_FAILED);
            e.getPendingVersion().setExtractionFailureReason(DocumentExtractionException.Reason.ENCRYPTED);
        });

        KnowledgeDocumentResponse response = enabled().toResponse(entry, NOW);

        assertThat(response.pendingVersion().problem()).startsWith("Text could not be extracted")
                .contains("Encrypted documents cannot be extracted");
        assertThat(response.pendingVersion().retryable()).isFalse();
        assertThat(response.toString()).doesNotContain(SHA, "approver-user-9", "creator-user-8", "voyage-4-lite", ORG);
        KnowledgeDocumentResponse retryable = enabled().toResponse(
                entry(e -> failed(e, KnowledgeIngestionFailure.STORAGE_UNAVAILABLE)), NOW);
        assertThat(retryable.pendingVersion().retryable()).isTrue();
        assertThat(retryable.pendingVersion().problem()).contains("Try ingesting again");
    }

    @Test
    void listingIsOrganizationScoped() {
        KnowledgeDocument foreign = entry(e -> { });
        foreign.setId("knowledge-foreign");
        foreign.setOrganizationId("org-other-12");
        when(repository.findByOrganizationIdOrderByCreatedAtDesc(ORG)).thenReturn(List.of(entry(e -> { }), foreign));

        assertThat(enabled().list(hr)).extracting(KnowledgeDocumentResponse::id).containsExactly(DOCUMENT);
        verify(repository).findByOrganizationIdOrderByCreatedAtDesc(ORG);
    }

    // --- Helpers --------------------------------------------------------------------------------

    private KnowledgeManagementService enabled() {
        return service(true, true, true);
    }

    private KnowledgeManagementService service(boolean management, boolean ingestionEnabled, boolean activationEnabled) {
        return new KnowledgeManagementService(repository, approval, ingestion, activation, documents, CLOCK,
                management, ingestionEnabled, activationEnabled, false, false);
    }

    private List<Executable> allOperations(KnowledgeManagementService service, VerifiedMyHrActor actor) {
        return List.of(
                () -> service.settings(actor),
                () -> service.list(actor),
                () -> service.addPolicy(actor, pdf(), "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.replacePolicy(actor, DOCUMENT, pdf(), ChunkAccessLevel.COMPANY_WIDE),
                () -> service.restoreHistoricalVersion(actor, DOCUMENT, "stored-old"),
                () -> service.acceptIncompleteText(actor, DOCUMENT, "stored-1"),
                () -> service.ingest(actor, DOCUMENT),
                () -> service.activate(actor, DOCUMENT, "ingestion-1"),
                () -> service.revoke(actor, DOCUMENT));
    }

    private KnowledgeDocumentResponse.Actions actions(KnowledgeManagementService service, Consumer<KnowledgeDocument> change) {
        return service.toResponse(entry(change), NOW).actions();
    }

    private static void failed(KnowledgeDocument entry, KnowledgeIngestionFailure failure) {
        entry.getPendingVersion().setStage(KnowledgeIngestionStage.FAILED);
        entry.getPendingVersion().setFailureReason(failure);
        entry.getPendingVersion().setFailedAt(NOW.minusMinutes(1));
    }

    private static void ingesting(KnowledgeDocument entry, LocalDateTime startedAt) {
        entry.getPendingVersion().setStage(KnowledgeIngestionStage.INGESTING);
        entry.getPendingVersion().setIngestionId("ingestion-running");
        entry.getPendingVersion().setIngestionStartedAt(startedAt);
    }

    private static KnowledgeDocument entry(Consumer<KnowledgeDocument> change) {
        DocumentTextExtractor.ContentInspection inspection = DocumentTextExtractor.ContentInspection.none();
        KnowledgeDocumentVersion pending = KnowledgeDocumentVersion.builder()
                .sourceDocumentId("stored-1")
                .sourceDocumentName("leave-policy.pdf")
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.COMPANY_WIDE)
                .contentSha256(SHA)
                .approvedByUserId("approver-user-9")
                .approvedAt(NOW.minusHours(1))
                .pageCount(3)
                .pagesWithoutText(0)
                .inspectionFormatVersion(DocumentTextExtractor.ContentInspection.FORMAT_VERSION)
                .hiddenDocxRunsExcluded(0).suspiciousTextFragments(0).invisibleUnicodeCharacters(0)
                .unicodeTagCharactersRemoved(0).hiddenCommentBlocks(0).instructionLikeContentMatches(0)
                .pagesRequiringOcrReview(0).contentReviewRequired(false)
                .inspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection))
                .indexableTextSha256(KnowledgeContentReviewMetadata.textFingerprint("synthetic pending text"))
                .stage(KnowledgeIngestionStage.APPROVED)
                .embeddingModel("voyage-4-lite")
                .embeddingDimensions(1024)
                .build();
        KnowledgeDocument entry = KnowledgeDocument.builder()
                .id(DOCUMENT)
                .organizationId(ORG)
                .title("Leave Policy")
                .status(KnowledgeDocumentStatus.LIVE)
                .pendingVersion(pending)
                .sourceDocumentIds(new java.util.ArrayList<>(List.of("stored-1")))
                .createdByUserId("creator-user-8")
                .createdAt(NOW.minusHours(1))
                .version(3L)
                .build();
        change.accept(entry);
        return entry;
    }

    private static KnowledgeDocument revoked() {
        KnowledgeDocument entry = entry(e -> { });
        entry.setStatus(KnowledgeDocumentStatus.REVOKED);
        entry.setRevokedAt(NOW);
        return entry;
    }

    private static StoredDocument stored(String id) {
        return StoredDocument.builder().id(id).organizationId(ORG).documentType(DocumentType.COMPANY_POLICY).build();
    }

    private static MockMultipartFile pdf() {
        return file("leave-policy.pdf", bytes("%PDF-1.7 leave policy"));
    }

    private static MockMultipartFile file(String name, byte[] content) {
        return new MockMultipartFile("file", name, "application/pdf", content);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String logText() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
    }
}
