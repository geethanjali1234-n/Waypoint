package com.waypoint.service;

import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.StorageProvider;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.storage.BinaryObjectStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private StoredDocumentRepository repository;

    private InMemoryObjectStorage storage;
    private DocumentService service;

    @BeforeEach
    void setUp() {
        storage = new InMemoryObjectStorage();
        service = new DocumentService(repository, storage);
    }

    @Test
    void successfulUploadStoresBinaryAndR2Metadata() {
        when(repository.save(any(StoredDocument.class))).thenAnswer(invocation -> {
            StoredDocument document = invocation.getArgument(0);
            document.setId("document-1");
            return document;
        });

        StoredDocument saved = service.store(uploadRequest("approved timesheet.pdf"));

        assertThat(saved.getId()).isEqualTo("document-1");
        assertThat(saved.getStorageProvider()).isEqualTo(StorageProvider.R2);
        assertThat(saved.getStatus()).isEqualTo(DocumentStatus.ACTIVE);
        assertThat(saved.getVersion()).isEqualTo(1);
        assertThat(saved.getFileSize()).isEqualTo(12L);
        assertThat(saved.getStorageKey())
                .startsWith("organizations/org-1/timesheet/")
                .endsWith("-approved_timesheet.pdf");
        assertThat(storage.exists(saved.getStorageKey())).isTrue();
    }

    @Test
    void generatedStorageKeysAreUniqueEvenForTheSameOriginalFileName() {
        when(repository.save(any(StoredDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StoredDocument first = service.store(uploadRequest("timesheet.pdf"));
        StoredDocument second = service.store(uploadRequest("timesheet.pdf"));

        assertThat(first.getStorageKey()).isNotEqualTo(second.getStorageKey());
    }

    @Test
    void retrievalReturnsMetadataAndStoredBinary() {
        byte[] content = "stored bytes".getBytes(StandardCharsets.UTF_8);
        StoredDocument metadata = StoredDocument.builder()
                .id("document-1")
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/org-1/report/key.pdf")
                .contentType("application/pdf")
                .build();
        storage.store(metadata.getStorageKey(), content, metadata.getContentType());
        when(repository.findById("document-1")).thenReturn(Optional.of(metadata));

        DocumentService.DocumentContent result = service.getContent("document-1");

        assertThat(result.metadata()).isSameAs(metadata);
        assertThat(result.content()).isEqualTo(content);
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.contentLength()).isEqualTo(content.length);
    }

    @Test
    void missingStorageObjectFailsWithoutWritingAPlaceholder() {
        StoredDocument metadata = StoredDocument.builder()
                .id("doc-missing")
                .fileName("Timesheet_Signoff_Lilly_W36.pdf")
                .documentType(DocumentType.TIMESHEET_SUPPORTING_DOCUMENT)
                .organizationId("org-1")
                .ownerUserId("user-1")
                .storageProvider(StorageProvider.R2)
                .storageKey("demo/missing/Timesheet_Signoff_Lilly_W36.pdf")
                .contentType("application/pdf")
                .build();
        when(repository.findById("doc-missing")).thenReturn(Optional.of(metadata));

        assertThatThrownBy(() -> service.getContent("doc-missing"))
                .isInstanceOf(ObjectStorageException.class);
        assertThat(storage.exists(metadata.getStorageKey())).isFalse();
    }

    @Test
    void missingImageFailsWithoutWritingAPlaceholder() {
        StoredDocument metadata = StoredDocument.builder()
                .id("doc-missing-image")
                .fileName("WhatsApp_Image_2026-09-21.jpeg")
                .documentType(DocumentType.TIMESHEET_SUPPORTING_DOCUMENT)
                .organizationId("org-1")
                .ownerUserId("user-1")
                .storageProvider(StorageProvider.R2)
                .storageKey("demo/missing/WhatsApp_Image_2026-09-21.jpeg")
                .contentType("image/jpeg")
                .build();
        when(repository.findById("doc-missing-image")).thenReturn(Optional.of(metadata));

        assertThatThrownBy(() -> service.getContent("doc-missing-image"))
                .isInstanceOf(ObjectStorageException.class);
        assertThat(storage.exists(metadata.getStorageKey())).isFalse();
    }

    @Test
    void retrievalRejectsPreviouslyStoredTextFallbackThatWasLabeledAsImage() {
        byte[] fallback = "Waypoint Certified Supporting Document\nFile Name: scan.jpeg".getBytes(StandardCharsets.UTF_8);
        StoredDocument metadata = StoredDocument.builder()
                .id("doc-corrupt-type")
                .fileName("scan.jpeg")
                .storageProvider(StorageProvider.R2)
                .storageKey("demo/stored/scan.jpeg")
                .contentType("image/jpeg")
                .build();
        storage.store(metadata.getStorageKey(), fallback, "image/jpeg");
        when(repository.findById("doc-corrupt-type")).thenReturn(Optional.of(metadata));

        assertThatThrownBy(() -> service.getContent("doc-corrupt-type"))
                .isInstanceOf(ObjectStorageException.class);
        assertThat(storage.get(metadata.getStorageKey()).content()).isEqualTo(fallback);
    }

    @Test
    void storedContentReturnsTheExactBytesHeldInStorage() {
        byte[] pdf = "%PDF-1.7 offer letter".getBytes(StandardCharsets.US_ASCII);
        StoredDocument metadata = StoredDocument.builder()
                .id("offer-doc")
                .fileName("offer-letter.pdf")
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/org-1/requested_document/offer-letter.pdf")
                .contentType("application/pdf")
                .fileSize((long) pdf.length)
                .build();
        storage.store(metadata.getStorageKey(), pdf, "application/pdf");
        when(repository.findById("offer-doc")).thenReturn(Optional.of(metadata));

        DocumentService.DocumentContent result = service.getStoredContent("offer-doc");

        assertThat(result.content()).isEqualTo(pdf);
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.metadata()).isSameAs(metadata);
    }

    @Test
    void storedContentNeverSynthesizesFallbackForMissingObject() {
        StoredDocument metadata = StoredDocument.builder()
                .id("missing-doc")
                .fileName("offer-letter.pdf")
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/org-1/requested_document/missing.pdf")
                .build();
        when(repository.findById("missing-doc")).thenReturn(Optional.of(metadata));

        assertThatThrownBy(() -> service.getStoredContent("missing-doc"))
                .isInstanceOf(ObjectStorageException.class);

        assertThat(storage.exists(metadata.getStorageKey())).isFalse();
    }

    @Test
    void storedContentRejectsPlaceholderPersistedByAnEarlierPreviewOfAMissingFile() {
        byte[] realPdf = "%PDF-1.7 the real generated offer letter".getBytes(StandardCharsets.US_ASCII);
        StoredDocument metadata = StoredDocument.builder()
                .id("offer-doc")
                .fileName("offer-letter.pdf")
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/org-1/requested_document/offer-letter.pdf")
                .contentType("application/pdf")
                .fileSize((long) realPdf.length)
                .build();
        when(repository.findById("offer-doc")).thenReturn(Optional.of(metadata));

        byte[] placeholder = "%PDF-1.7 old placeholder".getBytes(StandardCharsets.US_ASCII);
        storage.store(metadata.getStorageKey(), placeholder, "application/pdf");

        assertThatThrownBy(() -> service.getStoredContent("offer-doc"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessage("Stored document content does not match the uploaded document");
    }

    @Test
    void storedContentRejectsGeneratedFallbackTextEvenWithoutRecordedSize() {
        StoredDocument metadata = StoredDocument.builder()
                .id("text-doc")
                .fileName("note.txt")
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/org-1/requested_document/note.txt")
                .build();
        storage.store(metadata.getStorageKey(),
                "Waypoint Certified Supporting Document\nFile Name: note.txt".getBytes(StandardCharsets.UTF_8), "text/plain");
        when(repository.findById("text-doc")).thenReturn(Optional.of(metadata));

        assertThatThrownBy(() -> service.getStoredContent("text-doc")).isInstanceOf(ObjectStorageException.class);
    }

    @Test
    void deletionRemovesBinaryThenMetadata() {
        StoredDocument metadata = StoredDocument.builder()
                .id("document-1")
                .storageProvider(StorageProvider.R2)
                .storageKey("organizations/org-1/report/key.pdf")
                .build();
        storage.store(metadata.getStorageKey(), new byte[]{1}, "application/pdf");
        when(repository.findById("document-1")).thenReturn(Optional.of(metadata));

        service.delete("document-1");

        assertThat(storage.exists(metadata.getStorageKey())).isFalse();
        verify(repository).delete(metadata);
    }

    @Test
    void storageFailureDoesNotSaveMetadata() {
        storage.failStore = true;

        assertThatThrownBy(() -> service.store(uploadRequest("timesheet.pdf")))
                .isInstanceOf(ObjectStorageException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void metadataFailureTriggersCompensatingObjectDeletion() {
        when(repository.save(any(StoredDocument.class))).thenThrow(new RuntimeException("MongoDB unavailable"));

        assertThatThrownBy(() -> service.store(uploadRequest("timesheet.pdf")))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("rolled back");

        assertThat(storage.objects).isEmpty();
    }

    @Test
    void persistedMetadataUsesSanitizedFileNameAndExactDesignFields() {
        when(repository.save(any(StoredDocument.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.store(uploadRequest("../private/approved file.pdf"));

        ArgumentCaptor<StoredDocument> captor = ArgumentCaptor.forClass(StoredDocument.class);
        verify(repository).save(captor.capture());
        StoredDocument metadata = captor.getValue();
        assertThat(metadata.getOrganizationId()).isEqualTo("org-1");
        assertThat(metadata.getOwnerUserId()).isEqualTo("employee-1");
        assertThat(metadata.getDocumentType()).isEqualTo(DocumentType.TIMESHEET);
        assertThat(metadata.getRelatedEntityType()).isEqualTo("TIMESHEET");
        assertThat(metadata.getRelatedEntityId()).isEqualTo("timesheet-1");
        assertThat(metadata.getFileName()).isEqualTo("approved_file.pdf");
        assertThat(metadata.getUploadedByUserId()).isEqualTo("employee-1");
        assertThat(metadata.getCreatedAt()).isNotNull();
    }

    private DocumentUploadRequest uploadRequest(String fileName) {
        return new DocumentUploadRequest(
                "org-1",
                "employee-1",
                DocumentType.TIMESHEET,
                "TIMESHEET",
                "timesheet-1",
                fileName,
                "application/pdf",
                "test content".getBytes(StandardCharsets.UTF_8),
                null,
                "employee-1");
    }

    private static final class InMemoryObjectStorage implements BinaryObjectStorage {
        private final Map<String, StoredObject> objects = new HashMap<>();
        private boolean failStore;

        @Override
        public void store(String storageKey, byte[] content, String contentType) {
            if (failStore) {
                throw new ObjectStorageException("simulated R2 failure", new RuntimeException("unavailable"));
            }
            objects.put(storageKey, new StoredObject(content.clone(), contentType, content.length));
        }

        @Override
        public StoredObject get(String storageKey) {
            return objects.get(storageKey);
        }

        @Override
        public void delete(String storageKey) {
            objects.remove(storageKey);
        }

        @Override
        public boolean exists(String storageKey) {
            return objects.containsKey(storageKey);
        }
    }
}
