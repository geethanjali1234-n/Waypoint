package com.waypoint.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.entity.KnowledgeChunk;
import com.waypoint.entity.KnowledgeDocument;
import com.waypoint.entity.KnowledgeDocumentVersion;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeDocumentStatus;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.KnowledgeIngestionStage;
import com.waypoint.enums.KnowledgeContentReviewDecision;
import com.waypoint.enums.StorageProvider;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.KnowledgeChunkRepository;
import com.waypoint.repository.KnowledgeDocumentRepository;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import com.waypoint.service.DocumentChunkingService.TextChunk;
import com.waypoint.service.KnowledgeIngestionService.IngestionResult;
import com.waypoint.service.KnowledgeIngestionService.Outcome;
import com.waypoint.service.KnowledgeSourceDocumentReader.SourceDocumentException;
import com.waypoint.service.KnowledgeSourceDocumentReader.VerifiedSourceContent;
import com.waypoint.exception.DocumentExtractionException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentMatchers;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.waypoint.service.KnowledgeIngestionService.ALREADY_INDEXED;
import static com.waypoint.service.KnowledgeIngestionService.AUTHENTICATION_REQUIRED;
import static com.waypoint.service.KnowledgeIngestionService.CLAIM_CONFLICT;
import static com.waypoint.service.KnowledgeIngestionService.DOCUMENT_ID_REQUIRED;
import static com.waypoint.service.KnowledgeIngestionService.CONTENT_INSPECTION_REQUIRED;
import static com.waypoint.service.KnowledgeIngestionService.EMBEDDING_NOT_CONFIGURED;
import static com.waypoint.service.KnowledgeIngestionService.FAILURE_NOT_RETRYABLE;
import static com.waypoint.service.KnowledgeIngestionService.INCOMPLETE_EXTRACTION_NOT_ACCEPTED;
import static com.waypoint.service.KnowledgeIngestionService.INGESTION_IN_PROGRESS;
import static com.waypoint.service.KnowledgeIngestionService.INGESTION_STATE_UNRESOLVED;
import static com.waypoint.service.KnowledgeIngestionService.KNOWLEDGE_DOCUMENT_NOT_FOUND;
import static com.waypoint.service.KnowledgeIngestionService.KNOWLEDGE_DOCUMENT_REVOKED;
import static com.waypoint.service.KnowledgeIngestionService.NEEDS_REVIEW;
import static com.waypoint.service.KnowledgeIngestionService.NOTHING_TO_INGEST;
import static com.waypoint.service.KnowledgeIngestionService.NOT_A_KNOWLEDGE_MANAGER;
import static com.waypoint.service.KnowledgeIngestionService.PENDING_VERSION_INVALID;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Both repositories are backed by an in-memory fake: reads return copies and saves enforce
 * {@code @Version}, so concurrent revocations and replacements behave as they would in MongoDB.
 * The source reader and EmbeddingService are mocks; text extraction and chunking are the real
 * services on synthetic bytes unless a test needs an exact chunk count. Nothing is contacted.
 * After every test: no log line leaks anything, the active version and its chunks are untouched,
 * and no bulk chunk delete was used.
 */
class KnowledgeIngestionServiceTest {

    private static final String ORG = "org-acme-77";
    private static final String OTHER_ORG = "org-other-12";
    private static final String HR_ID = "hr-user-41";
    private static final String KD_ID = "kd-1";
    private static final String ACTIVE_DOC = "stored-doc-1";
    private static final String PENDING_DOC = "stored-doc-2";
    private static final String REPLACEMENT_DOC = "stored-doc-3";
    private static final String ACTIVE_INGESTION = "ingestion-active";
    private static final String MODEL = "voyage-4-lite";
    private static final String FILE_CANARY = "FILECANARY53";
    private static final String FILE_NAME = "leave-policy-" + FILE_CANARY + ".txt";
    private static final String TEXT_CANARY = "TEXTCANARY91";
    private static final String CREDENTIAL_CANARY = "mongodb+srv://user:s3cret@cluster.example";
    private static final byte[] POLICY =
            ("Leave Policy\nEmployees accrue leave monthly. " + TEXT_CANARY).getBytes(UTF_8);
    private static final String POLICY_SHA256 = KnowledgeApprovalService.sha256(POLICY);
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 18, 9, 0);
    private static final Clock CLOCK = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final KnowledgeDocumentRepository knowledgeDocuments = mock(KnowledgeDocumentRepository.class);
    private final KnowledgeChunkRepository chunks = mock(KnowledgeChunkRepository.class);
    private final KnowledgeChunkCleaner chunkCleaner = new KnowledgeChunkCleaner(knowledgeDocuments, chunks);
    /** Existing fixtures assume external embedding is approved; the Voyage gate has its own tests below. */
    private VoyageEmbeddingAuthorization voyageAuthorization =
            mock(VoyageEmbeddingAuthorization.class, invocation -> Boolean.TRUE);
    private final KnowledgeSourceDocumentReader reader = mock(KnowledgeSourceDocumentReader.class);
    private final EmbeddingService embeddings = mock(EmbeddingService.class);
    private final DocumentChunkingService mockChunker = mock(DocumentChunkingService.class);
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();
    private final DocumentChunkingService realChunker = new DocumentChunkingService(1500, 200);
    private final VerifiedMyHrActor hr = TestActors.hr(HR_ID, ORG);
    private KnowledgeIngestionService service;

    // --- In-memory database ---
    private KnowledgeDocument database;
    private final List<KnowledgeDocument> saves = new ArrayList<>();
    private final List<KnowledgeChunk> chunkStore = new ArrayList<>();
    private final List<String> deletedIngestionIds = new ArrayList<>();
    private final Map<Integer, RuntimeException> lookupFailures = new HashMap<>();
    private final Map<Integer, Runnable> beforeSave = new HashMap<>();
    private final Map<Integer, RuntimeException> saveFailures = new HashMap<>();
    private final Set<Integer> failAfterStoring = new HashSet<>();
    private final Map<Integer, Runnable> duringInsert = new HashMap<>();
    private final Map<Integer, RuntimeException> insertFailures = new HashMap<>();
    private boolean dropOneInsertedChunk;
    private RuntimeException countFailure;
    private RuntimeException deleteFailure;
    private int lookupCalls;
    private int saveCalls;
    private int insertCalls;
    private final List<Integer> insertBatchSizes = new ArrayList<>();

    // --- Embeddings ---
    private final Map<String, Integer> chunkIndexByText = new HashMap<>();
    private final Map<Integer, Runnable> duringEmbed = new HashMap<>();
    private final Map<Integer, RuntimeException> embedFailures = new HashMap<>();
    private final Map<Integer, Function<List<String>, List<List<Double>>>> embedResults = new HashMap<>();
    private final List<List<String>> embedRequests = new ArrayList<>();
    private final List<EmbeddingService.InputType> embedInputTypes = new ArrayList<>();
    private int embedCalls;
    private String modelName = MODEL;
    private boolean activeVersionChangedByTest;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeIngestionService.class);
    private final ListAppender<ILoggingEvent> cleanerLogs = new ListAppender<>();
    private final Logger cleanerLogger = (Logger) LoggerFactory.getLogger(KnowledgeChunkCleaner.class);

    @BeforeEach
    void setUp() {
        database = entry(activeVersion(), approvedVersion());
        for (int index = 0; index < 3; index++) {
            chunkStore.add(storedChunk(ACTIVE_INGESTION, index));
        }
        service = serviceWith(CLOCK, realChunker);
        stubDatabase();
        stubEmbeddings();
        when(reader.read(ORG, PENDING_DOC, POLICY_SHA256)).thenReturn(source(FILE_NAME, POLICY));
        logs.start();
        logger.addAppender(logs);
        cleanerLogs.start();
        cleanerLogger.addAppender(cleanerLogs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        cleanerLogger.detachAppender(cleanerLogs);
        assertLogsRevealNothing();
        if (!activeVersionChangedByTest) {
            assertThat(deletedIngestionIds).as("never deletes the active ingestion's chunks")
                    .doesNotContain(ACTIVE_INGESTION);
            assertThat(chunkStore.stream().filter(chunk -> ACTIVE_INGESTION.equals(chunk.getIngestionId())).count())
                    .as("active chunks untouched").isEqualTo(3);
        }
        if (!activeVersionChangedByTest && database != null) {
            assertThat(database.getActiveVersion()).as("active version untouched").isEqualTo(activeVersion());
        }
        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionIdNot(anyString(), anyString(), anyString());
        verify(chunks, never()).deleteByOrganizationIdAndKnowledgeDocumentId(anyString(), anyString());
        verify(chunks, never()).deleteByOrganizationIdAndSourceDocumentId(anyString(), anyString());
        verify(chunks, never()).deleteAll();
        verify(chunks, never()).deleteAll(ArgumentMatchers.<KnowledgeChunk>anyIterable());
    }

    // --- Authorization and organization isolation --------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = UserRole.class, mode = EnumSource.Mode.EXCLUDE, names = {"HR", "COMPANY_ADMIN"})
    void rejectsEveryOtherRole(UserRole role) {
        assertThatThrownBy(() -> service.ingest(TestActors.actor("user-9", ORG, role), KD_ID))
                .isInstanceOf(ForbiddenException.class).hasMessage(NOT_A_KNOWLEDGE_MANAGER);
        verifyNoInteractions(knowledgeDocuments, chunks, reader, embeddings);
    }

    @Test
    void waypointAdminCannotIngest() {
        assertThatThrownBy(() -> service.ingest(TestActors.actor("admin-1", ORG, UserRole.WAYPOINT_ADMIN), KD_ID))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(knowledgeDocuments, chunks, reader, embeddings);
    }

    @Test
    void requiresAnActor() {
        assertThatThrownBy(() -> service.ingest(null, KD_ID))
                .isInstanceOf(UnauthorizedException.class).hasMessage(AUTHENTICATION_REQUIRED);
        verifyNoInteractions(knowledgeDocuments, chunks, reader, embeddings);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void requiresAKnowledgeDocumentId(String id) {
        assertThatThrownBy(() -> service.ingest(hr, id))
                .isInstanceOf(BusinessException.class).hasMessage(DOCUMENT_ID_REQUIRED);
        verifyNoInteractions(knowledgeDocuments, chunks, reader, embeddings);
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"HR", "COMPANY_ADMIN"})
    void hrAndCompanyAdminCanIngest(UserRole role) {
        IngestionResult result = service.ingest(TestActors.actor("manager-1", ORG, role), KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.INDEXED);
    }

    @Test
    void looksUpTheEntryInTheActorsOrganizationOnly() {
        VerifiedMyHrActor otherOrganization = TestActors.hr("hr-user-99", OTHER_ORG);

        assertThatThrownBy(() -> service.ingest(otherOrganization, KD_ID))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage(KNOWLEDGE_DOCUMENT_NOT_FOUND);
        verify(knowledgeDocuments).findByIdAndOrganizationId(KD_ID, OTHER_ORG);
        assertNothingClaimed();
    }

    @Test
    void anEntryOfAnotherOrganizationIsNeverUsedEvenIfReturned() {
        KnowledgeDocument foreign = copy(database);
        foreign.setOrganizationId(OTHER_ORG);
        when(knowledgeDocuments.findByIdAndOrganizationId(KD_ID, ORG)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.ingest(hr, KD_ID))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage(KNOWLEDGE_DOCUMENT_NOT_FOUND);
        assertNothingClaimed();
    }

    @Test
    void readsTheApprovedSourceInTheEntrysOrganization() {
        service.ingest(hr, KD_ID);

        verify(reader).read(ORG, PENDING_DOC, POLICY_SHA256);
    }

    // --- Preconditions: nothing is claimed ---------------------------------------------------------

    @Test
    void revokedEntriesCannotBeIngested() {
        database.setStatus(KnowledgeDocumentStatus.REVOKED);

        assertRejected(KNOWLEDGE_DOCUMENT_REVOKED);
    }

    @Test
    void requiresAPendingVersion() {
        database.setPendingVersion(null);
        database.setSourceDocumentIds(new ArrayList<>(List.of(ACTIVE_DOC)));

        assertRejected(NOTHING_TO_INGEST);
    }

    @Test
    void aVersionAwaitingReviewOfIncompleteTextCannotBeIngested() {
        pending().setStage(KnowledgeIngestionStage.NEEDS_REVIEW);
        pending().setPageCount(3);
        pending().setPagesWithoutText(1);

        assertRejected(NEEDS_REVIEW);
    }

    @Test
    void missingPagesMustHaveBeenAcceptedByHr() {
        pending().setPageCount(3);
        pending().setPagesWithoutText(1);
        pending().setIncompleteExtractionAcceptedByUserId(null);

        assertRejected(INCOMPLETE_EXTRACTION_NOT_ACCEPTED);
    }

    @Test
    void legacyApprovedVersionWithoutInspectionMetadataCannotEnterIngestion() {
        pending().setInspectionFormatVersion(null);

        assertRejected(CONTENT_INSPECTION_REQUIRED);
        verifyNoInteractions(reader, chunks);
    }

    @Test
    void suspiciousVersionCannotEnterIngestionWithoutABoundContentReviewDecision() {
        DocumentTextExtractor.ContentInspection inspection =
                new DocumentTextExtractor.ContentInspection(1, 0, 0, 0, 0, 0, 0);
        pending().setHiddenDocxRunsExcluded(1);
        pending().setContentReviewRequired(true);
        pending().setInspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection));
        // This simulates a forged stage change; the ingestion boundary must still reject it.
        pending().setStage(KnowledgeIngestionStage.APPROVED);

        assertRejected(KnowledgeIngestionService.CONTENT_REVIEW_NOT_ACCEPTED);
        verifyNoInteractions(reader, chunks);
    }

    @Test
    void contentReviewDecisionMustBeBoundToTheCurrentSourceAndFingerprints() {
        DocumentTextExtractor.ContentInspection inspection =
                new DocumentTextExtractor.ContentInspection(1, 0, 0, 0, 0, 0, 0);
        pending().setHiddenDocxRunsExcluded(1);
        pending().setContentReviewRequired(true);
        pending().setInspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection));
        pending().setContentReviewDecision(KnowledgeContentReviewDecision.INCLUDE_FLAGGED_TEXT);
        pending().setContentReviewedByUserId(HR_ID);
        pending().setContentReviewedAt(NOW);
        pending().setReviewedSourceDocumentId("replaced-source");
        pending().setReviewedFindingsSha256(pending().getInspectionFindingsSha256());
        pending().setReviewedIndexableTextSha256(pending().getIndexableTextSha256());

        assertRejected(KnowledgeIngestionService.CONTENT_REVIEW_NOT_ACCEPTED);
        verifyNoInteractions(reader, chunks);
    }

    @Test
    void reExtractionMustMatchTheApprovedIndexableTextFingerprint() {
        pending().setIndexableTextSha256(KnowledgeApprovalService.sha256("different text".getBytes(UTF_8)));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertFailedWith(result, KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
        // Rejected before any text leaves the server for embedding, and before any chunk write.
        verify(embeddings, never()).embedBatch(ArgumentMatchers.anyList(), ArgumentMatchers.any());
        verify(chunks, never()).insert(ArgumentMatchers.<KnowledgeChunk>anyIterable());
    }

    @ParameterizedTest(name = "started {0} minutes before now")
    @ValueSource(longs = {0, 1, 29, -5})
    void anIngestionWithinItsLeaseCannotBeClaimedAgain(long minutesAgo) {
        ingesting("ingestion-running", NOW.minusMinutes(minutesAgo));

        assertRejected(INGESTION_IN_PROGRESS);
    }

    @Test
    void anIngestionOneSecondShortOfItsLeaseCannotBeClaimedAgain() {
        ingesting("ingestion-running", NOW.minus(KnowledgeApprovalService.INGESTION_LEASE).plusSeconds(1));

        assertRejected(INGESTION_IN_PROGRESS);
    }

    @Test
    void ingestingWithoutAStartIsUnresolvedEvenYearsLater() {
        ingesting("ingestion-running", null);
        service = serviceWith(Clock.offset(CLOCK, Duration.ofDays(3650)), realChunker);

        assertRejected(INGESTION_STATE_UNRESOLVED);
    }

    @Test
    void ingestingWithoutAnIngestionIdIsUnresolved() {
        ingesting(null, NOW.minusHours(2));

        assertRejected(INGESTION_STATE_UNRESOLVED);
    }

    @Test
    void aPendingVersionWithoutAStageIsUnresolved() {
        pending().setStage(null);

        assertRejected(INGESTION_STATE_UNRESOLVED);
    }

    @Test
    void anIndexedVersionIsNotIngestedAgain() {
        pending().setStage(KnowledgeIngestionStage.INDEXED);
        pending().setIngestionId("ingestion-indexed");

        assertRejected(ALREADY_INDEXED);
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeIngestionFailure.class, mode = EnumSource.Mode.EXCLUDE, names = {
            "STORAGE_UNAVAILABLE", "EMBEDDING_CONFIGURATION", "EMBEDDING_REQUEST_REJECTED",
            "EMBEDDING_UNAVAILABLE", "INVALID_EMBEDDING", "CHUNK_WRITE_FAILED", "EXTERNAL_PROCESSING_NOT_APPROVED"})
    void failuresThatNeedAReplacementAreNotRetried(KnowledgeIngestionFailure failure) {
        failed("ingestion-failed", failure);

        assertRejected(FAILURE_NOT_RETRYABLE);
    }

    @Test
    void aFailureWithoutAReasonIsNotRetried() {
        failed("ingestion-failed", null);

        assertRejected(FAILURE_NOT_RETRYABLE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidPendingVersions")
    void rejectsAnIncompleteOrInvalidApprovalRecord(String description, Consumer<KnowledgeDocument> change) {
        change.accept(database);

        assertRejected(PENDING_VERSION_INVALID);
    }

    static Stream<Arguments> invalidPendingVersions() {
        return Stream.of(
                invalid("not a company policy", entry -> entry.getPendingVersion().setDocumentType(DocumentType.REPORT)),
                invalid("no document type", entry -> entry.getPendingVersion().setDocumentType(null)),
                invalid("employee-private access",
                        entry -> entry.getPendingVersion().setAccessLevel(ChunkAccessLevel.EMPLOYEE_PRIVATE)),
                invalid("no access level", entry -> entry.getPendingVersion().setAccessLevel(null)),
                invalid("no hash", entry -> entry.getPendingVersion().setContentSha256(null)),
                invalid("uppercase hash", entry -> entry.getPendingVersion().setContentSha256(POLICY_SHA256.toUpperCase())),
                invalid("short hash", entry -> entry.getPendingVersion().setContentSha256(POLICY_SHA256.substring(1))),
                invalid("no approver", entry -> entry.getPendingVersion().setApprovedByUserId(null)),
                invalid("no source document", entry -> entry.getPendingVersion().setSourceDocumentId(null)),
                invalid("no source name", entry -> entry.getPendingVersion().setSourceDocumentName(" ")),
                invalid("source not held by the entry",
                        entry -> entry.setSourceDocumentIds(new ArrayList<>(List.of(ACTIVE_DOC)))),
                invalid("no source list", entry -> entry.setSourceDocumentIds(null)),
                invalid("no page count", entry -> entry.getPendingVersion().setPageCount(null)),
                invalid("no pages-without-text count", entry -> entry.getPendingVersion().setPagesWithoutText(null)),
                invalid("more pages without text than pages", entry -> {
                    entry.getPendingVersion().setPageCount(1);
                    entry.getPendingVersion().setPagesWithoutText(2);
                    entry.getPendingVersion().setIncompleteExtractionAcceptedByUserId(HR_ID);
                }));
    }

    @Test
    void requiresAConfiguredModelWith1024Dimensions() {
        for (String model : Arrays.asList(null, "", "  ")) {
            when(embeddings.model()).thenReturn(model);
            assertRejected(EMBEDDING_NOT_CONFIGURED);
        }
        when(embeddings.model()).thenReturn(MODEL);
        when(embeddings.dimensions()).thenReturn(1536);

        assertRejected(EMBEDDING_NOT_CONFIGURED);
    }

    // --- External embedding approval (Voyage AI) ---------------------------------------------------

    @Test
    void hrOnlyTextIsNeverSentToVoyageWithoutTheOrganizationsApprovalAndNothingIsClaimed() {
        pending().setAccessLevel(ChunkAccessLevel.HR_ONLY);
        com.waypoint.repository.ExternalProcessingApprovalRepository approvals =
                mock(com.waypoint.repository.ExternalProcessingApprovalRepository.class);
        voyageAuthorization = new VoyageEmbeddingAuthorization(approvals);
        service = serviceWith(CLOCK, realChunker);
        org.mockito.Mockito.clearInvocations(knowledgeDocuments);

        assertThatThrownBy(() -> service.ingest(hr, KD_ID)).isInstanceOf(BusinessException.class)
                .hasMessage(KnowledgeIngestionService.EMBEDDING_NOT_APPROVED);
        verify(knowledgeDocuments, never()).save(any());
        verifyNoInteractions(embeddings, reader);

        // A Claude approval, or a Voyage approval for company-wide content only, does not cover HR-only text.
        when(approvals.findByOrganizationIdAndProviderAndStatus(ORG, com.waypoint.enums.ExternalAiProvider.VOYAGE_AI,
                com.waypoint.enums.ExternalProcessingApprovalStatus.ACTIVE)).thenReturn(List.of(
                voyageApproval(java.util.Set.of(ChunkAccessLevel.COMPANY_WIDE))));
        assertThatThrownBy(() -> service.ingest(hr, KD_ID)).hasMessage(KnowledgeIngestionService.EMBEDDING_NOT_APPROVED);
        verifyNoInteractions(embeddings);

        // With the organization's recorded Voyage approval for HR-only content, it is indexed as before.
        when(approvals.findByOrganizationIdAndProviderAndStatus(ORG, com.waypoint.enums.ExternalAiProvider.VOYAGE_AI,
                com.waypoint.enums.ExternalProcessingApprovalStatus.ACTIVE)).thenReturn(List.of(
                voyageApproval(java.util.Set.of(ChunkAccessLevel.COMPANY_WIDE, ChunkAccessLevel.HR_ONLY))));
        assertThat(service.ingest(hr, KD_ID).outcome()).isEqualTo(Outcome.INDEXED);
    }

    @Test
    void companyWideTextNeedsNoAdditionalApprovalBeyondTheIngestionSwitch() {
        pending().setAccessLevel(ChunkAccessLevel.COMPANY_WIDE);
        voyageAuthorization = new VoyageEmbeddingAuthorization(
                mock(com.waypoint.repository.ExternalProcessingApprovalRepository.class));
        service = serviceWith(CLOCK, realChunker);

        assertThat(service.ingest(hr, KD_ID).outcome()).isEqualTo(Outcome.INDEXED);
    }

    @Test
    void anApprovalRevokedAfterTheClaimStopsTheIngestionBeforeAnyTextIsSent() {
        pending().setAccessLevel(ChunkAccessLevel.HR_ONLY);
        voyageAuthorization = mock(VoyageEmbeddingAuthorization.class);
        when(voyageAuthorization.permitsDocumentEmbedding(ORG, ChunkAccessLevel.HR_ONLY)).thenReturn(true, false);
        service = serviceWith(CLOCK, realChunker);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.failure()).isEqualTo(KnowledgeIngestionFailure.EXTERNAL_PROCESSING_NOT_APPROVED);
        verify(embeddings, never()).embedBatch(anyList(), any());
    }

    private static com.waypoint.entity.ExternalProcessingApproval voyageApproval(java.util.Set<ChunkAccessLevel> levels) {
        return com.waypoint.entity.ExternalProcessingApproval.builder().organizationId(ORG)
                .provider(com.waypoint.enums.ExternalAiProvider.VOYAGE_AI)
                .scope(com.waypoint.enums.ExternalProcessingApprovalScope.ORGANIZATION)
                .status(com.waypoint.enums.ExternalProcessingApprovalStatus.ACTIVE).accessLevels(levels).build();
    }

    @Test
    void retryableFailuresAreTheApprovedSet() {
        assertThat(KnowledgeIngestionService.RETRYABLE_FAILURES).containsExactlyInAnyOrder(
                KnowledgeIngestionFailure.STORAGE_UNAVAILABLE,
                KnowledgeIngestionFailure.EMBEDDING_CONFIGURATION,
                KnowledgeIngestionFailure.EMBEDDING_REQUEST_REJECTED,
                KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE,
                KnowledgeIngestionFailure.INVALID_EMBEDDING,
                KnowledgeIngestionFailure.CHUNK_WRITE_FAILED,
                KnowledgeIngestionFailure.EXTERNAL_PROCESSING_NOT_APPROVED);
        assertThatThrownBy(() -> KnowledgeIngestionService.RETRYABLE_FAILURES.add(KnowledgeIngestionFailure.CONTENT_CHANGED))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void limitsMatchTheApprovedDesignAndTheVectorIndex() throws ReflectiveOperationException {
        assertThat(KnowledgeIngestionService.EMBEDDING_BATCH_SIZE).isEqualTo(32);
        assertThat(KnowledgeIngestionService.CHUNK_WRITE_BATCH_SIZE).isEqualTo(100);
        assertThat(KnowledgeIngestionService.MAX_CHUNKS).isEqualTo(1000);
        Field indexDimensions = Class.forName("com.waypoint.setup.KnowledgeChunkVectorIndexSetup")
                .getDeclaredField("NUM_DIMENSIONS");
        indexDimensions.setAccessible(true);
        assertThat(KnowledgeIngestionService.REQUIRED_DIMENSIONS).isEqualTo(indexDimensions.getInt(null));
    }

    // --- Claim, duplicates and retries -------------------------------------------------------------

    @Test
    void claimsTheVersionBeforeReadingTheSource() {
        IngestionResult result = service.ingest(hr, KD_ID);

        KnowledgeDocument claim = saves.get(0);
        KnowledgeDocumentVersion claimed = claim.getPendingVersion();
        assertThat(claimed.getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(claimed.getIngestionId()).matches(UUID_FORMAT).isEqualTo(result.ingestionId());
        assertThat(claimed.getIngestionStartedAt()).isEqualTo(NOW);
        assertThat(claim.getVersion()).isEqualTo(6L);
        assertThat(claim.getActiveVersion()).isEqualTo(activeVersion());
        InOrder order = inOrder(knowledgeDocuments, reader);
        order.verify(knowledgeDocuments).save(any(KnowledgeDocument.class));
        order.verify(reader).read(ORG, PENDING_DOC, POLICY_SHA256);
    }

    @Test
    void losingTheClaimRaceChangesNothingAndReadsNothing() {
        beforeSave.put(1, () -> concurrently(entry -> {
            entry.getPendingVersion().setStage(KnowledgeIngestionStage.INGESTING);
            entry.getPendingVersion().setIngestionId("ingestion-other");
            entry.getPendingVersion().setIngestionStartedAt(NOW);
        }));

        assertThatThrownBy(() -> service.ingest(hr, KD_ID))
                .isInstanceOf(BusinessException.class).hasMessage(CLAIM_CONFLICT).hasNoCause();
        assertThat(pending().getIngestionId()).isEqualTo("ingestion-other");
        assertNothingClaimed();
    }

    @Test
    void aConcurrentIngestOfTheSameVersionIsRejectedWhileTheFirstRuns() {
        List<Throwable> second = new ArrayList<>();
        duringEmbed.put(1, () -> second.add(catchThrowable(() -> service.ingest(hr, KD_ID))));

        IngestionResult first = service.ingest(hr, KD_ID);

        assertThat(first.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(second).hasSize(1);
        assertThat(second.get(0)).isInstanceOf(BusinessException.class).hasMessage(INGESTION_IN_PROGRESS);
        assertThat(embedCalls).isEqualTo(1);
    }

    @Test
    void anIndexedVersionIsNotIngestedTwice() {
        service.ingest(hr, KD_ID);

        assertThatThrownBy(() -> service.ingest(hr, KD_ID)).isInstanceOf(BusinessException.class).hasMessage(ALREADY_INDEXED);
        assertThat(embedCalls).isEqualTo(1);
        assertThat(insertCalls).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = KnowledgeIngestionFailure.class, names = {
            "STORAGE_UNAVAILABLE", "EMBEDDING_CONFIGURATION", "EMBEDDING_REQUEST_REJECTED",
            "EMBEDDING_UNAVAILABLE", "INVALID_EMBEDDING", "CHUNK_WRITE_FAILED"})
    void aRetryableFailureIsRetriedWithANewIngestionIdAndItsLeftoversRemoved(KnowledgeIngestionFailure failure) {
        failed("ingestion-failed", failure);
        chunkStore.add(storedChunk("ingestion-failed", 0));
        chunkStore.add(storedChunk("ingestion-failed", 1));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(result.ingestionId()).matches(UUID_FORMAT).isNotEqualTo("ingestion-failed");
        KnowledgeDocumentVersion claimed = saves.get(0).getPendingVersion();
        assertThat(claimed.getFailureReason()).isNull();
        assertThat(claimed.getExtractionFailureReason()).isNull();
        assertThat(claimed.getFailedAt()).isNull();
        assertThat(chunksOf("ingestion-failed")).isEmpty();
        assertThat(deletedIngestionIds).containsExactly("ingestion-failed");
        assertThat(chunksOf(result.ingestionId())).hasSize(1);
    }

    @ParameterizedTest(name = "started {0} minutes before now")
    @ValueSource(longs = {30, 31, 600})
    void anIngestionWhoseLeaseExpiredIsReclaimedAndItsChunksRemoved(long minutesAgo) {
        ingesting("ingestion-stale", NOW.minusMinutes(minutesAgo));
        chunkStore.add(storedChunk("ingestion-stale", 0));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(result.ingestionId()).isNotEqualTo("ingestion-stale");
        assertThat(chunksOf("ingestion-stale")).isEmpty();
        assertThat(deletedIngestionIds).containsExactly("ingestion-stale");
    }

    @Test
    void staleCleanupNeverRemovesChunksTheEntryStillUses() {
        failed(ACTIVE_INGESTION, KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE); // a corrupt record naming the active id

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(deletedIngestionIds).isEmpty();
        assertThat(cleanerLogs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("because it is still referenced"));
    }

    @Test
    void aPdfWithMissingPagesThatHrAcceptedIsIngested() throws IOException {
        byte[] pdf = pdf("Leave policy page one " + TEXT_CANARY, null);
        String name = "leave-policy-" + FILE_CANARY + ".pdf";
        String sha256 = KnowledgeApprovalService.sha256(pdf);
        pending().setSourceDocumentName(name);
        pending().setContentSha256(sha256);
        pending().setPageCount(2);
        pending().setPagesWithoutText(1);
        pending().setIncompleteExtractionAcceptedByUserId("hr-user-2");
        pending().setIncompleteExtractionAcceptedAt(NOW.minusHours(1));
        DocumentTextExtractor.ExtractedText extracted = extractor.extract(pdf, name);
        DocumentTextExtractor.ContentInspection inspection = extracted.inspection();
        pending().setPagesRequiringOcrReview(inspection.pagesRequiringOcrReview());
        pending().setInspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection));
        pending().setIndexableTextSha256(KnowledgeContentReviewMetadata.textFingerprint(extracted.text()));
        when(reader.read(ORG, PENDING_DOC, sha256)).thenReturn(source(name, pdf));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(result.chunkCount()).isEqualTo(1);
    }

    // --- Source document --------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(KnowledgeSourceDocumentReader.Reason.class)
    void sourceDocumentProblemsAreRecordedWithTheirReason(KnowledgeSourceDocumentReader.Reason reason) {
        when(reader.read(ORG, PENDING_DOC, POLICY_SHA256)).thenThrow(new SourceDocumentException(reason));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertFailedWith(result, reason.failure(), null);
        verify(embeddings, never()).embedBatch(anyList(), any());
        verify(chunks, never()).insert(ArgumentMatchers.<KnowledgeChunk>anyIterable());
    }

    @Test
    void aSourceWhoseFileNameChangedSinceApprovalIsRejected() {
        when(reader.read(ORG, PENDING_DOC, POLICY_SHA256)).thenReturn(source("leave-policy-renamed.pdf", POLICY));

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.CONTENT_CHANGED, null);
        verify(embeddings, never()).embedBatch(anyList(), any());
    }

    @Test
    void anIncompleteSourceResultIsTreatedAsUnavailableStorage() {
        when(reader.read(ORG, PENDING_DOC, POLICY_SHA256)).thenReturn(null);

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.STORAGE_UNAVAILABLE, null);
    }

    // --- Extraction and chunking ------------------------------------------------------------------

    @Test
    void anExtractorRejectionIsRecordedWithItsReason() {
        String name = "leave-policy-" + FILE_CANARY + ".pdf"; // text bytes behind a .pdf name
        pending().setSourceDocumentName(name);
        when(reader.read(ORG, PENDING_DOC, POLICY_SHA256)).thenReturn(source(name, POLICY));

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.EXTRACTION_FAILED,
                DocumentExtractionException.Reason.FORMAT_MISMATCH);
        verify(embeddings, never()).embedBatch(anyList(), any());
    }

    @Test
    void aPageCountThatDiffersFromApprovalIsRejected() {
        pending().setPageCount(3);

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
    }

    @Test
    void missingPagesThatHrDidNotAcceptAreRejected() throws IOException {
        byte[] pdf = pdf("Leave policy page one " + TEXT_CANARY, null);
        String name = "leave-policy-" + FILE_CANARY + ".pdf";
        String sha256 = KnowledgeApprovalService.sha256(pdf);
        pending().setSourceDocumentName(name);
        pending().setContentSha256(sha256);
        pending().setPageCount(2);
        pending().setPagesWithoutText(0); // approval saw every page with text; this extraction does not
        when(reader.read(ORG, PENDING_DOC, sha256)).thenReturn(source(name, pdf));

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
        verify(embeddings, never()).embedBatch(anyList(), any());
    }

    @Test
    void textWithoutChunksIsRejected() {
        when(mockChunker.chunk(anyString())).thenReturn(List.of());
        service = serviceWith(CLOCK, mockChunker);

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedChunks")
    void malformedChunksAreRejected(String description, List<TextChunk> malformed) {
        when(mockChunker.chunk(anyString())).thenReturn(malformed);
        service = serviceWith(CLOCK, mockChunker);

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.EXTRACTION_FAILED, null);
        verify(embeddings, never()).embedBatch(anyList(), any());
    }

    static Stream<Arguments> malformedChunks() {
        return Stream.of(
                Arguments.of("index gap", List.of(new TextChunk(0, "first"), new TextChunk(2, "third"))),
                Arguments.of("out of order", List.of(new TextChunk(1, "second"), new TextChunk(0, "first"))),
                Arguments.of("blank text", List.of(new TextChunk(0, "first"), new TextChunk(1, "  "))),
                Arguments.of("null chunk", Arrays.asList(new TextChunk(0, "first"), null)));
    }

    @Test
    void moreThanTheMaximumNumberOfChunksIsRejectedBeforeEmbedding() {
        givenChunks(KnowledgeIngestionService.MAX_CHUNKS + 1);

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.TOO_MANY_CHUNKS, null);
        verify(embeddings, never()).embedBatch(anyList(), any());
    }

    @Test
    void exactlyTheMaximumNumberOfChunksIsIngested() {
        givenChunks(KnowledgeIngestionService.MAX_CHUNKS);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(result.chunkCount()).isEqualTo(1000);
        assertThat(embedCalls).isEqualTo(32); // 31 batches of 32 and one of 8
        assertThat(insertBatchSizes).hasSize(10).containsOnly(100);
    }

    // --- Embeddings ---------------------------------------------------------------------------------

    @Test
    void embedsDocumentChunksInBatchesOf32InChunkOrder() {
        List<TextChunk> list = givenChunks(70);

        service.ingest(hr, KD_ID);

        assertThat(embedRequests).extracting(List::size).containsExactly(32, 32, 6);
        assertThat(embedInputTypes).containsOnly(EmbeddingService.InputType.DOCUMENT);
        assertThat(embedRequests.stream().flatMap(List::stream).toList())
                .containsExactlyElementsOf(list.stream().map(TextChunk::text).toList());
    }

    @Test
    void storesEachVectorWithItsOwnChunk() {
        givenChunks(70);

        IngestionResult result = service.ingest(hr, KD_ID);

        List<KnowledgeChunk> stored = chunksOf(result.ingestionId());
        assertThat(stored).hasSize(70);
        for (KnowledgeChunk chunk : stored) {
            assertThat(chunk.getEmbedding()).hasSize(1024);
            assertThat(chunk.getEmbedding().get(0)).as("chunk " + chunk.getChunkIndex()).isEqualTo(chunk.getChunkIndex() + 1.0);
        }
    }

    @Test
    void everyEmbeddingIsPreparedBeforeTheFirstChunkIsWritten() {
        givenChunks(70);

        service.ingest(hr, KD_ID);

        InOrder order = inOrder(embeddings, chunks);
        order.verify(embeddings, times(3)).embedBatch(anyList(), any());
        order.verify(chunks, times(1)).insert(ArgumentMatchers.<KnowledgeChunk>anyIterable());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("embeddingFailures")
    void embeddingFailuresAreRecordedWithoutRetryingOrWriting(String description, RuntimeException failure,
                                                              KnowledgeIngestionFailure expected) {
        givenChunks(70);
        embedFailures.put(2, failure);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertFailedWith(result, expected, null);
        assertThat(embedCalls).as("no automatic retry").isEqualTo(2);
        verify(chunks, never()).insert(ArgumentMatchers.<KnowledgeChunk>anyIterable());
    }

    static Stream<Arguments> embeddingFailures() {
        return Stream.of(
                Arguments.of("configuration", new EmbeddingService.EmbeddingConfigurationException(),
                        KnowledgeIngestionFailure.EMBEDDING_CONFIGURATION),
                Arguments.of("request rejected", new EmbeddingService.EmbeddingRequestException(),
                        KnowledgeIngestionFailure.EMBEDDING_REQUEST_REJECTED),
                Arguments.of("invalid input", new IllegalArgumentException("Embedding input " + TEXT_CANARY),
                        KnowledgeIngestionFailure.EMBEDDING_REQUEST_REJECTED),
                Arguments.of("dimension mismatch", new EmbeddingService.EmbeddingDimensionMismatchException(1024, 3),
                        KnowledgeIngestionFailure.INVALID_EMBEDDING),
                Arguments.of("unavailable", new EmbeddingService.EmbeddingUnavailableException(
                        new IllegalStateException(CREDENTIAL_CANARY)), KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE),
                Arguments.of("other provider error", new EmbeddingService.EmbeddingException("provider said " + TEXT_CANARY),
                        KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEmbeddings")
    void invalidEmbeddingsAreRejectedBeforeAnythingIsWritten(String description,
                                                             Function<List<String>, List<List<Double>>> response) {
        givenChunks(3);
        embedResults.put(1, response);

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.INVALID_EMBEDDING, null);
        verify(chunks, never()).insert(ArgumentMatchers.<KnowledgeChunk>anyIterable());
    }

    static Stream<Arguments> invalidEmbeddings() {
        return Stream.of(
                invalidResponse("no result", texts -> null),
                invalidResponse("one vector too few", texts -> List.of(vector(0), vector(1))),
                invalidResponse("one vector too many", texts -> List.of(vector(0), vector(1), vector(2), vector(3))),
                invalidResponse("null vector", texts -> Arrays.asList(vector(0), null, vector(2))),
                invalidResponse("1023 dimensions", texts -> List.of(vector(0), vector(1), vector(2).subList(0, 1023))),
                invalidResponse("1025 dimensions", texts -> List.of(vector(0), vector(1), withValue(vector(2), 1024, 0.1))),
                invalidResponse("NaN", texts -> List.of(vector(0), withValue(vector(1), 5, Double.NaN), vector(2))),
                invalidResponse("positive infinity",
                        texts -> List.of(vector(0), withValue(vector(1), 5, Double.POSITIVE_INFINITY), vector(2))),
                invalidResponse("negative infinity",
                        texts -> List.of(vector(0), withValue(vector(1), 5, Double.NEGATIVE_INFINITY), vector(2))),
                invalidResponse("null value", texts -> List.of(vector(0), withValue(vector(1), 5, null), vector(2))),
                invalidResponse("zero vector", texts -> List.of(vector(0), Arrays.asList(new Double[1024]).stream()
                        .map(value -> 0.0).toList(), vector(2))));
    }

    @Test
    void aModelThatChangesDuringEmbeddingIsRejected() {
        givenChunks(3);
        duringEmbed.put(1, () -> modelName = "voyage-other-model");

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.INVALID_EMBEDDING, null);
        verify(chunks, never()).insert(ArgumentMatchers.<KnowledgeChunk>anyIterable());
    }

    @Test
    void revocationDuringEmbeddingStopsBeforeTheNextBatch() {
        givenChunks(70);
        duringEmbed.put(1, this::revokeConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(result.chunkCleanupCompleted()).isTrue();
        assertThat(embedCalls).isEqualTo(1);
        assertThat(saves).as("only the claim; the revocation is left as found").hasSize(1);
        assertThat(database.getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
        assertThat(insertCalls).isZero();
        assertThat(deletedIngestionIds).isEmpty();
    }

    @Test
    void replacementDuringEmbeddingStopsBeforeTheNextBatchAndKeepsTheReplacement() {
        givenChunks(70);
        duringEmbed.put(2, this::replaceConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(embedCalls).isEqualTo(2);
        assertThat(saves).hasSize(1);
        assertThat(pending()).isEqualTo(replacementVersion());
        assertThat(insertCalls).isZero();
    }

    // --- Chunk writes -----------------------------------------------------------------------------

    @Test
    void chunksCarryCompleteSourceOrganizationAuthorizationAndIngestionMetadata() {
        IngestionResult result = service.ingest(hr, KD_ID);

        List<KnowledgeChunk> stored = chunksOf(result.ingestionId());
        assertThat(stored).hasSize(1);
        KnowledgeChunk chunk = stored.get(0);
        assertThat(chunk.getId()).isEqualTo(result.ingestionId() + "-00000");
        assertThat(chunk.getOrganizationId()).isEqualTo(ORG);
        assertThat(chunk.getKnowledgeDocumentId()).isEqualTo(KD_ID);
        assertThat(chunk.getIngestionId()).isEqualTo(result.ingestionId());
        assertThat(chunk.getSourceDocumentId()).isEqualTo(PENDING_DOC);
        assertThat(chunk.getSourceDocumentName()).isEqualTo(FILE_NAME);
        assertThat(chunk.getDocumentType()).isEqualTo(DocumentType.COMPANY_POLICY);
        assertThat(chunk.getAccessLevel()).isEqualTo(ChunkAccessLevel.HR_ONLY);
        assertThat(chunk.getOwnerUserId()).isNull();
        assertThat(chunk.getChunkIndex()).isZero();
        assertThat(chunk.getChunkText()).contains(TEXT_CANARY);
        assertThat(chunk.getEmbedding()).hasSize(1024);
        assertThat(chunk.getEmbeddingModel()).isEqualTo(MODEL);
        assertThat(chunk.getContentHash()).isEqualTo(KnowledgeApprovalService.sha256(chunk.getChunkText().getBytes(UTF_8)));
        assertThat(chunk.getIndexedAt()).isEqualTo(NOW);
    }

    @Test
    void chunkIdsAreTheIngestionIdAndAFiveDigitIndex() {
        givenChunks(3);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(chunksOf(result.ingestionId())).extracting(KnowledgeChunk::getId).containsExactly(
                result.ingestionId() + "-00000", result.ingestionId() + "-00001", result.ingestionId() + "-00002");
        assertThat(KnowledgeIngestionService.chunkId("ingestion-x", 7)).isEqualTo("ingestion-x-00007");
        assertThat(KnowledgeIngestionService.chunkId("ingestion-x", 999)).isEqualTo("ingestion-x-00999");
    }

    @Test
    void writesChunksInBatchesOf100() {
        givenChunks(250);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(insertBatchSizes).containsExactly(100, 100, 50);
        assertThat(chunksOf(result.ingestionId())).hasSize(250);
    }

    @Test
    void aPartialWriteIsRemovedAndRecordedAsAWriteFailure() {
        givenChunks(250);
        insertFailures.put(2, new DataAccessResourceFailureException(CREDENTIAL_CANARY));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertFailedWith(result, KnowledgeIngestionFailure.CHUNK_WRITE_FAILED, null);
        assertThat(insertCalls).isEqualTo(2);
        assertThat(result.chunkCleanupCompleted()).isTrue();
        assertThat(deletedIngestionIds).containsExactly(result.ingestionId());
        assertThat(warnings()).anyMatch(line -> line.contains("DataAccessResourceFailureException"));
    }

    @Test
    void aChunkCountThatDoesNotMatchIsAWriteFailure() {
        givenChunks(5);
        dropOneInsertedChunk = true;

        IngestionResult result = service.ingest(hr, KD_ID);

        assertFailedWith(result, KnowledgeIngestionFailure.CHUNK_WRITE_FAILED, null);
        assertThat(deletedIngestionIds).containsExactly(result.ingestionId());
    }

    @Test
    void aChunkCountThatCannotBeReadIsAWriteFailure() {
        givenChunks(5);
        countFailure = new DataAccessResourceFailureException(CREDENTIAL_CANARY);

        assertFailedWith(service.ingest(hr, KD_ID), KnowledgeIngestionFailure.CHUNK_WRITE_FAILED, null);
    }

    @Test
    void aFailedCleanupIsReportedButTheFailureIsStillRecorded() {
        givenChunks(250);
        insertFailures.put(1, new DataAccessResourceFailureException(CREDENTIAL_CANARY));
        deleteFailure = new DataAccessResourceFailureException(CREDENTIAL_CANARY);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.chunkCleanupCompleted()).isFalse();
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.FAILED);
        assertThat(pending().getFailureReason()).isEqualTo(KnowledgeIngestionFailure.CHUNK_WRITE_FAILED);
        // What remains is inert: a FAILED version's chunks never serve.
        assertThat(chunksOf(result.ingestionId())).hasSize(50);
    }

    @Test
    void revocationDuringWritesStopsBeforeTheNextBatchAndLeavesCleanupToRevocation() {
        givenChunks(250);
        duringInsert.put(1, this::revokeConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(insertCalls).isEqualTo(1);
        assertThat(saves).hasSize(1);
        assertThat(database.getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
        assertThat(chunksOf(result.ingestionId())).hasSize(100);
        assertThat(deletedIngestionIds).isEmpty();
        assertThat(result.chunkCleanupCompleted()).isFalse();
    }

    @Test
    void replacementDuringWritesStopsAndRemovesOnlyThisAttemptsChunks() {
        givenChunks(250);
        duringInsert.put(2, this::replaceConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(insertCalls).isEqualTo(2);
        assertThat(pending()).isEqualTo(replacementVersion());
        assertThat(chunksOf(result.ingestionId())).isEmpty();
        assertThat(deletedIngestionIds).containsExactly(result.ingestionId());
    }

    // --- Completion ---------------------------------------------------------------------------------

    @Test
    void successLeavesOnlyThePendingVersionIndexedAndInactive() {
        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(result.chunkCount()).isEqualTo(1);
        assertThat(result.failure()).isNull();
        assertThat(result.chunkCleanupCompleted()).isTrue();
        assertThat(result.document()).isEqualTo(database);
        KnowledgeDocumentVersion indexed = pending();
        assertThat(indexed.getStage()).isEqualTo(KnowledgeIngestionStage.INDEXED);
        assertThat(indexed.getIngestionId()).isEqualTo(result.ingestionId());
        assertThat(indexed.getIngestionStartedAt()).isEqualTo(NOW);
        assertThat(indexed.getChunkCount()).isEqualTo(1);
        assertThat(indexed.getEmbeddingModel()).isEqualTo(MODEL);
        assertThat(indexed.getEmbeddingDimensions()).isEqualTo(1024);
        assertThat(indexed.getIndexedAt()).isEqualTo(NOW);
        assertThat(indexed.getActivatedAt()).isNull();
        assertThat(database.getStatus()).isEqualTo(KnowledgeDocumentStatus.LIVE);
        assertThat(database.getActiveVersion()).isEqualTo(activeVersion());
        assertThat(database.getSourceDocumentIds()).containsExactly(ACTIVE_DOC, PENDING_DOC);
        assertThat(saves).hasSize(2);
        assertThat(database.getVersion()).isEqualTo(7L);
        assertThat(deletedIngestionIds).isEmpty();
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("indexed 1 chunks; awaiting activation"));
    }

    @Test
    void ownershipIsRecheckedBeforeMarkingIndexed() {
        givenChunks(3);
        duringInsert.put(1, this::revokeConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(saves).hasSize(1);
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(chunksOf(result.ingestionId())).hasSize(3);
        assertThat(result.chunkCleanupCompleted()).isFalse();
    }

    @Test
    void aRevocationThatWinsTheIndexedSaveIsKept() {
        givenChunks(3);
        beforeSave.put(2, this::revokeConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(database.getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(saves).hasSize(1);
        assertThat(chunksOf(result.ingestionId())).hasSize(3);
        assertThat(deletedIngestionIds).isEmpty();
        assertThat(result.chunkCleanupCompleted()).isFalse();
    }

    @Test
    void aReplacementThatWinsTheIndexedSaveIsKept() {
        givenChunks(3);
        beforeSave.put(2, this::replaceConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(pending()).isEqualTo(replacementVersion());
        assertThat(saves).hasSize(1);
        assertThat(chunksOf(result.ingestionId())).isEmpty();
    }

    // --- Failure recovery never overwrites a replacement or revocation ------------------------------

    @Test
    void aFailureIsNotRecordedOverAReplacement() {
        givenChunks(70);
        duringEmbed.put(2, this::replaceConcurrently);
        embedFailures.put(2, new EmbeddingService.EmbeddingUnavailableException());

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(result.failure()).isEqualTo(KnowledgeIngestionFailure.EMBEDDING_UNAVAILABLE);
        assertThat(saves).as("only the claim").hasSize(1);
        assertThat(pending()).isEqualTo(replacementVersion());
    }

    @Test
    void aFailureIsNotRecordedOverARevocation() {
        givenChunks(70);
        duringEmbed.put(1, this::revokeConcurrently);
        embedFailures.put(1, new EmbeddingService.EmbeddingUnavailableException());

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.REVOKED);
        assertThat(saves).hasSize(1);
        assertThat(database.getStatus()).isEqualTo(KnowledgeDocumentStatus.REVOKED);
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(pending().getFailureReason()).isNull();
    }

    @Test
    void aConflictWhileRecordingAFailureIsNotRetried() {
        givenChunks(3);
        embedFailures.put(1, new EmbeddingService.EmbeddingUnavailableException());
        beforeSave.put(2, this::replaceConcurrently);

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(saveCalls).as("the claim and one FAILED attempt").isEqualTo(2);
        assertThat(pending()).isEqualTo(replacementVersion());
    }

    @Test
    void aFailureThatCannotBeSavedLeavesTheVersionToItsLease() {
        givenChunks(250);
        insertFailures.put(1, new DataAccessResourceFailureException(CREDENTIAL_CANARY));
        saveFailures.put(2, new DataAccessResourceFailureException(CREDENTIAL_CANARY));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.failure()).isEqualTo(KnowledgeIngestionFailure.CHUNK_WRITE_FAILED);
        assertThat(result.document()).isNull();
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(pending().getIngestionId()).isEqualTo(result.ingestionId());
        assertThat(chunksOf(result.ingestionId()))
                .as("a LIVE INGESTING version protects its partial chunks until its lease is resolved")
                .hasSize(50);
        assertThat(warnings()).anyMatch(line -> line.contains("could not be recorded"));
    }

    // --- Cleanup targets one exact obsolete ingestion -----------------------------------------------

    @Test
    void aSlowAttemptOvertakenAfterItsLeaseRemovesOnlyItsOwnChunks() {
        givenChunks(250);
        KnowledgeIngestionService later = serviceWith(Clock.offset(CLOCK, Duration.ofMinutes(31)), mockChunker);
        String[] first = new String[1];
        IngestionResult[] overtaking = new IngestionResult[1];
        duringInsert.put(1, () -> {
            first[0] = database.getPendingVersion().getIngestionId();
            overtaking[0] = later.ingest(TestActors.companyAdmin("admin-3", ORG), KD_ID);
        });

        IngestionResult slow = service.ingest(hr, KD_ID);

        assertThat(slow.ingestionId()).isEqualTo(first[0]);
        assertThat(slow.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(overtaking[0].outcome()).isEqualTo(Outcome.INDEXED);
        String newer = overtaking[0].ingestionId();
        assertThat(pending().getIngestionId()).isEqualTo(newer);
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INDEXED);
        assertThat(chunksOf(newer)).as("the newer ingestion's chunks are kept").hasSize(250);
        assertThat(chunksOf(slow.ingestionId())).isEmpty();
        assertThat(deletedIngestionIds).containsOnly(slow.ingestionId());
    }

    @Test
    void cleanupKeepsTheNewActiveIngestionAndRemovesTheOldPermanentlyObsoleteOne() {
        givenChunks(250);
        activeVersionChangedByTest = true;
        duringInsert.put(1, () -> concurrently(entry -> {
            // A race in which this attempt's ingestion became the active one and a new version is pending.
            KnowledgeDocumentVersion promoted = copy(entry.getPendingVersion());
            promoted.setStage(KnowledgeIngestionStage.INDEXED);
            entry.setActiveVersion(promoted);
            entry.setPendingVersion(replacementVersion());
        }));

        IngestionResult result = service.ingest(hr, KD_ID);

        assertThat(result.outcome()).isEqualTo(Outcome.SUPERSEDED);
        assertThat(result.chunkCleanupCompleted()).isFalse();
        assertThat(deletedIngestionIds).containsExactly(ACTIVE_INGESTION);
        assertThat(chunksOf(result.ingestionId())).hasSize(100);
        assertThat(cleanerLogs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(line -> line.contains("because it is still referenced"));
    }

    @Test
    void cleanupNeverRemovesChunksOfAnIndexedPendingVersion() {
        givenChunks(3);
        failAfterStoring.add(2); // the INDEXED save is stored, but its acknowledgement is lost

        assertThatThrownBy(() -> service.ingest(hr, KD_ID))
                .isInstanceOf(KnowledgeIngestionService.IngestionFailedException.class).hasNoCause();

        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INDEXED);
        assertThat(chunksOf(pending().getIngestionId())).hasSize(3);
        assertThat(deletedIngestionIds).isEmpty();
    }

    // --- Unexpected errors are sanitized and left to the lease --------------------------------------

    @Test
    void anUnexpectedErrorAfterTheClaimIsSanitizedAndLeftToTheLease() {
        when(mockChunker.chunk(anyString())).thenThrow(new IllegalStateException(
                "parser quoted " + TEXT_CANARY + " from " + FILE_NAME + " via " + CREDENTIAL_CANARY));
        service = serviceWith(CLOCK, mockChunker);

        Throwable thrown = catchThrowable(() -> service.ingest(hr, KD_ID));

        assertThat(thrown).isInstanceOf(KnowledgeIngestionService.IngestionFailedException.class)
                .hasMessage(KnowledgeIngestionService.STOPPED_UNEXPECTEDLY).hasNoCause();
        assertSanitized(thrown);
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(pending().getFailureReason()).isNull();
        assertThat(saves).hasSize(1);
        assertThat(deletedIngestionIds).isEmpty();
        assertThat(warnings()).anyMatch(line -> line.contains("IllegalStateException"));
    }

    @Test
    void anUnexpectedErrorAfterWritingKeepsChunksWhileTheLiveVersionIsStillIngesting() {
        givenChunks(3);
        saveFailures.put(2, new DataAccessResourceFailureException(CREDENTIAL_CANARY));

        Throwable thrown = catchThrowable(() -> service.ingest(hr, KD_ID));

        assertThat(thrown).isInstanceOf(KnowledgeIngestionService.IngestionFailedException.class).hasNoCause();
        assertSanitized(thrown);
        String attempt = pending().getIngestionId();
        assertThat(pending().getStage()).isEqualTo(KnowledgeIngestionStage.INGESTING);
        assertThat(chunksOf(attempt)).hasSize(3);
        assertThat(deletedIngestionIds).isEmpty();
    }

    @Test
    void afterAnUnexpectedErrorTheVersionCanBeRetriedOnceItsLeaseExpires() {
        when(mockChunker.chunk(anyString())).thenThrow(new IllegalStateException("parser failure"));
        service = serviceWith(CLOCK, mockChunker);
        assertThatThrownBy(() -> service.ingest(hr, KD_ID))
                .isInstanceOf(KnowledgeIngestionService.IngestionFailedException.class);
        String abandoned = pending().getIngestionId();

        assertThatThrownBy(() -> serviceWith(CLOCK, realChunker).ingest(hr, KD_ID))
                .isInstanceOf(BusinessException.class).hasMessage(INGESTION_IN_PROGRESS);
        IngestionResult retried =
                serviceWith(Clock.offset(CLOCK, Duration.ofMinutes(31)), realChunker).ingest(hr, KD_ID);

        assertThat(retried.outcome()).isEqualTo(Outcome.INDEXED);
        assertThat(retried.ingestionId()).isNotEqualTo(abandoned);
        assertThat(chunksOf(abandoned)).isEmpty();
        assertThat(deletedIngestionIds).isEmpty();
    }

    @Test
    void anUnexpectedErrorBeforeTheClaimIsSanitized() {
        lookupFailures.put(1, new DataAccessResourceFailureException(CREDENTIAL_CANARY));

        Throwable thrown = catchThrowable(() -> service.ingest(hr, KD_ID));

        assertThat(thrown).isInstanceOf(KnowledgeIngestionService.IngestionFailedException.class)
                .hasMessage(KnowledgeIngestionService.NOT_STARTED).hasNoCause();
        assertSanitized(thrown);
        assertNothingClaimed();
    }

    @Test
    void aClaimThatCannotBeSavedIsSanitizedAndReadsNothing() {
        saveFailures.put(1, new DataAccessResourceFailureException(CREDENTIAL_CANARY));

        Throwable thrown = catchThrowable(() -> service.ingest(hr, KD_ID));

        assertThat(thrown).isInstanceOf(KnowledgeIngestionService.IngestionFailedException.class)
                .hasMessage(KnowledgeIngestionService.NOT_STARTED).hasNoCause();
        assertSanitized(thrown);
        verifyNoInteractions(reader, chunks);
    }

    @Test
    void rejectionMessagesAreFixedAndRevealNothing() {
        for (String message : List.of(KnowledgeIngestionService.NOT_STARTED, KnowledgeIngestionService.STOPPED_UNEXPECTEDLY,
                CLAIM_CONFLICT, PENDING_VERSION_INVALID, EMBEDDING_NOT_CONFIGURED, FAILURE_NOT_RETRYABLE)) {
            assertThat(message).isNotBlank().doesNotContain(FILE_CANARY).doesNotContain(TEXT_CANARY).doesNotContain(ORG);
        }
    }

    // --- Assertions -------------------------------------------------------------------------------

    private void assertRejected(String message) {
        assertThatThrownBy(() -> service.ingest(hr, KD_ID)).isInstanceOf(BusinessException.class).hasMessage(message);
        assertNothingClaimed();
    }

    private void assertNothingClaimed() {
        assertThat(saves).as("nothing saved").isEmpty();
        verifyNoInteractions(reader, chunks);
        verify(embeddings, never()).embedBatch(anyList(), any());
    }

    /** No log line may carry document text, file names, hashes, vectors, user or organization ids, or credentials. */
    private void assertLogsRevealNothing() {
        List<String> forbidden = List.of(TEXT_CANARY, FILE_CANARY, ".txt", ".pdf", "organizations/", HR_ID, ORG, OTHER_ORG,
                "s3cret", "mongodb", POLICY_SHA256, "0.5, 0.0");
        for (ILoggingEvent event : logs.list) {
            String message = event.getFormattedMessage();
            for (String value : forbidden) {
                assertThat(message).as("log line").doesNotContain(value);
            }
            assertThat(message).as("log line").doesNotMatch("(?s).*[0-9a-f]{64}.*");
            assertThat(event.getThrowableProxy()).as("stack trace logged with: " + message).isNull();
        }
    }

    /** FAILED is recorded on the claimed version, which keeps its ingestionId and never becomes active. */
    private void assertFailedWith(IngestionResult result, KnowledgeIngestionFailure failure,
                                  DocumentExtractionException.Reason extractionReason) {
        assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
        assertThat(result.failure()).isEqualTo(failure);
        assertThat(result.chunkCount()).isZero();
        KnowledgeDocumentVersion recorded = pending();
        assertThat(recorded.getStage()).isEqualTo(KnowledgeIngestionStage.FAILED);
        assertThat(recorded.getFailureReason()).isEqualTo(failure);
        assertThat(recorded.getExtractionFailureReason()).isEqualTo(extractionReason);
        assertThat(recorded.getFailedAt()).isEqualTo(NOW);
        assertThat(recorded.getIngestionId()).isEqualTo(result.ingestionId());
        assertThat(recorded.getChunkCount()).isNull();
        assertThat(recorded.getIndexedAt()).isNull();
        assertThat(recorded.getActivatedAt()).isNull();
        assertThat(result.document()).isEqualTo(database);
        assertThat(chunksOf(result.ingestionId())).as("no chunks of a failed attempt remain").isEmpty();
    }

    private static void assertSanitized(Throwable thrown) {
        assertThat(thrown.getMessage()).doesNotContain(TEXT_CANARY).doesNotContain(FILE_CANARY)
                .doesNotContain("s3cret").doesNotContain("mongodb").doesNotContain(POLICY_SHA256);
        assertThat(thrown.getCause()).isNull();
        assertThat(thrown.getSuppressed()).isEmpty();
    }

    private List<String> warnings() {
        return logs.list.stream().filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    // --- Fixtures ---------------------------------------------------------------------------------

    private KnowledgeIngestionService serviceWith(Clock clock, DocumentChunkingService chunker) {
        return new KnowledgeIngestionService(
                knowledgeDocuments, chunks, reader, extractor, chunker, embeddings, chunkCleaner, clock,
                voyageAuthorization);
    }

    private KnowledgeDocumentVersion pending() {
        return database.getPendingVersion();
    }

    private void ingesting(String ingestionId, LocalDateTime startedAt) {
        pending().setStage(KnowledgeIngestionStage.INGESTING);
        pending().setIngestionId(ingestionId);
        pending().setIngestionStartedAt(startedAt);
    }

    private void failed(String ingestionId, KnowledgeIngestionFailure failure) {
        pending().setStage(KnowledgeIngestionStage.FAILED);
        pending().setIngestionId(ingestionId);
        pending().setIngestionStartedAt(NOW.minusHours(1));
        pending().setFailureReason(failure);
        pending().setFailedAt(NOW.minusMinutes(50));
    }

    private static Arguments invalid(String description, Consumer<KnowledgeDocument> change) {
        return Arguments.of(description, change);
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
                .createdAt(NOW.minusDays(30))
                .version(5L)
                .build();
    }

    private static KnowledgeDocumentVersion activeVersion() {
        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(ACTIVE_DOC)
                .sourceDocumentName("leave-policy-2025.txt")
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.COMPANY_WIDE)
                .contentSha256(KnowledgeApprovalService.sha256(ACTIVE_DOC.getBytes(UTF_8)))
                .approvedByUserId("hr-user-earlier")
                .approvedAt(NOW.minusDays(30))
                .pageCount(0)
                .pagesWithoutText(0)
                .stage(KnowledgeIngestionStage.INDEXED)
                .ingestionId(ACTIVE_INGESTION)
                .ingestionStartedAt(NOW.minusDays(30))
                .chunkCount(3)
                .embeddingModel(MODEL)
                .embeddingDimensions(1024)
                .indexedAt(NOW.minusDays(30))
                .activatedAt(NOW.minusDays(30))
                .build();
    }

    private static KnowledgeDocumentVersion approvedVersion() {
        DocumentTextExtractor.ContentInspection inspection = DocumentTextExtractor.ContentInspection.none();
        return KnowledgeDocumentVersion.builder()
                .sourceDocumentId(PENDING_DOC)
                .sourceDocumentName(FILE_NAME)
                .documentType(DocumentType.COMPANY_POLICY)
                .accessLevel(ChunkAccessLevel.HR_ONLY)
                .contentSha256(POLICY_SHA256)
                .approvedByUserId(HR_ID)
                .approvedAt(NOW.minusDays(1))
                .pageCount(0)
                .pagesWithoutText(0)
                .inspectionFormatVersion(DocumentTextExtractor.ContentInspection.FORMAT_VERSION)
                .hiddenDocxRunsExcluded(0).suspiciousTextFragments(0).invisibleUnicodeCharacters(0)
                .unicodeTagCharactersRemoved(0).hiddenCommentBlocks(0).instructionLikeContentMatches(0)
                .pagesRequiringOcrReview(0).contentReviewRequired(false)
                .inspectionFindingsSha256(KnowledgeContentReviewMetadata.fingerprint(inspection))
                .indexableTextSha256(KnowledgeContentReviewMetadata.textFingerprint(new String(POLICY, UTF_8)))
                .stage(KnowledgeIngestionStage.APPROVED)
                .build();
    }

    private static KnowledgeDocumentVersion replacementVersion() {
        KnowledgeDocumentVersion replacement = approvedVersion();
        replacement.setSourceDocumentId(REPLACEMENT_DOC);
        replacement.setSourceDocumentName("leave-policy-v3.txt");
        replacement.setContentSha256(KnowledgeApprovalService.sha256(REPLACEMENT_DOC.getBytes(UTF_8)));
        replacement.setApprovedByUserId("hr-user-other");
        return replacement;
    }

    private static KnowledgeChunk storedChunk(String ingestionId, int index) {
        return KnowledgeChunk.builder()
                .id(KnowledgeIngestionService.chunkId(ingestionId, index))
                .organizationId(ORG)
                .knowledgeDocumentId(KD_ID)
                .ingestionId(ingestionId)
                .chunkIndex(index)
                .build();
    }

    private static VerifiedSourceContent source(String fileName, byte[] content) {
        StoredDocument metadata = StoredDocument.builder()
                .id(PENDING_DOC)
                .organizationId(ORG)
                .documentType(DocumentType.COMPANY_POLICY)
                .fileName(fileName)
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/" + ORG + "/company_policy/uuid-" + fileName)
                .fileSize((long) content.length)
                .status(DocumentStatus.ACTIVE)
                .build();
        return new VerifiedSourceContent(metadata, content, KnowledgeApprovalService.sha256(content));
    }

    /** Exactly count chunks with unique texts, served by the mock chunker. */
    private List<TextChunk> givenChunks(int count) {
        List<TextChunk> list = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            String text = String.format("Section %05d of the leave policy. %s", index, TEXT_CANARY);
            list.add(new TextChunk(index, text));
            chunkIndexByText.put(text, index);
        }
        when(mockChunker.chunk(anyString())).thenReturn(list);
        service = serviceWith(CLOCK, mockChunker);
        return list;
    }

    private static Arguments invalidResponse(String description, Function<List<String>, List<List<Double>>> response) {
        return Arguments.of(description, response);
    }

    /** A copy of vector with value at position, growing it by one when position equals its size. */
    private static List<Double> withValue(List<Double> vector, int position, Double value) {
        List<Double> changed = new ArrayList<>(vector);
        if (position == changed.size()) {
            changed.add(value);
        } else {
            changed.set(position, value);
        }
        return changed;
    }

    /** 1024 finite values, distinct per chunk index so the stored order can be checked. */
    private static List<Double> vector(int chunkIndex) {
        Double[] values = new Double[1024];
        Arrays.fill(values, 0.0);
        values[0] = chunkIndex + 1.0;
        values[1] = 0.5;
        return Arrays.asList(values);
    }

    private List<KnowledgeChunk> chunksOf(String ingestionId) {
        return chunkStore.stream().filter(chunk -> Objects.equals(ingestionId, chunk.getIngestionId())).toList();
    }

    /** A change made by another request: applied to the stored entry, bumping its version. */
    private void concurrently(Consumer<KnowledgeDocument> change) {
        change.accept(database);
        database.setVersion(database.getVersion() + 1);
    }

    private void revokeConcurrently() {
        concurrently(entry -> {
            entry.setStatus(KnowledgeDocumentStatus.REVOKED);
            entry.setRevokedByUserId("admin-2");
            entry.setRevokedAt(NOW);
        });
    }

    private void replaceConcurrently() {
        concurrently(entry -> {
            entry.setPendingVersion(replacementVersion());
            entry.setSourceDocumentIds(new ArrayList<>(List.of(ACTIVE_DOC, REPLACEMENT_DOC)));
        });
    }

    private void stubDatabase() {
        when(knowledgeDocuments.findByIdAndOrganizationId(anyString(), anyString())).thenAnswer(invocation -> {
            RuntimeException failure = lookupFailures.get(++lookupCalls);
            if (failure != null) {
                throw failure;
            }
            String id = invocation.getArgument(0);
            String organizationId = invocation.getArgument(1);
            return database != null && id.equals(database.getId()) && organizationId.equals(database.getOrganizationId())
                    ? Optional.of(copy(database)) : Optional.empty();
        });
        when(knowledgeDocuments.save(any(KnowledgeDocument.class))).thenAnswer(invocation -> {
            int call = ++saveCalls;
            KnowledgeDocument entry = invocation.getArgument(0);
            Runnable hook = beforeSave.get(call);
            if (hook != null) {
                hook.run();
            }
            RuntimeException failure = saveFailures.get(call);
            if (failure != null) {
                throw failure;
            }
            if (!Objects.equals(entry.getVersion(), database.getVersion())) {
                throw new OptimisticLockingFailureException("Stale version of " + FILE_NAME + " " + CREDENTIAL_CANARY);
            }
            KnowledgeDocument stored = copy(entry);
            stored.setVersion(entry.getVersion() + 1);
            database = stored;
            saves.add(copy(stored));
            if (failAfterStoring.contains(call)) {
                throw new DataAccessResourceFailureException(CREDENTIAL_CANARY);
            }
            return copy(stored);
        });
        when(chunks.insert(ArgumentMatchers.<KnowledgeChunk>anyIterable())).thenAnswer(invocation -> {
            int call = ++insertCalls;
            List<KnowledgeChunk> batch = new ArrayList<>();
            ((Iterable<KnowledgeChunk>) invocation.getArgument(0)).forEach(batch::add);
            insertBatchSizes.add(batch.size());
            Runnable hook = duringInsert.get(call);
            if (hook != null) {
                hook.run();
            }
            RuntimeException failure = insertFailures.get(call);
            if (failure != null) {
                chunkStore.addAll(batch.subList(0, batch.size() / 2)); // a partial write before the failure
                throw failure;
            }
            chunkStore.addAll(dropOneInsertedChunk && call == 1 ? batch.subList(1, batch.size()) : batch);
            return batch;
        });
        when(chunks.countByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    if (countFailure != null) {
                        throw countFailure;
                    }
                    return chunkStore.stream().filter(matches(invocation.getArgument(0), invocation.getArgument(1),
                            invocation.getArgument(2))).count();
                });
        when(chunks.findByOrganizationIdAndKnowledgeDocumentId(anyString(), anyString()))
                .thenAnswer(invocation -> chunkStore.stream()
                        .filter(chunk -> invocation.getArgument(0).equals(chunk.getOrganizationId())
                                && invocation.getArgument(1).equals(chunk.getKnowledgeDocumentId()))
                        .map(KnowledgeIngestionServiceTest::copy)
                        .toList());
        when(chunks.deleteByOrganizationIdAndKnowledgeDocumentIdAndIngestionId(anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> {
                    deletedIngestionIds.add(invocation.getArgument(2));
                    if (deleteFailure != null) {
                        throw deleteFailure;
                    }
                    Predicate<KnowledgeChunk> match =
                            matches(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
                    long removed = chunkStore.stream().filter(match).count();
                    chunkStore.removeIf(match);
                    return removed;
                });
    }

    private static Predicate<KnowledgeChunk> matches(String organizationId, String knowledgeDocumentId, String ingestionId) {
        return chunk -> organizationId.equals(chunk.getOrganizationId())
                && knowledgeDocumentId.equals(chunk.getKnowledgeDocumentId())
                && ingestionId.equals(chunk.getIngestionId());
    }

    private void stubEmbeddings() {
        when(embeddings.model()).thenAnswer(invocation -> modelName);
        when(embeddings.dimensions()).thenReturn(1024);
        when(embeddings.embedBatch(anyList(), any())).thenAnswer(invocation -> {
            int call = ++embedCalls;
            List<String> texts = invocation.getArgument(0);
            embedRequests.add(List.copyOf(texts));
            embedInputTypes.add(invocation.getArgument(1));
            Runnable hook = duringEmbed.get(call);
            if (hook != null) {
                hook.run();
            }
            RuntimeException failure = embedFailures.get(call);
            if (failure != null) {
                throw failure;
            }
            Function<List<String>, List<List<Double>>> result = embedResults.get(call);
            if (result != null) {
                return result.apply(texts);
            }
            return texts.stream().map(text -> vector(chunkIndexByText.getOrDefault(text, 0))).toList();
        });
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

    /** Field-by-field copy, deep for versions and lists, as a database round trip would produce. */
    @SuppressWarnings("unchecked")
    private static <T> T copy(T source) {
        if (source == null) {
            return null;
        }
        try {
            T target = (T) source.getClass().getDeclaredConstructor().newInstance();
            for (Field field : source.getClass().getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(source);
                if (value instanceof KnowledgeDocumentVersion version) {
                    value = copy(version);
                } else if (value instanceof List<?> list) {
                    value = new ArrayList<>(list);
                }
                field.set(target, value);
            }
            return target;
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
