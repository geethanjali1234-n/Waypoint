package com.waypoint.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.entity.KnowledgeVersionHistoryEntry;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.StorageProvider;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.DocumentExtractionException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.KnowledgeChunkRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.DocumentTextExtractor.DocumentFormat;
import com.waypoint.service.DocumentTextExtractor.ExtractedText;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.waypoint.service.KnowledgeApprovalService.ACCESS_LEVEL_NOT_ALLOWED;
import static com.waypoint.service.KnowledgeApprovalService.AUTHENTICATION_REQUIRED;
import static com.waypoint.service.KnowledgeApprovalService.CONCURRENT_CHANGE;
import static com.waypoint.service.KnowledgeApprovalService.CONTENT_INSPECTION_REQUIRED;
import static com.waypoint.service.KnowledgeApprovalService.CONTENT_ALREADY_IN_KNOWLEDGE_BASE;
import static com.waypoint.service.KnowledgeApprovalService.DOCUMENT_ID_REQUIRED;
import static com.waypoint.service.KnowledgeApprovalService.DOCUMENT_NOT_FOUND;
import static com.waypoint.service.KnowledgeApprovalService.EXTRACTION_RESULT_INVALID;
import static com.waypoint.service.KnowledgeApprovalService.INGESTION_IN_PROGRESS;
import static com.waypoint.service.KnowledgeApprovalService.INGESTION_LEASE;
import static com.waypoint.service.KnowledgeApprovalService.INGESTION_STATE_UNRESOLVED;
import static com.waypoint.service.KnowledgeApprovalService.HISTORICAL_VERSION_ALREADY_ACTIVE;
import static com.waypoint.service.KnowledgeApprovalService.HISTORICAL_VERSION_CONTENT_CHANGED;
import static com.waypoint.service.KnowledgeApprovalService.HISTORICAL_VERSION_NOT_FOUND;
import static com.waypoint.service.KnowledgeApprovalService.KNOWLEDGE_DOCUMENT_NOT_FOUND;
import static com.waypoint.service.KnowledgeApprovalService.KNOWLEDGE_DOCUMENT_REVOKED;
import static com.waypoint.service.KnowledgeApprovalService.MAX_TITLE_LENGTH;
import static com.waypoint.service.KnowledgeApprovalService.NOTHING_AWAITING_REVIEW;
import static com.waypoint.service.KnowledgeApprovalService.NOT_A_KNOWLEDGE_MANAGER;
import static com.waypoint.service.KnowledgeApprovalService.NOT_COMPANY_POLICY;
import static com.waypoint.service.KnowledgeApprovalService.REVIEWED_VERSION_CHANGED;
import static com.waypoint.service.KnowledgeApprovalService.SOURCE_ALREADY_IN_KNOWLEDGE_BASE;
import static com.waypoint.service.KnowledgeApprovalService.SOURCE_METADATA_INCOMPLETE;
import static com.waypoint.service.KnowledgeApprovalService.SOURCE_NOT_ACTIVE;
import static com.waypoint.service.KnowledgeApprovalService.SOURCE_UNREADABLE;
import static com.waypoint.service.KnowledgeApprovalService.TITLE_REQUIRED;
import static com.waypoint.service.KnowledgeApprovalService.TITLE_TOO_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Repositories, the index guard and DocumentService are mocks; documents are synthetic bytes run
 * through the real DocumentTextExtractor. No database, object storage or AI provider is contacted.
 * Every test also checks that nothing sensitive reached the service's log.
 */
class KnowledgeApprovalServiceTest {

    private static final String ORG = "org-acme-77";
    private static final String OTHER_ORG = "org-other-12";
    private static final String HR_ID = "hr-user-41";
    private static final String DOC_ID = "stored-doc-1";
    private static final String DOC_V2 = "stored-doc-2";
    private static final String OLD_PENDING_DOC = "stored-doc-0";
    private static final String KD_ID = "kd-1";
    private static final String TEXT_CANARY = "TEXTCANARY91";
    private static final String FILE_CANARY = "FILECANARY53";
    private static final String FILE_NAME = "leave-policy-" + FILE_CANARY + ".txt";
    private static final byte[] POLICY =
            ("Leave Policy\nEmployees accrue leave monthly. " + TEXT_CANARY).getBytes(StandardCharsets.UTF_8);
    /** SHA-256 test vector for "abc" (FIPS 180-2). */
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 9, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    private final KnowledgeDocumentRepository knowledgeDocuments = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
    private final KnowledgeDocumentIndexGuard indexGuard = mock(KnowledgeDocumentIndexGuard.class);
    private final DocumentService documentService = mock(DocumentService.class);
    private final KnowledgeApprovalService service = serviceAt(CLOCK, new DocumentTextExtractor());
    private final VerifiedMyHrActor hr = TestActors.hr(HR_ID, ORG);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeApprovalService.class);

    @BeforeEach
    void setUp() {
        when(knowledgeDocuments.insert(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument inserted = invocation.getArgument(0);
            inserted.setId("kd-new");
            inserted.setVersion(0L);
            return inserted;
        });
        when(knowledgeDocuments.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        assertLogsRevealNothing();
    }

    // --- Authorization --------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = UserRole.class, mode = EnumSource.Mode.EXCLUDE, names = {"HR", "COMPANY_ADMIN"})
    void rejectsEveryOtherRoleForEveryOperation(UserRole role) {
        VerifiedMyHrActor actor = TestActors.actor("user-9", ORG, role);

        for (ThrowingCallable operation : everyOperation(actor)) {
            assertThatThrownBy(operation).isInstanceOf(ForbiddenException.class).hasMessage(NOT_A_KNOWLEDGE_MANAGER);
        }
        verifyNoInteractions(knowledgeDocuments, chunks, indexGuard, documentService);
    }

    @Test
    void waypointAdminIsNotAKnowledgeManager() {
        VerifiedMyHrActor platformAdmin = TestActors.actor("admin-1", ORG, UserRole.WAYPOINT_ADMIN);

        for (ThrowingCallable operation : everyOperation(platformAdmin)) {
            assertThatThrownBy(operation).isInstanceOf(ForbiddenException.class);
        }
        verifyNoInteractions(knowledgeDocuments, chunks, indexGuard, documentService);
    }

    @Test
    void rejectsAMissingActorForEveryOperation() {
        for (ThrowingCallable operation : everyOperation(null)) {
            assertThatThrownBy(operation).isInstanceOf(UnauthorizedException.class).hasMessage(AUTHENTICATION_REQUIRED);
        }
        verifyNoInteractions(knowledgeDocuments, chunks, indexGuard, documentService);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"HR", "COMPANY_ADMIN"})
    void hrAndCompanyAdminCanApprove(UserRole role) {
        givenStored(DOC_ID, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approve(TestActors.actor("manager-1", ORG, role), DOC_ID, "Leave Policy",
                ChunkAccessLevel.COMPANY_WIDE);

        assertThat(entry.getCreatedByUserId()).isEqualTo("manager-1");
        assertThat(entry.getPendingVersion().getApprovedByUserId()).isEqualTo("manager-1");
    }

    // --- approve: result ------------------------------------------------------------------------

    @Test
    void approvesACompanyPolicyAsALiveEntryWithAPendingVersion() {
        givenStored(DOC_ID, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approve(hr, DOC_ID, "  Leave Policy  ", ChunkAccessLevel.COMPANY_WIDE);

        ArgumentCaptor<KnowledgeDocument> inserted = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(knowledgeDocuments).insert(inserted.capture());
        assertThat(inserted.getValue()).isSameAs(entry);
        assertThat(entry.getId()).isEqualTo("kd-new");
        assertThat(entry.getOrganizationId()).isEqualTo(ORG);
        assertThat(entry.getTitle()).isEqualTo("Leave Policy");
        assertThat(entry.getStatus()).isEqualTo(KnowledgeDocumentStatus.LIVE);
        assertThat(entry.getActiveVersion()).isNull();
        assertThat(entry.getSourceDocumentIds()).containsExactly(DOC_ID);
        assertThat(entry.getCreatedByUserId()).isEqualTo(HR_ID);
        assertThat(entry.getCreatedAt()).isEqualTo(NOW);
        assertThat(entry.getRevokedByUserId()).isNull();
        assertThat(entry.getRevokedAt()).isNull();

        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        assertThat(pending.getSourceDocumentId()).isEqualTo(DOC_ID);
        assertThat(pending.getSourceDocumentName()).isEqualTo(FILE_NAME);
        assertThat(pending.getDocumentType()).isEqualTo(DocumentType.COMPANY_POLICY);
        assertThat(pending.getAccessLevel()).isEqualTo(ChunkAccessLevel.COMPANY_WIDE);
        assertThat(pending.getContentSha256()).isEqualTo(KnowledgeApprovalService.sha256(POLICY)).matches("[0-9a-f]{64}");
        assertThat(pending.getApprovedByUserId()).isEqualTo(HR_ID);
        assertThat(pending.getApprovedAt()).isEqualTo(NOW);
        assertThat(pending.getPageCount()).isZero();
        assertThat(pending.getPagesWithoutText()).isZero();
        assertThat(pending.getInspectionFormatVersion()).isEqualTo(DocumentTextExtractor.ContentInspection.FORMAT_VERSION);
        assertThat(pending.getContentReviewRequired()).isFalse();
        assertThat(pending.getInspectionFindingsSha256()).matches("[0-9a-f]{64}");
        assertThat(pending.getIndexableTextSha256()).matches("[0-9a-f]{64}");
        assertThat(pending.getHiddenDocxRunsExcluded()).isZero();
        assertThat(pending.getSuspiciousTextFragments()).isZero();
        assertThat(pending.getStage()).isEqualTo(KnowledgeIngestionStage.APPROVED);
        assertThat(pending.getIncompleteExtractionAcceptedByUserId()).isNull();
        // Ingestion fields stay empty: approval never embeds or writes chunks.
        assertThat(pending.getIngestionId()).isNull();
        assertThat(pending.getIngestionStartedAt()).isNull();
        assertThat(pending.getChunkCount()).isNull();
        assertThat(pending.getEmbeddingModel()).isNull();
        verifyNoInteractions(chunks);
    }

    @Test
    void hiddenDocxContentIsExcludedAndRequiresContentReviewBeforeIngestion() throws IOException {
        String hidden = "HIDDEN-DOCX-G2";
        byte[] content;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Visible leave policy.");
            XWPFRun hiddenRun = document.createParagraph().createRun();
            hiddenRun.setText(hidden);
            hiddenRun.setVanish(true);
            document.write(output);
            content = output.toByteArray();
        }
        givenStored(DOC_ID, "leave-policy.docx", content);

        KnowledgeDocumentVersion pending = service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE)
                .getPendingVersion();

        assertThat(pending.getStage()).isEqualTo(KnowledgeIngestionStage.NEEDS_REVIEW);
        assertThat(pending.getContentReviewRequired()).isTrue();
        assertThat(pending.getHiddenDocxRunsExcluded()).isEqualTo(1);
        assertThat(new DocumentTextExtractor().extract(content, "leave-policy.docx").text()).doesNotContain(hidden);
    }

    // --- historical restoration -----------------------------------------------------------------

    @Test
    void restoresHistoricalSourceAsAFreshPendingVersionWithoutReusingChunks() {
        KnowledgeDocument entry = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null);
        KnowledgeDocumentVersion historical = version(DOC_V2, KnowledgeIngestionStage.INDEXED);
        historical.setActivatedAt(NOW.minusDays(5));
        entry.setVersionHistory(List.of(KnowledgeVersionHistoryEntry.builder().version(historical)
                .supersededAt(NOW.minusDays(2)).supersededByIngestionId("ingestion-newer").build()));
        givenEntry(entry);
        byte[] restoredBytes = ("Earlier leave policy " + TEXT_CANARY).getBytes(StandardCharsets.UTF_8);
        historical.setContentSha256(KnowledgeApprovalService.sha256(restoredBytes));
        givenStored(DOC_V2, "earlier-policy.txt", restoredBytes);

        KnowledgeDocument restored = service.restoreHistoricalVersion(hr, KD_ID, DOC_V2);

        assertThat(restored).isSameAs(entry);
        assertThat(restored.getActiveVersion().getSourceDocumentId()).isEqualTo(DOC_ID);
        assertThat(restored.getPendingVersion().getSourceDocumentId()).isEqualTo(DOC_V2);
        assertThat(restored.getPendingVersion().getStage()).isEqualTo(KnowledgeIngestionStage.APPROVED);
        assertThat(restored.getPendingVersion().getIngestionId()).isNull();
        assertThat(restored.getPendingVersion().getRestoredFromSourceDocumentId()).isEqualTo(DOC_V2);
        assertThat(restored.getPendingVersion().getRestoredFromActivatedAt()).isEqualTo(NOW.minusDays(5));
        assertThat(restored.getSourceDocumentIds()).containsExactly(DOC_ID, DOC_V2);
        verify(knowledgeDocuments).save(entry);
        verifyNoInteractions(chunks);
    }

    @Test
    void restorationRejectsUnknownOrAlreadyActiveHistoryBeforeReadingAStoredDocument() {
        KnowledgeDocument entry = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null);
        KnowledgeDocumentVersion historical = version(DOC_V2, KnowledgeIngestionStage.INDEXED);
        entry.setVersionHistory(List.of(KnowledgeVersionHistoryEntry.builder().version(historical).build()));
        givenEntry(entry);

        assertThatThrownBy(() -> service.restoreHistoricalVersion(hr, KD_ID, "not-in-history"))
                .isInstanceOf(BusinessException.class).hasMessage(HISTORICAL_VERSION_NOT_FOUND);

        entry.setVersionHistory(List.of(KnowledgeVersionHistoryEntry.builder()
                .version(version(DOC_ID, KnowledgeIngestionStage.INDEXED)).build()));
        assertThatThrownBy(() -> service.restoreHistoricalVersion(hr, KD_ID, DOC_ID))
                .isInstanceOf(BusinessException.class).hasMessage(HISTORICAL_VERSION_ALREADY_ACTIVE);
        verifyNoInteractions(documentService, chunks);
    }

    @Test
    void restorationRejectsAHistoricalSourceWhoseCurrentBytesNoLongerMatchItsApprovedHash() {
        KnowledgeDocument entry = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null);
        KnowledgeDocumentVersion historical = version(DOC_V2, KnowledgeIngestionStage.INDEXED);
        historical.setContentSha256(KnowledgeApprovalService.sha256("approved old bytes".getBytes(StandardCharsets.UTF_8)));
        entry.setVersionHistory(List.of(KnowledgeVersionHistoryEntry.builder().version(historical).build()));
        givenEntry(entry);
        givenStored(DOC_V2, "changed-policy.txt", "changed bytes".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.restoreHistoricalVersion(hr, KD_ID, DOC_V2))
                .isInstanceOf(BusinessException.class).hasMessage(HISTORICAL_VERSION_CONTENT_CHANGED);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
        verifyNoInteractions(chunks);
    }

    @Test
    void storesNoDocumentText() {
        givenStored(DOC_ID, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE);

        assertThat(entry.toString()).doesNotContain(TEXT_CANARY).doesNotContain("Employees accrue leave");
    }

    @Test
    void pinsTheSha256OfTheStoredBytes() {
        givenStored(DOC_ID, "policy.txt", "abc".getBytes(StandardCharsets.US_ASCII));

        KnowledgeDocument entry = service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE);

        assertThat(entry.getPendingVersion().getContentSha256()).isEqualTo(ABC_SHA256);
        verify(knowledgeDocuments).existsByOrganizationIdAndStatusAndActiveVersionContentSha256(
                ORG, KnowledgeDocumentStatus.LIVE, ABC_SHA256);
        verify(knowledgeDocuments).existsByOrganizationIdAndStatusAndPendingVersionContentSha256(
                ORG, KnowledgeDocumentStatus.LIVE, ABC_SHA256);
    }

    @ParameterizedTest
    @EnumSource(value = ChunkAccessLevel.class, names = {"COMPANY_WIDE", "HR_ONLY"})
    void approvesCompanyWideAndHrOnlyAccess(ChunkAccessLevel accessLevel) {
        givenStored(DOC_ID, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approve(hr, DOC_ID, "Leave Policy", accessLevel);

        assertThat(entry.getPendingVersion().getAccessLevel()).isEqualTo(accessLevel);
    }

    // --- approve: input -------------------------------------------------------------------------

    @Test
    void rejectsAccessLevelsOtherThanCompanyWideAndHrOnly() {
        for (ChunkAccessLevel accessLevel : new ChunkAccessLevel[]{ChunkAccessLevel.EMPLOYEE_PRIVATE, null}) {
            assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", accessLevel))
                    .isInstanceOf(BusinessException.class).hasMessage(ACCESS_LEVEL_NOT_ALLOWED);
            assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, accessLevel))
                    .isInstanceOf(BusinessException.class).hasMessage(ACCESS_LEVEL_NOT_ALLOWED);
        }
        verifyNoInteractions(knowledgeDocuments, indexGuard, documentService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t\n"})
    void requiresATitle(String title) {
        assertThatThrownBy(() -> service.approve(hr, DOC_ID, title, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(TITLE_REQUIRED);
        verifyNoInteractions(knowledgeDocuments, indexGuard, documentService);
    }

    @Test
    void limitsTheTitleLength() {
        givenStored(DOC_ID, FILE_NAME, POLICY);

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "x".repeat(MAX_TITLE_LENGTH + 1), ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(TITLE_TOO_LONG);
        assertThat(service.approve(hr, DOC_ID, " " + "x".repeat(MAX_TITLE_LENGTH) + " ", ChunkAccessLevel.COMPANY_WIDE)
                .getTitle()).hasSize(MAX_TITLE_LENGTH);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void requiresDocumentIds(String id) {
        assertThatThrownBy(() -> service.approve(hr, id, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(DOCUMENT_ID_REQUIRED);
        assertThatThrownBy(() -> service.approveReplacement(hr, id, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(DOCUMENT_ID_REQUIRED);
        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, id, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(DOCUMENT_ID_REQUIRED);
        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, id, DOC_ID))
                .isInstanceOf(BusinessException.class).hasMessage(DOCUMENT_ID_REQUIRED);
        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, id))
                .isInstanceOf(BusinessException.class).hasMessage(DOCUMENT_ID_REQUIRED);
        assertThatThrownBy(() -> service.revoke(hr, id))
                .isInstanceOf(BusinessException.class).hasMessage(DOCUMENT_ID_REQUIRED);
        verifyNoInteractions(knowledgeDocuments, chunks, indexGuard, documentService);
    }

    // --- approve: index guard -------------------------------------------------------------------

    @Test
    void approvalIsBlockedWithoutTheUniqueIndex() {
        indexMissing();
        givenStored(DOC_ID, FILE_NAME, POLICY);

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeDocumentIndexGuard.UNAVAILABLE);
        verifyNoInteractions(knowledgeDocuments, documentService);
    }

    @Test
    void checksTheIndexBeforeReadingOrWritingAnything() {
        givenStored(DOC_ID, FILE_NAME, POLICY);

        service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE);

        InOrder order = inOrder(indexGuard, knowledgeDocuments, documentService);
        order.verify(indexGuard).requireUniqueLiveSourceIndex();
        order.verify(knowledgeDocuments).existsWithSourceDocument(ORG, KnowledgeDocumentStatus.LIVE, DOC_ID);
        order.verify(documentService).getMetadata(DOC_ID);
        order.verify(documentService).getStoredContent(DOC_ID);
        order.verify(knowledgeDocuments).insert(any(KnowledgeDocument.class));
    }

    // --- approve: stored document ---------------------------------------------------------------

    @Test
    void readsContentOnlyThroughGetStoredContent() {
        givenStored(DOC_ID, FILE_NAME, POLICY);

        service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE);

        verify(documentService).getStoredContent(DOC_ID);
        verify(documentService, never()).getContent(anyString());
    }

    @Test
    void anotherOrganizationsDocumentIsReportedAsNotFoundAndNeverRead() {
        givenStored(DOC_ID, FILE_NAME, POLICY, document -> document.setOrganizationId(OTHER_ORG));

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage(DOCUMENT_NOT_FOUND);
        verify(documentService, never()).getStoredContent(anyString());
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void missingDocumentIsReportedAsNotFound() {
        when(documentService.getMetadata(DOC_ID)).thenThrow(new ResourceNotFoundException("Document not found"));

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, mode = EnumSource.Mode.EXCLUDE, names = "COMPANY_POLICY")
    void approvesCompanyPoliciesOnly(DocumentType type) {
        givenStored(DOC_ID, FILE_NAME, POLICY, document -> document.setDocumentType(type));

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(NOT_COMPANY_POLICY);
        verify(documentService, never()).getStoredContent(anyString());
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void approvesActiveStoredDocumentsOnly() {
        for (DocumentStatus status : new DocumentStatus[]{DocumentStatus.SUPERSEDED, null}) {
            givenStored(DOC_ID, FILE_NAME, POLICY, document -> document.setStatus(status));

            assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                    .isInstanceOf(BusinessException.class).hasMessage(SOURCE_NOT_ACTIVE);
        }
        verify(documentService, never()).getStoredContent(anyString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteMetadata")
    void rejectsIncompleteMetadata(String description, Consumer<StoredDocument> change) {
        givenStored(DOC_ID, FILE_NAME, POLICY, change);

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(SOURCE_METADATA_INCOMPLETE);
        verify(documentService, never()).getStoredContent(anyString());
    }

    static Stream<Arguments> incompleteMetadata() {
        return Stream.of(
                Arguments.of("GridFS storage", change(document -> document.setStorageProvider(StorageProvider.GRIDFS))),
                Arguments.of("no storage provider", change(document -> document.setStorageProvider(null))),
                Arguments.of("no storage key", change(document -> document.setStorageKey(null))),
                Arguments.of("no file name", change(document -> document.setFileName(null))),
                Arguments.of("blank file name", change(document -> document.setFileName("  "))),
                Arguments.of("no file size", change(document -> document.setFileSize(null))),
                Arguments.of("zero file size", change(document -> document.setFileSize(0L))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataChangedWhileReading")
    void rejectsContentWhoseMetadataChangedAfterTheCheck(
            String description, Consumer<StoredDocument> change, Class<? extends RuntimeException> failure, String message) {
        when(documentService.getMetadata(DOC_ID)).thenAnswer(invocation -> stored(DOC_ID, FILE_NAME, POLICY.length, unchanged()));
        when(documentService.getStoredContent(DOC_ID)).thenAnswer(invocation -> new DocumentService.DocumentContent(
                stored(DOC_ID, FILE_NAME, POLICY.length, change), POLICY.clone(), "text/plain", POLICY.length));

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(failure).hasMessage(message);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    static Stream<Arguments> metadataChangedWhileReading() {
        return Stream.of(
                Arguments.of("moved to another organization", change(document -> document.setOrganizationId(OTHER_ORG)),
                        ResourceNotFoundException.class, DOCUMENT_NOT_FOUND),
                Arguments.of("different document returned", change(document -> document.setId("stored-doc-x")),
                        ResourceNotFoundException.class, DOCUMENT_NOT_FOUND),
                Arguments.of("type changed", change(document -> document.setDocumentType(DocumentType.PAYSTUB)),
                        BusinessException.class, NOT_COMPANY_POLICY),
                Arguments.of("superseded", change(document -> document.setStatus(DocumentStatus.SUPERSEDED)),
                        BusinessException.class, SOURCE_NOT_ACTIVE),
                Arguments.of("storage key changed", change(document -> document.setStorageKey("organizations/x/other")),
                        BusinessException.class, SOURCE_UNREADABLE),
                Arguments.of("file name changed", change(document -> document.setFileName("other.txt")),
                        BusinessException.class, SOURCE_UNREADABLE));
    }

    @Test
    void rejectsContentWhoseLengthDiffersFromTheRecordedSize() {
        givenStored(DOC_ID, FILE_NAME, POLICY);
        when(documentService.getStoredContent(DOC_ID)).thenAnswer(invocation -> new DocumentService.DocumentContent(
                stored(DOC_ID, FILE_NAME, POLICY.length, unchanged()), "short".getBytes(StandardCharsets.UTF_8), "text/plain", 5));

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(SOURCE_UNREADABLE);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void rejectsMissingContent() {
        givenStored(DOC_ID, FILE_NAME, POLICY);
        StoredDocument metadata = stored(DOC_ID, FILE_NAME, POLICY.length, unchanged());
        when(documentService.getStoredContent(DOC_ID)).thenReturn(
                null,
                new DocumentService.DocumentContent(metadata, null, "text/plain", 0),
                new DocumentService.DocumentContent(metadata, new byte[0], "text/plain", 0));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                    .isInstanceOf(BusinessException.class).hasMessage(SOURCE_UNREADABLE);
        }
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void storageFailuresBecomeAFixedMessageWithoutTheStorageKey() {
        givenStored(DOC_ID, FILE_NAME, POLICY);
        when(documentService.getStoredContent(DOC_ID)).thenThrow(new ObjectStorageException(
                "Failed to read organizations/" + ORG + "/company_policy/uuid-" + FILE_NAME,
                new IllegalStateException("bucket waypoint-secret-bucket")));

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class)
                .hasMessage(SOURCE_UNREADABLE)
                .hasNoCause();
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("ObjectStorageException");
    }

    // --- approve: duplicates --------------------------------------------------------------------

    @Test
    void rejectsAStoredDocumentAlreadyInALiveEntryBeforeReadingIt() {
        when(knowledgeDocuments.existsWithSourceDocument(ORG, KnowledgeDocumentStatus.LIVE, DOC_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(SOURCE_ALREADY_IN_KNOWLEDGE_BASE);
        verifyNoInteractions(documentService);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void rejectsContentAlreadyServedByAnotherEntry() {
        givenStored(DOC_ID, FILE_NAME, POLICY);
        when(knowledgeDocuments.existsByOrganizationIdAndStatusAndActiveVersionContentSha256(
                eq(ORG), eq(KnowledgeDocumentStatus.LIVE), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(CONTENT_ALREADY_IN_KNOWLEDGE_BASE);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void rejectsContentAlreadyAwaitingIngestionInAnotherEntry() {
        givenStored(DOC_ID, FILE_NAME, POLICY);
        when(knowledgeDocuments.existsByOrganizationIdAndStatusAndPendingVersionContentSha256(
                eq(ORG), eq(KnowledgeDocumentStatus.LIVE), anyString())).thenReturn(true);

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(CONTENT_ALREADY_IN_KNOWLEDGE_BASE);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void aConcurrentApprovalCaughtByTheUniqueIndexIsReportedAsADuplicate() {
        givenStored(DOC_ID, FILE_NAME, POLICY);
        when(knowledgeDocuments.insert(any(KnowledgeDocument.class)))
                .thenThrow(new DuplicateKeyException("E11000 duplicate key " + DOC_ID));

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class)
                .hasMessage(SOURCE_ALREADY_IN_KNOWLEDGE_BASE)
                .hasNoCause();
    }

    // --- approve: synthetic documents through the real extractor --------------------------------

    @Test
    void pdfWithPagesWithoutTextNeedsExplicitReview() throws IOException {
        givenStored(DOC_ID, "leave-policy.pdf", pdf("Leave policy page one", null, "Leave policy page three"));

        KnowledgeDocument entry = service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.HR_ONLY);

        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        assertThat(pending.getStage()).isEqualTo(KnowledgeIngestionStage.NEEDS_REVIEW);
        assertThat(pending.getPageCount()).isEqualTo(3);
        assertThat(pending.getPagesWithoutText()).isEqualTo(1);
        assertThat(pending.getIncompleteExtractionAcceptedByUserId()).isNull();
        assertThat(pending.getIncompleteExtractionAcceptedAt()).isNull();
    }

    @Test
    void pdfWithTextOnEveryPageIsApproved() throws IOException {
        givenStored(DOC_ID, "leave-policy.pdf", pdf("Leave policy page one", "Leave policy page two"));

        KnowledgeDocumentVersion pending = service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE)
                .getPendingVersion();

        assertThat(pending.getStage()).isEqualTo(KnowledgeIngestionStage.APPROVED);
        assertThat(pending.getPageCount()).isEqualTo(2);
        assertThat(pending.getPagesWithoutText()).isZero();
    }

    @Test
    void docxAndMarkdownAreApproved() throws IOException {
        givenStored(DOC_ID, "leave-policy.docx", docx("Leave Policy", "Employees accrue leave monthly."));
        givenStored(DOC_V2, "holidays.md", "# Holidays\n\nThe office closes on public holidays.".getBytes(StandardCharsets.UTF_8));

        assertThat(service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE).getPendingVersion().getStage())
                .isEqualTo(KnowledgeIngestionStage.APPROVED);
        assertThat(service.approve(hr, DOC_V2, "Holidays", ChunkAccessLevel.COMPANY_WIDE).getPendingVersion().getStage())
                .isEqualTo(KnowledgeIngestionStage.APPROVED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unusableDocuments")
    void rejectsDocumentsThatCannotBeExtracted(String fileName, byte[] content, DocumentExtractionException.Reason reason) {
        givenStored(DOC_ID, fileName, content);

        assertThatThrownBy(() -> service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class)
                .hasMessage(new DocumentExtractionException(reason).getMessage())
                .hasNoCause();
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    static Stream<Arguments> unusableDocuments() throws IOException {
        return Stream.of(
                Arguments.of("policy.pdf", POLICY, DocumentExtractionException.Reason.FORMAT_MISMATCH),
                Arguments.of("policy.png", POLICY, DocumentExtractionException.Reason.UNSUPPORTED_FORMAT),
                Arguments.of("policy.txt", " \n\t ".getBytes(StandardCharsets.UTF_8), DocumentExtractionException.Reason.NO_TEXT),
                Arguments.of("scanned.pdf", pdf(null, null), DocumentExtractionException.Reason.NO_TEXT));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentExtractions")
    void rejectsInconsistentExtractionResults(String description, ExtractedText result) {
        DocumentTextExtractor extractor = mock(DocumentTextExtractor.class);
        when(extractor.extract(any(), anyString())).thenReturn(result);
        givenStored(DOC_ID, FILE_NAME, POLICY);

        assertThatThrownBy(() -> serviceAt(CLOCK, extractor).approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(EXTRACTION_RESULT_INVALID);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
    }

    static Stream<Arguments> inconsistentExtractions() {
        return Stream.of(
                Arguments.of("no result", null),
                Arguments.of("blank text", new ExtractedText(DocumentFormat.PDF, "  ", 1, 0)),
                Arguments.of("more pages without text than pages", new ExtractedText(DocumentFormat.PDF, "text", 1, 2)),
                Arguments.of("negative pages without text", new ExtractedText(DocumentFormat.PDF, "text", 2, -1)),
                Arguments.of("pages without text outside a PDF", new ExtractedText(DocumentFormat.TEXT, "text", 1, 1)));
    }

    // --- approveReplacement ---------------------------------------------------------------------

    @Test
    void replacementBecomesThePendingVersionWhileTheActiveVersionKeepsServing() {
        KnowledgeDocumentVersion active = version(DOC_ID, KnowledgeIngestionStage.INDEXED);
        givenEntry(entry(active, null));
        givenStored(DOC_V2, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.HR_ONLY);

        verify(knowledgeDocuments).save(entry);
        verify(knowledgeDocuments, never()).insert(any(KnowledgeDocument.class));
        assertThat(entry.getVersion()).as("read version kept for the optimistic lock").isEqualTo(5L);
        assertThat(entry.getStatus()).isEqualTo(KnowledgeDocumentStatus.LIVE);
        assertThat(entry.getActiveVersion()).isSameAs(active);
        assertThat(entry.getSourceDocumentIds()).containsExactly(DOC_ID, DOC_V2);
        KnowledgeDocumentVersion pending = entry.getPendingVersion();
        assertThat(pending.getSourceDocumentId()).isEqualTo(DOC_V2);
        assertThat(pending.getAccessLevel()).isEqualTo(ChunkAccessLevel.HR_ONLY);
        assertThat(pending.getStage()).isEqualTo(KnowledgeIngestionStage.APPROVED);
        assertThat(pending.getApprovedByUserId()).isEqualTo(HR_ID);
        assertThat(pending.getApprovedAt()).isEqualTo(NOW);
        assertThat(pending.getContentSha256()).isEqualTo(KnowledgeApprovalService.sha256(POLICY));
        verify(documentService, never()).getContent(anyString());
        verifyNoInteractions(chunks);
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeIngestionStage.class, names = {"APPROVED", "NEEDS_REVIEW", "INDEXED", "FAILED"})
    void replacementSupersedesAPendingVersionThatIsNotIngesting(KnowledgeIngestionStage stage) {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), version(OLD_PENDING_DOC, stage)));
        givenStored(DOC_V2, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE);

        assertThat(entry.getPendingVersion().getSourceDocumentId()).isEqualTo(DOC_V2);
        assertThat(entry.getSourceDocumentIds()).containsExactly(DOC_ID, DOC_V2).doesNotContain(OLD_PENDING_DOC);
    }

    @Test
    void replacementOfAnEntryWithoutAnActiveVersionReplacesItsPendingVersion() {
        givenEntry(entry(null, version(OLD_PENDING_DOC, KnowledgeIngestionStage.FAILED)));
        givenStored(DOC_V2, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE);

        assertThat(entry.getActiveVersion()).isNull();
        assertThat(entry.getSourceDocumentIds()).containsExactly(DOC_V2);
    }

    @Test
    void ingestingWithoutAStartTimeIsUnresolvedAndBlocksReplacement() {
        KnowledgeDocumentVersion ingesting = ingesting(null);
        KnowledgeDocument stuck = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), ingesting);
        givenEntry(stuck);
        givenStored(DOC_V2, FILE_NAME, POLICY);
        // Even ten years later there is no lease to expire: a missing start is never treated as an expired lease.
        KnowledgeApprovalService muchLater = serviceAt(Clock.offset(CLOCK, Duration.ofDays(3650)), new DocumentTextExtractor());

        for (KnowledgeApprovalService approvals : List.of(service, muchLater)) {
            assertThatThrownBy(() -> approvals.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage(INGESTION_STATE_UNRESOLVED);
        }
        assertThat(stuck.getPendingVersion()).isSameAs(ingesting);
        assertThat(ingesting.getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(stuck.getSourceDocumentIds()).containsExactly(DOC_ID, OLD_PENDING_DOC);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
        verifyNoInteractions(documentService, chunks);
    }

    @Test
    void pendingVersionWithoutAStageIsUnresolvedAndBlocksReplacement() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), version(OLD_PENDING_DOC, null)));

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(INGESTION_STATE_UNRESOLVED);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
        verifyNoInteractions(documentService);
    }

    @ParameterizedTest(name = "started {0} minutes before now")
    @ValueSource(longs = {0, 1, 29, -5})
    void ingestionWithinItsLeaseBlocksReplacement(long minutesAgo) {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), ingesting(NOW.minusMinutes(minutesAgo))));

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(INGESTION_IN_PROGRESS);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
        verifyNoInteractions(documentService);
    }

    @Test
    void ingestionOneSecondShortOfItsLeaseStillBlocksReplacement() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED),
                ingesting(NOW.minus(INGESTION_LEASE).plusSeconds(1))));

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(INGESTION_IN_PROGRESS);
    }

    @ParameterizedTest(name = "started {0} minutes before now")
    @ValueSource(longs = {30, 31, 600})
    void ingestionWhoseLeaseExpiredIsSuperseded(long minutesAgo) {
        assertThat(INGESTION_LEASE).isEqualTo(Duration.ofMinutes(30));
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), ingesting(NOW.minusMinutes(minutesAgo))));
        givenStored(DOC_V2, FILE_NAME, POLICY);

        KnowledgeDocument entry = service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE);

        assertThat(entry.getPendingVersion().getSourceDocumentId()).isEqualTo(DOC_V2);
        assertThat(entry.getPendingVersion().getIngestionId()).isNull();
        assertThat(entry.getSourceDocumentIds()).containsExactly(DOC_ID, DOC_V2);
    }

    @Test
    void replacementIsBlockedWithoutTheUniqueIndex() {
        indexMissing();

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(KnowledgeDocumentIndexGuard.UNAVAILABLE);
        verifyNoInteractions(knowledgeDocuments, documentService);
    }

    @Test
    void replacementLooksUpTheEntryInTheActorsOrganizationOnly() {
        when(knowledgeDocuments.findByIdAndOrganizationId(KD_ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage(KNOWLEDGE_DOCUMENT_NOT_FOUND);
        verify(knowledgeDocuments).findByIdAndOrganizationId(KD_ID, ORG);
        verifyNoInteractions(documentService);
    }

    @Test
    void anEntryOfAnotherOrganizationIsNeverUsedEvenIfReturned() {
        KnowledgeDocument foreign = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), needsReview(DOC_V2));
        foreign.setOrganizationId(OTHER_ORG);
        givenEntry(foreign);

        for (ThrowingCallable operation : List.<ThrowingCallable>of(
                () -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE),
                () -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2),
                () -> service.revoke(hr, KD_ID))) {
            assertThatThrownBy(operation).isInstanceOf(ResourceNotFoundException.class).hasMessage(KNOWLEDGE_DOCUMENT_NOT_FOUND);
        }
        assertThat(foreign.getStatus()).isEqualTo(KnowledgeDocumentStatus.LIVE);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
        verifyNoInteractions(chunks, documentService);
    }

    @Test
    void revokedEntriesCannotBeReplaced() {
        KnowledgeDocument revoked = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null);
        revoked.setStatus(KnowledgeDocumentStatus.REVOKED);
        givenEntry(revoked);

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(KNOWLEDGE_DOCUMENT_REVOKED);
        verifyNoInteractions(documentService);
    }

    @Test
    void replacementWithADocumentAlreadyInTheKnowledgeBaseIsRejected() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        when(knowledgeDocuments.existsWithSourceDocument(ORG, KnowledgeDocumentStatus.LIVE, DOC_ID)).thenReturn(true);

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_ID, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(SOURCE_ALREADY_IN_KNOWLEDGE_BASE);
        verifyNoInteractions(documentService);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void replacementWithIdenticalContentIsRejected() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        givenStored(DOC_V2, FILE_NAME, POLICY);
        when(knowledgeDocuments.existsByOrganizationIdAndStatusAndActiveVersionContentSha256(
                ORG, KnowledgeDocumentStatus.LIVE, KnowledgeApprovalService.sha256(POLICY))).thenReturn(true);

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(CONTENT_ALREADY_IN_KNOWLEDGE_BASE);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void replacementValidatesTheStoredDocumentLikeApproval() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        givenStored(DOC_V2, FILE_NAME, POLICY, document -> document.setDocumentType(DocumentType.RESUME));

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(NOT_COMPANY_POLICY);
        verify(documentService, never()).getStoredContent(anyString());
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void replacementPdfWithPagesWithoutTextNeedsReview() throws IOException {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        givenStored(DOC_V2, "leave-policy-v2.pdf", pdf("Leave policy", null));

        KnowledgeDocumentVersion pending = service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE)
                .getPendingVersion();

        assertThat(pending.getStage()).isEqualTo(KnowledgeIngestionStage.NEEDS_REVIEW);
        assertThat(pending.getPageCount()).isEqualTo(2);
        assertThat(pending.getPagesWithoutText()).isEqualTo(1);
    }

    @Test
    void concurrentChangeDuringReplacementIsAConflict() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        givenStored(DOC_V2, FILE_NAME, POLICY);
        when(knowledgeDocuments.save(any(KnowledgeDocument.class)))
                .thenThrow(new OptimisticLockingFailureException("version 5 is stale"));

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(CONCURRENT_CHANGE).hasNoCause();
    }

    @Test
    void replacementCaughtByTheUniqueIndexIsReportedAsADuplicate() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        givenStored(DOC_V2, FILE_NAME, POLICY);
        when(knowledgeDocuments.save(any(KnowledgeDocument.class))).thenThrow(new DuplicateKeyException("E11000"));

        assertThatThrownBy(() -> service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE))
                .isInstanceOf(BusinessException.class).hasMessage(SOURCE_ALREADY_IN_KNOWLEDGE_BASE).hasNoCause();
    }

    // --- acceptIncompleteExtraction -------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"HR", "COMPANY_ADMIN"})
    void acceptingIncompleteExtractionApprovesTheReviewedVersion(UserRole role) {
        KnowledgeDocumentVersion review = needsReview(DOC_V2);
        KnowledgeDocument entry = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), review);
        givenEntry(entry);

        KnowledgeDocument saved = service.acceptIncompleteExtraction(TestActors.actor("manager-2", ORG, role), KD_ID, DOC_V2);

        verify(knowledgeDocuments).save(entry);
        assertThat(saved.getPendingVersion()).isSameAs(review);
        assertThat(review.getStage()).isEqualTo(KnowledgeIngestionStage.APPROVED);
        assertThat(review.getIncompleteExtractionAcceptedByUserId()).isEqualTo("manager-2");
        assertThat(review.getIncompleteExtractionAcceptedAt()).isEqualTo(NOW);
        assertThat(review.getPagesWithoutText()).isEqualTo(2);
        assertThat(saved.getVersion()).isEqualTo(5L);
        // A local metadata change only: no index check, stored-content read or chunk access.
        verifyNoInteractions(indexGuard, documentService, chunks);
    }

    @Test
    void acceptingIncompleteTextCannotBypassSuspiciousContentReview() {
        KnowledgeDocumentVersion review = needsReview(DOC_V2);
        applyInspection(review, new DocumentTextExtractor.ContentInspection(1, 0, 0, 0, 0, 0, 2), true, DOC_V2);
        KnowledgeDocument entry = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), review);
        givenEntry(entry);

        KnowledgeDocument saved = service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2);

        assertThat(saved.getPendingVersion().getStage()).isEqualTo(KnowledgeIngestionStage.NEEDS_REVIEW);
        assertThat(review.getIncompleteExtractionAcceptedByUserId()).isEqualTo(HR_ID);
        assertThat(review.getContentReviewDecision()).isNull();
    }

    @Test
    void legacyIncompleteVersionCannotBeAcceptedWithoutInspectionMetadata() {
        KnowledgeDocumentVersion review = needsReview(DOC_V2);
        review.setInspectionFormatVersion(null);
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), review));

        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2))
                .isInstanceOf(BusinessException.class).hasMessage(CONTENT_INSPECTION_REQUIRED);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void acceptanceRequiresAVersionAwaitingReview() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));

        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_ID))
                .isInstanceOf(BusinessException.class).hasMessage(NOTHING_AWAITING_REVIEW);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeIngestionStage.class, mode = EnumSource.Mode.EXCLUDE, names = "NEEDS_REVIEW")
    void acceptanceRejectsVersionsInOtherStages(KnowledgeIngestionStage stage) {
        KnowledgeDocumentVersion pending = needsReview(DOC_V2);
        pending.setStage(stage);
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), pending));

        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2))
                .isInstanceOf(BusinessException.class).hasMessage(NOTHING_AWAITING_REVIEW);
        assertThat(pending.getIncompleteExtractionAcceptedByUserId()).isNull();
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void acceptanceRejectsAVersionOtherThanTheOneReviewed() {
        KnowledgeDocumentVersion review = needsReview(DOC_V2);
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), review));

        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, "stored-doc-reviewed-earlier"))
                .isInstanceOf(BusinessException.class).hasMessage(REVIEWED_VERSION_CHANGED);
        assertThat(review.getStage()).isEqualTo(KnowledgeIngestionStage.NEEDS_REVIEW);
        assertThat(review.getIncompleteExtractionAcceptedByUserId()).isNull();
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void acceptanceRejectsAReviewWithoutRecordedMissingPages() {
        for (Integer pagesWithoutText : new Integer[]{null, 0}) {
            KnowledgeDocumentVersion review = needsReview(DOC_V2);
            review.setPagesWithoutText(pagesWithoutText);
            givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), review));

            assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2))
                    .isInstanceOf(BusinessException.class).hasMessage(EXTRACTION_RESULT_INVALID);
        }
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void acceptanceRejectsRevokedEntries() {
        KnowledgeDocument revoked = entry(null, needsReview(DOC_V2));
        revoked.setStatus(KnowledgeDocumentStatus.REVOKED);
        givenEntry(revoked);

        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2))
                .isInstanceOf(BusinessException.class).hasMessage(KNOWLEDGE_DOCUMENT_REVOKED);
        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
    }

    @Test
    void acceptanceOfAnUnknownEntryIsNotFound() {
        when(knowledgeDocuments.findByIdAndOrganizationId(KD_ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage(KNOWLEDGE_DOCUMENT_NOT_FOUND);
        verify(knowledgeDocuments).findByIdAndOrganizationId(KD_ID, ORG);
    }

    @Test
    void concurrentChangeDuringAcceptanceIsAConflict() {
        givenEntry(entry(null, needsReview(DOC_V2)));
        when(knowledgeDocuments.save(any(KnowledgeDocument.class)))
                .thenThrow(new OptimisticLockingFailureException("version 5 is stale"));

        assertThatThrownBy(() -> service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2))
                .isInstanceOf(BusinessException.class).hasMessage(CONCURRENT_CHANGE).hasNoCause();
    }

    // --- revoke ---------------------------------------------------------------------------------

    @Test
    void revokesTheEntryBeforeDeletingItsChunks() {
        KnowledgeDocument entry = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null);
        givenEntry(entry);
        List<KnowledgeDocumentStatus> statusWhenSaved = new ArrayList<>();
        when(knowledgeDocuments.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            KnowledgeDocument saved = invocation.getArgument(0);
            statusWhenSaved.add(saved.getStatus());
            return saved;
        });
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentId(ORG, KD_ID)).thenReturn(7L);

        KnowledgeApprovalService.RevocationResult result = service.revoke(hr, KD_ID);

        InOrder order = inOrder(knowledgeDocuments, chunks);
        order.verify(knowledgeDocuments).save(entry);
        order.verify(chunks).deleteByOrganizationIdAndKnowledgeDocumentId(ORG, KD_ID);
        assertThat(statusWhenSaved).containsExactly(KnowledgeDocumentStatus.REVOKED);
        assertThat(result.document()).isSameAs(entry);
        assertThat(result.chunksDeleted()).isEqualTo(7L);
        assertThat(result.chunkCleanupCompleted()).isTrue();
        assertThat(entry.getRevokedByUserId()).isEqualTo(HR_ID);
        assertThat(entry.getRevokedAt()).isEqualTo(NOW);
        assertThat(entry.getVersion()).isEqualTo(5L);
        verifyNoInteractions(documentService);
    }

    @Test
    void revocationDoesNotDependOnTheIndexGuard() {
        indexMissing();
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));

        assertThat(service.revoke(hr, KD_ID).document().getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
        verifyNoInteractions(indexGuard);
    }

    @Test
    void revocationWithAnIngestionInProgressStillRevokes() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), ingesting(NOW.minusMinutes(1))));

        assertThat(service.revoke(hr, KD_ID).document().getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
    }

    @Test
    void concurrentChangeDuringRevocationIsAConflictAndDeletesNothing() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        when(knowledgeDocuments.save(any(KnowledgeDocument.class)))
                .thenThrow(new OptimisticLockingFailureException("version 5 is stale"));

        assertThatThrownBy(() -> service.revoke(hr, KD_ID))
                .isInstanceOf(BusinessException.class).hasMessage(CONCURRENT_CHANGE).hasNoCause();
        verifyNoInteractions(chunks);
    }

    @Test
    void failedRevocationSaveDeletesNothing() {
        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        when(knowledgeDocuments.save(any(KnowledgeDocument.class)))
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> service.revoke(hr, KD_ID)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(chunks);
    }

    @Test
    void failedChunkCleanupLeavesTheEntryRevokedAndReportsIt() {
        KnowledgeDocument entry = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null);
        givenEntry(entry);
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentId(ORG, KD_ID))
                .thenThrow(new DataAccessResourceFailureException("mongodb+srv://user:s3cret@cluster.example"));

        KnowledgeApprovalService.RevocationResult result = service.revoke(hr, KD_ID);

        assertThat(result.document().getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
        assertThat(result.chunkCleanupCompleted()).isFalse();
        assertThat(result.chunksDeleted()).isZero();
        verify(knowledgeDocuments).save(entry);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("DataAccessResourceFailureException");
    }

    @Test
    void revokingAgainRetriesChunkCleanupWithoutChangingTheRevocation() {
        KnowledgeDocument revoked = entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null);
        revoked.setStatus(KnowledgeDocumentStatus.REVOKED);
        revoked.setRevokedByUserId("hr-user-earlier");
        revoked.setRevokedAt(NOW.minusDays(1));
        givenEntry(revoked);
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentId(ORG, KD_ID)).thenReturn(3L);

        KnowledgeApprovalService.RevocationResult result = service.revoke(hr, KD_ID);

        verify(knowledgeDocuments, never()).save(any(KnowledgeDocument.class));
        assertThat(result.chunksDeleted()).isEqualTo(3L);
        assertThat(result.chunkCleanupCompleted()).isTrue();
        assertThat(revoked.getRevokedByUserId()).isEqualTo("hr-user-earlier");
        assertThat(revoked.getRevokedAt()).isEqualTo(NOW.minusDays(1));
    }

    @Test
    void revokingAnUnknownEntryIsNotFoundAndDeletesNothing() {
        when(knowledgeDocuments.findByIdAndOrganizationId(KD_ID, ORG)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(hr, KD_ID))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage(KNOWLEDGE_DOCUMENT_NOT_FOUND);
        verify(knowledgeDocuments).findByIdAndOrganizationId(KD_ID, ORG);
        verifyNoInteractions(chunks);
    }

    // --- Logging --------------------------------------------------------------------------------

    @Test
    void approvalReplacementAcceptanceAndRevocationAreLoggedWithoutSensitiveValues() throws IOException {
        givenStored(DOC_ID, FILE_NAME, POLICY);
        service.approve(hr, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE);

        givenEntry(entry(version(DOC_ID, KnowledgeIngestionStage.INDEXED), null));
        givenStored(DOC_V2, "leave-policy-" + FILE_CANARY + ".pdf", pdf("Leave policy " + TEXT_CANARY, null));
        service.approveReplacement(hr, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE);
        service.acceptIncompleteExtraction(hr, KD_ID, DOC_V2);
        service.revoke(hr, KD_ID);

        // One line each for approval, replacement, acceptance and revocation, plus the chunk removal.
        assertThat(logs.list).hasSize(5);
        // assertLogsRevealNothing() checks their content after every test.
    }

    // --- Helpers --------------------------------------------------------------------------------

    private KnowledgeApprovalService serviceAt(Clock clock, DocumentTextExtractor extractor) {
        return new KnowledgeApprovalService(knowledgeDocuments, chunks, indexGuard, documentService, extractor, clock);
    }

    private List<ThrowingCallable> everyOperation(VerifiedMyHrActor actor) {
        return List.of(
                () -> service.approve(actor, DOC_ID, "Leave Policy", ChunkAccessLevel.COMPANY_WIDE),
                () -> service.approveReplacement(actor, KD_ID, DOC_V2, ChunkAccessLevel.COMPANY_WIDE),
                () -> service.restoreHistoricalVersion(actor, KD_ID, DOC_V2),
                () -> service.acceptIncompleteExtraction(actor, KD_ID, DOC_V2),
                () -> service.revoke(actor, KD_ID));
    }

    private void indexMissing() {
        doThrow(new BusinessException(KnowledgeDocumentIndexGuard.UNAVAILABLE)).when(indexGuard).requireUniqueLiveSourceIndex();
    }

    private static Consumer<StoredDocument> change(Consumer<StoredDocument> change) {
        return change;
    }

    private static Consumer<StoredDocument> unchanged() {
        return document -> {
        };
    }

    private void givenStored(String id, String fileName, byte[] content) {
        givenStored(id, fileName, content, unchanged());
    }

    /** Each read returns fresh metadata, as the repository would; change applies to every read. */
    private void givenStored(String id, String fileName, byte[] content, Consumer<StoredDocument> change) {
        when(documentService.getMetadata(id)).thenAnswer(invocation -> stored(id, fileName, content.length, change));
        when(documentService.getStoredContent(id)).thenAnswer(invocation -> new DocumentService.DocumentContent(
                stored(id, fileName, content.length, change), content.clone(), "application/octet-stream", content.length));
    }

    private static StoredDocument stored(String id, String fileName, long size, Consumer<StoredDocument> change) {
        StoredDocument document = StoredDocument.builder()
                .id(id)
                .organizationId(ORG)
                .documentType(DocumentType.COMPANY_POLICY)
                .fileName(fileName)
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/" + ORG + "/company_policy/uuid-" + fileName)
                .contentType("application/octet-stream")
                .fileSize(size)
                .version(1)
                .status(DocumentStatus.ACTIVE)
                .uploadedByUserId(HR_ID)
                .createdAt(NOW.minusDays(1))
                .build();
        change.accept(document);
        return document;
    }

    private void givenEntry(KnowledgeDocument entry) {
        when(knowledgeDocuments.findByIdAndOrganizationId(KD_ID, ORG)).thenReturn(Optional.of(entry));
    }

    private static KnowledgeDocument entry(KnowledgeDocumentVersion active, KnowledgeDocumentVersion pending) {
        List<String> sourceDocumentIds = new ArrayList<>();
        if (active != null) {
            sourceDocumentIds.add(active.getSourceDocumentId());
        }
        if (pending != null) {
            sourceDocumentIds.add(pending.getSourceDocumentId());
        }
        return KnowledgeDocument.builder()
                .id(KD_ID)
                .organizationId(ORG)
                .title("Leave Policy")
                .status(KnowledgeDocumentStatus.LIVE)
                .activeVersion(active)
                .pendingVersion(pending)
                .sourceDocumentIds(sourceDocumentIds)
                .createdByUserId("hr-user-earlier")
                .createdAt(NOW.minusDays(3))
                .version(5L)
                .build();
    }

    private static KnowledgeDocumentVersion version(String sourceDocumentId, KnowledgeIngestionStage stage) {
        DocumentTextExtractor.ContentInspection inspection = DocumentTextExtractor.ContentInspection.none();
        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(sourceDocumentId)
                .sourceDocumentName("policy-" + sourceDocumentId + ".txt")
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.COMPANY_WIDE)
                .contentSha256(KnowledgeApprovalService.sha256(sourceDocumentId.getBytes(StandardCharsets.UTF_8)))
                .approvedByUserId("hr-user-earlier")
                .approvedAt(NOW.minusDays(3))
                .pageCount(0)
                .pagesWithoutText(0)
                .inspectionFormatVersion(DocumentTextExtractor.ContentInspection.FORMAT_VERSION)
                .hiddenDocxRunsExcluded(0).suspiciousTextFragments(0).invisibleUnicodeCharacters(0)
                .unicodeTagCharactersRemoved(0).hiddenCommentBlocks(0).instructionLikeContentMatches(0)
                .pagesRequiringOcrReview(0).contentReviewRequired(false)
                .inspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection))
                .indexableTextSha256(KnowledgeContentReviewMetadata.textFingerprint(sourceDocumentId))
                .stage(stage)
                .ingestionId(stage == KnowledgeIngestionStage.INDEXED ? "ingestion-" + sourceDocumentId : null)
                .build();
    }

    private static KnowledgeDocumentVersion ingesting(LocalDateTime startedAt) {
        KnowledgeDocumentVersion version = version(OLD_PENDING_DOC, KnowledgeIngestionStage.INGESTING);
        version.setIngestionId("ingestion-2");
        version.setIngestionStartedAt(startedAt);
        return version;
    }

    private static KnowledgeDocumentVersion needsReview(String sourceDocumentId) {
        KnowledgeDocumentVersion version = version(sourceDocumentId, KnowledgeIngestionStage.NEEDS_REVIEW);
        version.setPageCount(5);
        version.setPagesWithoutText(2);
        return version;
    }

    private static void applyInspection(
            KnowledgeDocumentVersion version, DocumentTextExtractor.ContentInspection inspection,
            boolean reviewRequired, String indexableText) {
        version.setInspectionFormatVersion(DocumentTextExtractor.ContentInspection.FORMAT_VERSION);
        version.setHiddenDocxRunsExcluded(inspection.hiddenDocxRunsExcluded());
        version.setSuspiciousTextFragments(inspection.suspiciousTextFragments());
        version.setInvisibleUnicodeCharacters(inspection.invisibleUnicodeCharacters());
        version.setUnicodeTagCharactersRemoved(inspection.unicodeTagCharactersRemoved());
        version.setHiddenCommentBlocks(inspection.hiddenCommentBlocks());
        version.setInstructionLikeContentMatches(inspection.instructionLikeContentMatches());
        version.setPagesRequiringOcrReview(inspection.pagesRequiringOcrReview());
        version.setContentReviewRequired(reviewRequired);
        version.setInspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection));
        version.setIndexableTextSha256(KnowledgeContentReviewMetadata.textFingerprint(indexableText));
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** No log line may carry document text, file names, hashes, user or organization ids, secrets or a stack trace. */
    private void assertLogsRevealNothing() {
        List<String> forbidden = List.of(
                TEXT_CANARY, FILE_CANARY, "Employees accrue", "leave-policy", "policy.", "holidays", ".txt", ".pdf",
                ".docx", ".md", "organizations/", HR_ID, "hr-user-earlier", "manager-", ORG, OTHER_ORG,
                "secret", "s3cret", "mongodb");
        for (ILoggingEvent event : logs.list) {
            String message = event.getFormattedMessage();
            for (String value : forbidden) {
                assertThat(message).as("log line").doesNotContain(value);
            }
            assertThat(message).as("log line").doesNotMatch("(?s).*[0-9a-f]{64}.*");
            assertThat(event.getThrowableProxy()).as("stack trace logged with: " + message).isNull();
        }
    }

    /** One PDF page per argument; null is a page with no text, as a scan without OCR has. */
    private static byte[] pdf(String... pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text == null) {
                    continue;
                }
                try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(50, 720);
                    stream.showText(text);
                    stream.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] docx(String... paragraphs) throws IOException {
        try (XWPFDocument document = new XWPFDocument()) {
            for (String paragraph : paragraphs) {
                document.createParagraph().createRun().setText(paragraph);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        }
    }
}
