package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.entity.KnowledgeVersionHistoryEntry;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.KnowledgeActivationService.ActivationOutcome;
import com.waypoint.service.KnowledgeActivationService.CleanupStatus;
import com.waypoint.service.KnowledgeChunkIntegrityCheck.IntegrityCheckResult;
import com.waypoint.service.KnowledgeChunkIntegrityCheck.IntegrityOutcome;
import com.waypoint.setup.KnowledgeChunkSearchReadinessProbe;
import com.waypoint.setup.KnowledgeChunkSearchReadinessProbe.SearchReadinessOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Pure Mockito tests: no Spring context, MongoDB, vector search, storage, or embedding provider is started. */
@ExtendWith(MockitoExtension.class)
class KnowledgeActivationServiceTest {

    private static final String ORG = "org-acme-77";
    private static final String OTHER_ORG = "org-other-12";
    private static final String DOCUMENT = "knowledge-77";
    private static final String INGESTION = "ingestion-pending-77";
    private static final String OLD_INGESTION = "ingestion-active-22";
    private static final String SOURCE = "stored-policy-77";
    private static final String OLD_SOURCE = "stored-policy-22";
    private static final String MODEL = "voyage-4-lite";
    private static final String SHA = "a3f1b2c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f9a0b1c2d3e4f5a6b7c8d9e0f1a2";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 19, 12, 0);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);

    @Mock KnowledgeDocumentRepository documents;
    @Mock KnowledgeDocumentIndexGuard indexGuard;
    @Mock KnowledgeSourceDocumentReader sourceReader;
    @Mock KnowledgeChunkIntegrityCheck integrityCheck;
    @Mock KnowledgeChunkSearchReadinessProbe readinessProbe;
    @Mock KnowledgeChunkCleaner cleaner;
    @Mock EmbeddingService embeddingService;

    private final VerifiedMyHrActor hr = TestActors.hr("hr-user-71", ORG);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeActivationService.class);

    private KnowledgeActivationService service;

    @BeforeEach
    void setUp() {
        logger.addAppender(logs);
        logs.start();
        service = service(true);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
    }

    @Test
    void disabledFeatureFailsClosedBeforeTheIndexOrAnyDocumentRead() {
        KnowledgeActivationService disabled = service(false);

        assertThatThrownBy(() -> disabled.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeActivationService.ACTIVATION_DISABLED)
                .hasNoCause();

        verifyNoInteractions(indexGuard, documents, sourceReader, integrityCheck, readinessProbe, cleaner, embeddingService);
    }

    @Test
    void requiresVerifiedKnowledgeManagersAndExplicitIds() {
        assertThatThrownBy(() -> service.activate(null, DOCUMENT, INGESTION))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(KnowledgeActivationService.AUTHENTICATION_REQUIRED);
        assertThatThrownBy(() -> service.activate(TestActors.actor("employee-1", ORG, UserRole.EMPLOYEE), DOCUMENT, INGESTION))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(KnowledgeActivationService.NOT_A_KNOWLEDGE_MANAGER);
        assertThatThrownBy(() -> service.activate(hr, " ", INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.DOCUMENT_ID_REQUIRED);
        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, " "))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.INGESTION_ID_REQUIRED);

        verifyNoInteractions(indexGuard, documents);
    }

    @Test
    void companyAdminMayActivateButIndexGuardFailsBeforeAnyDocumentProcessing() {
        doThrow(new BusinessException(KnowledgeDocumentIndexGuard.UNAVAILABLE)).when(indexGuard)
                .requireUniqueLiveSourceIndex();

        assertThatThrownBy(() -> service.activate(TestActors.companyAdmin("admin-1", ORG), DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeDocumentIndexGuard.UNAVAILABLE);

        verify(indexGuard).requireUniqueLiveSourceIndex();
        verifyNoInteractions(documents, sourceReader, integrityCheck, readinessProbe, cleaner, embeddingService);
    }

    @Test
    void organizationScopedLookupDoesNotRevealAnotherOrganizationsDocument() {
        KnowledgeDocument other = entry(null, pending());
        other.setOrganizationId(OTHER_ORG);
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(KnowledgeActivationService.KNOWLEDGE_DOCUMENT_NOT_FOUND);

        verify(documents).findByIdAndOrganizationId(DOCUMENT, ORG);
        verifyNoInteractions(sourceReader, integrityCheck, readinessProbe, cleaner, embeddingService);
    }

    @Test
    void rejectsEveryNonIndexedPendingStageAndExpectedIngestionMismatchWithoutExternalReads() {
        for (KnowledgeIngestionStage stage : KnowledgeIngestionStage.values()) {
            if (stage == KnowledgeIngestionStage.INDEXED) {
                continue;
            }
            KnowledgeDocumentVersion pending = pending();
            pending.setStage(stage);
            assertRejectedBeforeExternalChecks(entry(null, pending));
        }
        KnowledgeDocumentVersion wrongIngestion = pending();
        wrongIngestion.setIngestionId("other-ingestion");
        assertRejectedBeforeExternalChecks(entry(null, wrongIngestion));
    }

    @Test
    void rejectsInvalidApprovalSourceExtractionAndIngestionMetadataWithoutSaving() {
        List<Consumer<KnowledgeDocumentVersion>> invalid = List.of(
                version -> version.setDocumentType(DocumentType.REPORT),
                version -> version.setAccessLevel(ChunkAccessLevel.EMPLOYEE_PRIVATE),
                version -> version.setSourceDocumentId(" "),
                version -> version.setSourceDocumentName(" "),
                version -> version.setContentSha256("not-a-sha"),
                version -> version.setApprovedByUserId(" "),
                version -> version.setApprovedAt(null),
                version -> version.setPageCount(-1),
                version -> version.setPagesWithoutText(3),
                version -> { version.setPagesWithoutText(1); version.setIncompleteExtractionAcceptedByUserId(null); },
                version -> version.setIngestionStartedAt(null),
                version -> version.setChunkCount(0),
                version -> version.setChunkCount(1001),
                version -> version.setEmbeddingModel(" "),
                version -> version.setEmbeddingDimensions(768),
                version -> version.setIndexedAt(null),
                version -> version.setActivatedByUserId("unexpected"));

        for (Consumer<KnowledgeDocumentVersion> mutation : invalid) {
            KnowledgeDocumentVersion pending = pending();
            mutation.accept(pending);
            assertRejectedBeforeExternalChecks(entry(null, pending));
        }
    }

    @Test
    void rejectsPendingVersionsWhoseConfiguredEmbeddingModelOrDimensionChanged() {
        KnowledgeDocument document = entry(null, pending());
        givenInitialAndReload(document);
        when(embeddingService.model()).thenReturn("different-model");

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeActivationService.EMBEDDING_CONFIGURATION_INVALID)
                .hasNoCause();

        verifyNoInteractions(sourceReader, integrityCheck, readinessProbe, cleaner);
        verify(documents, never()).save(any());
    }

    @Test
    void sourceVerificationAndIntegrityFailuresFailClosedWithoutCleanup() {
        KnowledgeDocument document = entry(null, pending());
        givenInitialAndReload(document);
        givenConfiguredEmbedding();
        when(sourceReader.read(eq(ORG), eq(SOURCE), eq(SHA)))
                .thenThrow(new IllegalStateException("mongodb://user:s3cret@host policy contents"));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.SOURCE_NOT_VERIFIED).hasNoCause();
        verifyNoInteractions(integrityCheck, readinessProbe, cleaner);
        verify(documents, never()).save(any());

        String logText = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(logText).doesNotContain("mongodb", "s3cret", "policy contents", SHA);
    }

    @Test
    void changedApprovedSourceFilenameFailsClosedWithoutIntegrityOrSave() {
        KnowledgeDocument document = entry(null, pending());
        givenInitialAndReload(document);
        givenConfiguredEmbedding();
        when(sourceReader.read(eq(ORG), eq(SOURCE), eq(SHA)))
                .thenReturn(verifiedSource("changed-policy.pdf"));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeActivationService.SOURCE_NOT_VERIFIED)
                .hasNoCause();

        verifyNoInteractions(integrityCheck, readinessProbe, cleaner);
        verify(documents, never()).save(any());
    }

    @Test
    void integrityFailureDoesNotAttemptVectorReadinessSaveOrCleanup() {
        KnowledgeDocument document = entry(null, pending());
        givenInitialAndReload(document);
        givenConfiguredEmbedding();
        when(sourceReader.read(eq(ORG), eq(SOURCE), eq(SHA))).thenReturn(verifiedSource("policy-private.pdf"));
        when(integrityCheck.check(any(KnowledgeChunkIntegrityCheck.Expectation.class)))
                .thenReturn(new IntegrityCheckResult(IntegrityOutcome.NOT_VERIFIED, 2, 1, Set.of()));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.CHUNK_INTEGRITY_NOT_VERIFIED);

        verify(readinessProbe, never()).probe(any());
        verify(documents, never()).save(any());
        verifyNoInteractions(cleaner);
    }

    @Test
    void readinessDelayReturnsAwaitingWithoutChangingTheDocumentOrCleaningChunks() {
        KnowledgeDocumentVersion pending = pending();
        KnowledgeDocument document = entry(null, pending);
        givenInitialAndReload(document);
        givenConfiguredEmbedding();
        when(sourceReader.read(eq(ORG), eq(SOURCE), eq(SHA))).thenReturn(verifiedSource("policy-private.pdf"));
        when(integrityCheck.check(any(KnowledgeChunkIntegrityCheck.Expectation.class))).thenReturn(verifiedIntegrity());
        when(readinessProbe.probe(any(KnowledgeChunkSearchReadinessProbe.ProbeRequest.class)))
                .thenReturn(new KnowledgeChunkSearchReadinessProbe.SearchReadinessReport(
                        SearchReadinessOutcome.AWAITING_SEARCH_INDEX, 2, 1));

        var result = service.activate(hr, DOCUMENT, INGESTION);

        assertThat(result.outcome()).isEqualTo(ActivationOutcome.AWAITING_SEARCH_INDEX);
        assertThat(result.cleanupStatus()).isEqualTo(CleanupStatus.NOT_ATTEMPTED);
        assertThat(document.getPendingVersion()).isSameAs(pending);
        assertThat(document.getPendingVersion().getStage()).isEqualTo(KnowledgeIngestionStage.INDEXED);
        assertThat(document.getPendingVersion().getIngestionId()).isEqualTo(INGESTION);
        verify(documents, never()).save(any());
        verifyNoInteractions(cleaner);
    }

    @Test
    void activatesFirstPolicyWithOneVersionCheckedSaveAndThenOrdinaryCleanup() {
        KnowledgeDocument document = entry(null, pending());
        givenInitialAndReload(document);
        givenSuccessfulPrerequisites();
        when(cleaner.cleanObsoleteChunks(ORG, DOCUMENT)).thenReturn(cleanupComplete());
        when(documents.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.activate(hr, DOCUMENT, INGESTION);

        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documents).save(saved.capture());
        KnowledgeDocument activated = saved.getValue();
        assertThat(result.outcome()).isEqualTo(ActivationOutcome.ACTIVATED);
        assertThat(result.cleanupStatus()).isEqualTo(CleanupStatus.COMPLETED);
        assertThat(activated.getVersion()).isEqualTo(5L);
        assertThat(activated.getActiveVersion()).isNotSameAs(document.getPendingVersion());
        assertThat(activated.getActiveVersion().getIngestionId()).isEqualTo(INGESTION);
        assertThat(activated.getActiveVersion().getActivatedAt()).isEqualTo(NOW);
        assertThat(activated.getActiveVersion().getActivatedByUserId()).isEqualTo("hr-user-71");
        assertThat(activated.getPendingVersion()).isNull();
        assertThat(activated.getSourceDocumentIds()).containsExactly(SOURCE);
        assertThat(activated.getVersionHistory()).isEmpty();

        InOrder order = inOrder(documents, cleaner);
        order.verify(documents).save(any(KnowledgeDocument.class));
        order.verify(cleaner).cleanObsoleteChunks(ORG, DOCUMENT);
    }

    @Test
    void replacementAppendsDeepCopiedHistoryAndSupportsLegacyNullHistory() {
        KnowledgeDocumentVersion old = active();
        KnowledgeDocumentVersion pending = pending();
        KnowledgeDocument document = entry(old, pending);
        document.setVersionHistory(null);
        givenInitialAndReload(document);
        givenSuccessfulPrerequisites();
        when(cleaner.cleanObsoleteChunks(ORG, DOCUMENT)).thenReturn(cleanupComplete());
        when(documents.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.activate(hr, DOCUMENT, INGESTION);

        ArgumentCaptor<KnowledgeDocument> saved = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(documents).save(saved.capture());
        KnowledgeDocument activated = saved.getValue();
        KnowledgeVersionHistoryEntry history = activated.getVersionHistory().getFirst();
        assertThat(activated.getVersionHistory()).hasSize(1);
        assertThat(history.getVersion()).isNotSameAs(old);
        assertThat(history.getVersion().getIngestionId()).isEqualTo(OLD_INGESTION);
        assertThat(history.getSupersededAt()).isEqualTo(NOW);
        assertThat(history.getSupersededByIngestionId()).isEqualTo(INGESTION);
        assertThat(history.getSupersededByUserId()).isEqualTo("hr-user-71");

        pending.setEmbeddingModel("tampered-after-save");
        old.setSourceDocumentId("tampered-old-source");
        assertThat(activated.getActiveVersion().getEmbeddingModel()).isEqualTo(MODEL);
        assertThat(history.getVersion().getSourceDocumentId()).isEqualTo(OLD_SOURCE);
    }

    @Test
    void historyLimitStopsReplacementWithoutDiscardingRecordsOrReadingSource() {
        KnowledgeDocument document = entry(active(), pending());
        List<KnowledgeVersionHistoryEntry> history = new ArrayList<>();
        for (int index = 0; index < KnowledgeDocument.MAX_VERSION_HISTORY_ENTRIES; index++) {
            history.add(KnowledgeVersionHistoryEntry.builder().version(active()).build());
        }
        document.setVersionHistory(history);
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.VERSION_HISTORY_FULL);

        assertThat(document.getVersionHistory()).hasSize(100);
        verifyNoInteractions(sourceReader, integrityCheck, readinessProbe, cleaner, embeddingService);
        verify(documents, never()).save(any());
    }

    @Test
    void duplicateActivationIsIdempotentAndMaySafelyRetryOrdinaryCleanup() {
        KnowledgeDocument confirmed = confirmedActive();
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(confirmed));
        when(cleaner.cleanObsoleteChunks(ORG, DOCUMENT)).thenReturn(cleanupIncomplete());

        var result = service.activate(hr, DOCUMENT, INGESTION);

        assertThat(result.outcome()).isEqualTo(ActivationOutcome.ALREADY_ACTIVE);
        assertThat(result.cleanupStatus()).isEqualTo(CleanupStatus.INCOMPLETE);
        verify(documents, never()).save(any());
        verify(cleaner).cleanObsoleteChunks(ORG, DOCUMENT);
        verifyNoInteractions(sourceReader, integrityCheck, readinessProbe, embeddingService);
    }

    @Test
    void optimisticConflictIsIdempotentOnlyAfterExpectedActivationIsConfirmed() {
        KnowledgeDocument initial = entry(null, pending());
        KnowledgeDocument confirmed = confirmedActive();
        givenSuccessfulPrerequisites();
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG))
                .thenReturn(Optional.of(initial), Optional.of(initial), Optional.of(confirmed));
        when(documents.save(any(KnowledgeDocument.class))).thenThrow(new OptimisticLockingFailureException("stale"));

        var result = service.activate(hr, DOCUMENT, INGESTION);

        assertThat(result.outcome()).isEqualTo(ActivationOutcome.ALREADY_ACTIVE);
        verify(cleaner).cleanObsoleteChunks(ORG, DOCUMENT);
    }

    @Test
    void revocationReplacementAndUnconfirmedSavesNeverCleanChunks() {
        KnowledgeDocument initial = entry(null, pending());
        KnowledgeDocument revoked = entry(null, pending());
        revoked.setStatus(KnowledgeDocumentStatus.REVOKED);
        givenSuccessfulPrerequisites();
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG))
                .thenReturn(Optional.of(initial), Optional.of(initial), Optional.of(revoked));
        when(documents.save(any(KnowledgeDocument.class))).thenThrow(new OptimisticLockingFailureException("stale"));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.KNOWLEDGE_DOCUMENT_REVOKED);
        verifyNoInteractions(cleaner);

        KnowledgeDocument replacement = entry(null, pending());
        replacement.getPendingVersion().setIngestionId("replacement-ingestion");
        resetForAnotherScenario();
        givenSuccessfulPrerequisites();
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG))
                .thenReturn(Optional.of(initial), Optional.of(initial), Optional.of(replacement));
        when(documents.save(any(KnowledgeDocument.class))).thenThrow(new DataAccessResourceFailureException("mongodb://secret"));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.ACTIVATION_CONFLICT).hasNoCause();
        verifyNoInteractions(cleaner);
    }

    @Test
    void ambiguousSaveWithoutAReadableConfirmationFailsSafely() {
        KnowledgeDocument initial = entry(null, pending());
        givenSuccessfulPrerequisites();
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG))
                .thenReturn(Optional.of(initial), Optional.of(initial))
                .thenThrow(new DataAccessResourceFailureException("mongodb://user:s3cret"));
        when(documents.save(any(KnowledgeDocument.class))).thenThrow(new DataAccessResourceFailureException("lost connection"));

        assertThatThrownBy(() -> service.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.ACTIVATION_UNCONFIRMED).hasNoCause();
        verifyNoInteractions(cleaner);
        String text = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(text).doesNotContain("mongodb", "s3cret", SOURCE, SHA);
    }

    private void assertRejectedBeforeExternalChecks(KnowledgeDocument document) {
        KnowledgeActivationService local = service(true);
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(document));

        assertThatThrownBy(() -> local.activate(hr, DOCUMENT, INGESTION))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeActivationService.PENDING_VERSION_INVALID);

        verifyNoInteractions(sourceReader, integrityCheck, readinessProbe, cleaner, embeddingService);
        verify(documents, never()).save(any());
        org.mockito.Mockito.clearInvocations(documents, sourceReader, integrityCheck, readinessProbe, cleaner, embeddingService);
    }

    private void resetForAnotherScenario() {
        org.mockito.Mockito.reset(documents, indexGuard, sourceReader, integrityCheck, readinessProbe, cleaner, embeddingService);
        service = service(true);
    }

    private void givenSuccessfulPrerequisites() {
        givenConfiguredEmbedding();
        when(sourceReader.read(eq(ORG), eq(SOURCE), eq(SHA))).thenReturn(verifiedSource("policy-private.pdf"));
        when(integrityCheck.check(any(KnowledgeChunkIntegrityCheck.Expectation.class))).thenReturn(verifiedIntegrity());
        when(readinessProbe.probe(any(KnowledgeChunkSearchReadinessProbe.ProbeRequest.class))).thenReturn(ready());
    }

    private void givenConfiguredEmbedding() {
        when(embeddingService.model()).thenReturn(MODEL);
        when(embeddingService.dimensions()).thenReturn(1024);
    }

    private KnowledgeActivationService service(boolean enabled) {
        return new KnowledgeActivationService(documents, indexGuard, sourceReader, integrityCheck, readinessProbe, cleaner,
                embeddingService, CLOCK, enabled);
    }

    private void givenInitialAndReload(KnowledgeDocument document) {
        when(documents.findByIdAndOrganizationId(DOCUMENT, ORG)).thenReturn(Optional.of(document));
    }

    private static IntegrityCheckResult verifiedIntegrity() {
        return new IntegrityCheckResult(IntegrityOutcome.VERIFIED, 2, 2, Set.of());
    }

    private static KnowledgeChunkSearchReadinessProbe.SearchReadinessReport ready() {
        return new KnowledgeChunkSearchReadinessProbe.SearchReadinessReport(SearchReadinessOutcome.READY, 2, 2);
    }

    private static KnowledgeChunkCleaner.CleanupResult cleanupComplete() {
        return new KnowledgeChunkCleaner.CleanupResult(Set.of("old"), Set.of(), Set.of(), true);
    }

    private static KnowledgeChunkCleaner.CleanupResult cleanupIncomplete() {
        return new KnowledgeChunkCleaner.CleanupResult(Set.of("old"), Set.of(), Set.of("old"), true);
    }

    private static KnowledgeSourceDocumentReader.VerifiedSourceContent verifiedSource(String fileName) {
        StoredDocument metadata = StoredDocument.builder()
                .id(SOURCE)
                .fileName(fileName)
                .build();
        return new KnowledgeSourceDocumentReader.VerifiedSourceContent(metadata, new byte[]{1}, SHA);
    }

    private static KnowledgeDocument entry(KnowledgeDocumentVersion active, KnowledgeDocumentVersion pending) {
        List<String> sourceIds = new ArrayList<>();
        if (active != null) {
            sourceIds.add(active.getSourceDocumentId());
        }
        if (pending != null) {
            sourceIds.add(pending.getSourceDocumentId());
        }
        return KnowledgeDocument.builder()
                .id(DOCUMENT)
                .organizationId(ORG)
                .title("Leave Policy")
                .status(KnowledgeDocumentStatus.LIVE)
                .activeVersion(active)
                .pendingVersion(pending)
                .sourceDocumentIds(sourceIds)
                .createdByUserId("hr-earlier")
                .createdAt(NOW.minusDays(1))
                .version(5L)
                .build();
    }

    private static KnowledgeDocumentVersion pending() {
        DocumentTextExtractor.ContentInspection inspection = DocumentTextExtractor.ContentInspection.none();
        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(SOURCE)
                .sourceDocumentName("policy-private.pdf")
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.COMPANY_WIDE)
                .contentSha256(SHA)
                .approvedByUserId("hr-earlier")
                .approvedAt(NOW.minusHours(2))
                .pageCount(2)
                .pagesWithoutText(0)
                .inspectionFormatVersion(DocumentTextExtractor.ContentInspection.FORMAT_VERSION)
                .hiddenDocxRunsExcluded(0).suspiciousTextFragments(0).invisibleUnicodeCharacters(0)
                .unicodeTagCharactersRemoved(0).hiddenCommentBlocks(0).instructionLikeContentMatches(0)
                .pagesRequiringOcrReview(0).contentReviewRequired(false)
                .inspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection))
                .indexableTextSha256(KnowledgeContentReviewMetadata.textFingerprint("synthetic pending text"))
                .stage(KnowledgeIngestionStage.INDEXED)
                .ingestionId(INGESTION)
                .ingestionStartedAt(NOW.minusMinutes(10))
                .chunkCount(2)
                .embeddingModel(MODEL)
                .embeddingDimensions(1024)
                .indexedAt(NOW.minusMinutes(5))
                .build();
    }

    private static KnowledgeDocumentVersion active() {
        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(OLD_SOURCE)
                .sourceDocumentName("previous-private.pdf")
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.COMPANY_WIDE)
                .contentSha256(SHA)
                .approvedByUserId("hr-earlier")
                .approvedAt(NOW.minusDays(10))
                .pageCount(1)
                .pagesWithoutText(0)
                .stage(KnowledgeIngestionStage.INDEXED)
                .ingestionId(OLD_INGESTION)
                .ingestionStartedAt(NOW.minusDays(9))
                .chunkCount(1)
                .embeddingModel(MODEL)
                .embeddingDimensions(1024)
                .indexedAt(NOW.minusDays(9).plusMinutes(1))
                .activatedAt(NOW.minusDays(9))
                .activatedByUserId("hr-earlier")
                .build();
    }

    private static KnowledgeDocument confirmedActive() {
        KnowledgeDocumentVersion version = pending();
        version.setActivatedAt(NOW);
        version.setActivatedByUserId("hr-user-71");
        return entry(version, null);
    }
}
