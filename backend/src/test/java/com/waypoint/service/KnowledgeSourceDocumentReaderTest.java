package com.waypoint.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.StorageProvider;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.service.KnowledgeSourceDocumentReader.Reason;
import com.waypoint.service.KnowledgeSourceDocumentReader.SourceDocumentException;
import com.waypoint.service.KnowledgeSourceDocumentReader.VerifiedSourceContent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** DocumentService is a mock and every document is synthetic bytes; no database or object storage is contacted. */
class KnowledgeSourceDocumentReaderTest {

    private static final String ORG = "org-acme-77";
    private static final String OTHER_ORG = "org-other-12";
    private static final String DOC_ID = "stored-doc-1";
    private static final String FILE_CANARY = "FILECANARY53";
    private static final String FILE_NAME = "leave-policy-" + FILE_CANARY + ".txt";
    private static final String STORAGE_KEY = "organizations/" + ORG + "/company_policy/uuid-" + FILE_NAME;
    private static final String TEXT_CANARY = "TEXTCANARY91";
    private static final byte[] POLICY =
            ("Leave Policy\nEmployees accrue leave monthly. " + TEXT_CANARY).getBytes(StandardCharsets.UTF_8);
    private static final String POLICY_SHA256 = KnowledgeApprovalService.sha256(POLICY);
    /** SHA-256 test vector for "abc" (FIPS 180-2). */
    private static final String ABC_SHA256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String CREDENTIAL_CANARY = "mongodb+srv://user:s3cret@cluster.example";

    private final DocumentService documentService = mock(DocumentService.class);
    private final KnowledgeSourceDocumentReader reader = new KnowledgeSourceDocumentReader(documentService);

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(KnowledgeSourceDocumentReader.class);

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logs);
        assertLogsRevealNothing();
    }

    // --- Success ----------------------------------------------------------------------------------

    @Test
    void returnsTheApprovedBytesWithTheirMetadataAndHash() {
        givenStored(POLICY);

        VerifiedSourceContent verified = reader.read(ORG, DOC_ID, POLICY_SHA256);

        assertThat(verified.content()).isEqualTo(POLICY);
        assertThat(verified.contentSha256()).isEqualTo(POLICY_SHA256);
        assertThat(verified.metadata().getId()).isEqualTo(DOC_ID);
        assertThat(verified.metadata().getOrganizationId()).isEqualTo(ORG);
        assertThat(verified.metadata().getFileName()).isEqualTo(FILE_NAME);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void acceptsTheHashKnowledgeApprovalRecords() {
        byte[] abc = "abc".getBytes(StandardCharsets.US_ASCII);
        givenStored(abc);

        assertThat(KnowledgeApprovalService.sha256(abc)).isEqualTo(ABC_SHA256);
        assertThat(reader.read(ORG, DOC_ID, ABC_SHA256).contentSha256()).isEqualTo(ABC_SHA256);
    }

    @Test
    void acceptsBinaryContent() {
        byte[] synthetic = {'%', 'P', 'D', 'F', '-', 0, (byte) 0xFF, 7, '\n', (byte) 0x80};
        givenStored(synthetic);

        assertThat(reader.read(ORG, DOC_ID, KnowledgeApprovalService.sha256(synthetic)).content()).isEqualTo(synthetic);
    }

    @Test
    void checksMetadataBeforeFetchingBytesAndNeverUsesGetContent() {
        givenStored(POLICY);

        reader.read(ORG, DOC_ID, POLICY_SHA256);

        InOrder order = inOrder(documentService);
        order.verify(documentService).getMetadata(DOC_ID);
        order.verify(documentService).getStoredContent(DOC_ID);
        verify(documentService, never()).getContent(anyString());
    }

    @Test
    void toStringRevealsNoFileNameStorageKeyHashOrContent() {
        givenStored(POLICY);

        String text = reader.read(ORG, DOC_ID, POLICY_SHA256).toString();

        assertThat(text).isEqualTo("VerifiedSourceContent[storedDocumentId=" + DOC_ID + ", bytes=" + POLICY.length + "]");
    }

    // --- Arguments --------------------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  "})
    void requiresAnOrganizationAndADocumentBeforeAnyLookup(String blank) {
        assertThatThrownBy(() -> reader.read(blank, DOC_ID, POLICY_SHA256)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> reader.read(ORG, blank, POLICY_SHA256)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documentService);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"abc", "BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015a", // 63 characters
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015adz",
            "za7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"})
    void requiresAWellFormedApprovedHashBeforeAnyLookup(String hash) {
        assertThatThrownBy(() -> reader.read(ORG, DOC_ID, hash)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(documentService);
    }

    // --- Organization isolation -------------------------------------------------------------------

    @Test
    void anotherOrganizationsDocumentIsNotFoundAndItsBytesAreNeverFetched() {
        givenStored(POLICY, document -> document.setOrganizationId(OTHER_ORG));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.NOT_FOUND);
        verify(documentService, never()).getStoredContent(anyString());
    }

    @Test
    void aDocumentWithoutAnOrganizationIsNotFoundAndItsBytesAreNeverFetched() {
        givenStored(POLICY, document -> document.setOrganizationId(null));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.NOT_FOUND);
        verify(documentService, never()).getStoredContent(anyString());
    }

    @Test
    void metadataForADifferentDocumentIsNotFound() {
        givenStored(POLICY, document -> document.setId("stored-doc-x"));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.NOT_FOUND);
        verify(documentService, never()).getStoredContent(anyString());
    }

    @Test
    void missingMetadataIsNotFound() {
        when(documentService.getMetadata(DOC_ID)).thenReturn(null);

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.NOT_FOUND);
        verify(documentService, never()).getStoredContent(anyString());
    }

    @Test
    void aDeletedDocumentIsNotFound() {
        when(documentService.getMetadata(DOC_ID)).thenThrow(new ResourceNotFoundException("Document not found"));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.NOT_FOUND);
        verify(documentService, never()).getStoredContent(anyString());
    }

    // --- Metadata eligibility ---------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = DocumentType.class, mode = EnumSource.Mode.EXCLUDE, names = "COMPANY_POLICY")
    void acceptsCompanyPoliciesOnly(DocumentType type) {
        givenStored(POLICY, document -> document.setDocumentType(type));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.INELIGIBLE);
        verify(documentService, never()).getStoredContent(anyString());
    }

    @Test
    void acceptsActiveDocumentsOnly() {
        for (DocumentStatus status : new DocumentStatus[]{DocumentStatus.SUPERSEDED, null}) {
            givenStored(POLICY, document -> document.setStatus(status));

            assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.INELIGIBLE);
        }
        verify(documentService, never()).getStoredContent(anyString());
    }

    @Test
    void aDocumentWithoutATypeIsIneligible() {
        givenStored(POLICY, document -> document.setDocumentType(null));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.INELIGIBLE);
        verify(documentService, never()).getStoredContent(anyString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteMetadata")
    void rejectsIncompleteMetadataBeforeFetchingBytes(String description, Consumer<StoredDocument> change) {
        givenStored(POLICY, change);

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.METADATA_INCOMPLETE);
        verify(documentService, never()).getStoredContent(anyString());
    }

    static Stream<Arguments> incompleteMetadata() {
        return Stream.of(
                Arguments.of("GridFS storage", change(document -> document.setStorageProvider(StorageProvider.GRIDFS))),
                Arguments.of("no storage provider", change(document -> document.setStorageProvider(null))),
                Arguments.of("no storage key", change(document -> document.setStorageKey(null))),
                Arguments.of("blank storage key", change(document -> document.setStorageKey(" "))),
                Arguments.of("no file name", change(document -> document.setFileName(null))),
                Arguments.of("blank file name", change(document -> document.setFileName("  "))),
                Arguments.of("no file size", change(document -> document.setFileSize(null))),
                Arguments.of("zero file size", change(document -> document.setFileSize(0L))),
                Arguments.of("negative file size", change(document -> document.setFileSize(-1L))));
    }

    // --- Storage failures -------------------------------------------------------------------------

    @Test
    void metadataDatabaseFailureIsStorageUnavailableAndFetchesNoBytes() {
        when(documentService.getMetadata(DOC_ID)).thenThrow(new DataAccessResourceFailureException(CREDENTIAL_CANARY));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.STORAGE_UNAVAILABLE);
        verify(documentService, never()).getStoredContent(anyString());
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("DataAccessResourceFailureException");
    }

    @Test
    void objectStorageFailureIsStorageUnavailableWithoutTheStorageKey() {
        givenStored(POLICY);
        when(documentService.getStoredContent(DOC_ID)).thenThrow(new ObjectStorageException(
                "Failed to read " + STORAGE_KEY, new IllegalStateException("bucket waypoint-secret-bucket")));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.STORAGE_UNAVAILABLE);
        assertThat(warnings()).hasSize(1);
        assertThat(warnings().get(0)).contains("ObjectStorageException");
    }

    @Test
    void anyOtherContentReadFailureIsStorageUnavailable() {
        givenStored(POLICY);
        when(documentService.getStoredContent(DOC_ID)).thenThrow(
                new BusinessException("This Document Service currently manages new R2 documents only"));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.STORAGE_UNAVAILABLE);
    }

    @Test
    void aDocumentDeletedBetweenReadsIsNotFound() {
        givenStored(POLICY);
        when(documentService.getStoredContent(DOC_ID)).thenThrow(new ResourceNotFoundException("Document not found"));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.NOT_FOUND);
    }

    @Test
    void missingOrEmptyContentIsStorageUnavailable() {
        givenStored(POLICY);
        StoredDocument metadata = stored(POLICY.length, unchanged());
        when(documentService.getStoredContent(DOC_ID)).thenReturn(
                null,
                new DocumentService.DocumentContent(metadata, null, "text/plain", 0),
                new DocumentService.DocumentContent(metadata, new byte[0], "text/plain", 0));

        for (int attempt = 0; attempt < 3; attempt++) {
            assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.STORAGE_UNAVAILABLE);
        }
    }

    // --- Metadata re-read with the bytes ----------------------------------------------------------

    @ParameterizedTest(name = "{0}")
    @MethodSource("metadataChangedWhileReading")
    void rejectsBytesWhoseMetadataChangedAfterTheCheck(String description, Consumer<StoredDocument> change, Reason reason) {
        when(documentService.getMetadata(DOC_ID)).thenAnswer(invocation -> stored(POLICY.length, unchanged()));
        when(documentService.getStoredContent(DOC_ID)).thenAnswer(invocation -> new DocumentService.DocumentContent(
                stored(POLICY.length, change), POLICY.clone(), "text/plain", POLICY.length));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), reason);
    }

    static Stream<Arguments> metadataChangedWhileReading() {
        return Stream.of(
                Arguments.of("moved to another organization",
                        change(document -> document.setOrganizationId(OTHER_ORG)), Reason.NOT_FOUND),
                Arguments.of("different document returned",
                        change(document -> document.setId("stored-doc-x")), Reason.NOT_FOUND),
                Arguments.of("type changed",
                        change(document -> document.setDocumentType(DocumentType.PAYSTUB)), Reason.INELIGIBLE),
                Arguments.of("superseded", change(document -> document.setStatus(DocumentStatus.SUPERSEDED)),
                        Reason.INELIGIBLE),
                Arguments.of("moved out of R2",
                        change(document -> document.setStorageProvider(StorageProvider.GRIDFS)), Reason.METADATA_INCOMPLETE),
                Arguments.of("storage key changed",
                        change(document -> document.setStorageKey("organizations/x/other")), Reason.CONTENT_CHANGED),
                Arguments.of("file name changed", change(document -> document.setFileName("other.txt")),
                        Reason.CONTENT_CHANGED),
                Arguments.of("file size changed", change(document -> document.setFileSize((long) POLICY.length + 1)),
                        Reason.CONTENT_CHANGED));
    }

    // --- Content verification ---------------------------------------------------------------------

    @Test
    void bytesOfADifferentLengthThanRecordedAreRejected() {
        givenStored(POLICY);
        when(documentService.getStoredContent(DOC_ID)).thenAnswer(invocation -> new DocumentService.DocumentContent(
                stored(POLICY.length, unchanged()), "short".getBytes(StandardCharsets.UTF_8), "text/plain", 5));

        assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.CONTENT_CHANGED);
    }

    @Test
    void bytesThatDifferFromTheApprovedHashAreRejected() {
        byte[] changed = POLICY.clone();
        changed[0] ^= 0x01; // same length, one bit different
        givenStored(changed);

        SourceDocumentException failure = assertFails(() -> reader.read(ORG, DOC_ID, POLICY_SHA256), Reason.CONTENT_CHANGED);
        assertThat(failure.getMessage())
                .doesNotContain(POLICY_SHA256)
                .doesNotContain(KnowledgeApprovalService.sha256(changed));
    }

    @Test
    void anApprovedHashForOtherContentIsRejected() {
        givenStored(POLICY);

        assertFails(() -> reader.read(ORG, DOC_ID, ABC_SHA256), Reason.CONTENT_CHANGED);
    }

    // --- Failure reasons --------------------------------------------------------------------------

    @Test
    void reasonsMapOntoKnowledgeIngestionFailures() {
        assertThat(Reason.NOT_FOUND.failure()).isEqualTo(KnowledgeIngestionFailure.SOURCE_DOCUMENT_INELIGIBLE);
        assertThat(Reason.INELIGIBLE.failure()).isEqualTo(KnowledgeIngestionFailure.SOURCE_DOCUMENT_INELIGIBLE);
        assertThat(Reason.METADATA_INCOMPLETE.failure()).isEqualTo(KnowledgeIngestionFailure.SOURCE_DOCUMENT_INELIGIBLE);
        assertThat(Reason.STORAGE_UNAVAILABLE.failure()).isEqualTo(KnowledgeIngestionFailure.STORAGE_UNAVAILABLE);
        assertThat(Reason.CONTENT_CHANGED.failure()).isEqualTo(KnowledgeIngestionFailure.CONTENT_CHANGED);
    }

    @ParameterizedTest
    @EnumSource(Reason.class)
    void failuresHaveAFixedMessageAndNoCause(Reason reason) {
        SourceDocumentException failure = new SourceDocumentException(reason);

        assertThat(failure.getReason()).isEqualTo(reason);
        assertThat(failure.getFailure()).isEqualTo(reason.failure());
        assertThat(failure.getMessage()).isNotBlank().doesNotContain(FILE_CANARY).doesNotContain("organizations/");
        assertThat(failure).hasNoCause();
    }

    // --- Helpers ----------------------------------------------------------------------------------

    private static SourceDocumentException assertFails(Runnable read, Reason reason) {
        Throwable thrown = catchThrowable(read::run);
        assertThat(thrown).isInstanceOf(SourceDocumentException.class);
        SourceDocumentException failure = (SourceDocumentException) thrown;
        assertThat(failure.getReason()).isEqualTo(reason);
        assertThat(failure.getFailure()).isEqualTo(reason.failure());
        assertThat(failure).hasNoCause();
        assertThat(failure.getMessage())
                .doesNotContain(FILE_CANARY)
                .doesNotContain(STORAGE_KEY)
                .doesNotContain(TEXT_CANARY)
                .doesNotContain("s3cret");
        return failure;
    }

    private static Consumer<StoredDocument> change(Consumer<StoredDocument> change) {
        return change;
    }

    private static Consumer<StoredDocument> unchanged() {
        return document -> {
        };
    }

    private void givenStored(byte[] content) {
        givenStored(content, unchanged());
    }

    /** Each read returns fresh metadata, as the repository would; change applies to every read. */
    private void givenStored(byte[] content, Consumer<StoredDocument> change) {
        when(documentService.getMetadata(DOC_ID)).thenAnswer(invocation -> stored(content.length, change));
        when(documentService.getStoredContent(DOC_ID)).thenAnswer(invocation -> new DocumentService.DocumentContent(
                stored(content.length, change), content.clone(), "application/octet-stream", content.length));
    }

    private static StoredDocument stored(long size, Consumer<StoredDocument> change) {
        StoredDocument document = StoredDocument.builder()
                .id(DOC_ID)
                .organizationId(ORG)
                .documentType(DocumentType.COMPANY_POLICY)
                .fileName(FILE_NAME)
                .storageProvider(StorageProvider.R2)
                .storageKey(STORAGE_KEY)
                .contentType("application/octet-stream")
                .fileSize(size)
                .version(1)
                .status(DocumentStatus.ACTIVE)
                .uploadedByUserId("hr-user-41")
                .createdAt(LocalDateTime.of(2026, 10, 17, 9, 0))
                .build();
        change.accept(document);
        return document;
    }

    private List<String> warnings() {
        return logs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** No log line may carry file names, storage keys, content, hashes, ids, credentials or a stack trace. */
    private void assertLogsRevealNothing() {
        List<String> forbidden = List.of(FILE_CANARY, TEXT_CANARY, "organizations/", ".txt", ORG, OTHER_ORG, DOC_ID,
                "secret", "s3cret", "mongodb", POLICY_SHA256, ABC_SHA256);
        for (ILoggingEvent event : logs.list) {
            String message = event.getFormattedMessage();
            for (String value : forbidden) {
                assertThat(message).as("log line").doesNotContain(value);
            }
            assertThat(message).as("log line").doesNotMatch("(?s).*[0-9a-f]{64}.*");
            assertThat(event.getThrowableProxy()).as("stack trace logged with: " + message).isNull();
        }
    }
}
