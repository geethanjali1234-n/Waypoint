package com.waypoint.service;

import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.StorageProvider;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.storage.BinaryObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class DocumentStorageMigrationServiceTest {
    @TempDir Path root;

    @Test
    void copiesOriginalBytesUnderExistingKeyAndRetainsLocalFile() throws Exception {
        byte[] bytes = "original uploaded hours".getBytes();
        StoredDocument document = document(bytes.length);
        Path source = root.resolve(document.getStorageKey());
        Files.createDirectories(source.getParent());
        Files.write(source, bytes);
        StoredDocumentRepository repository = mock(StoredDocumentRepository.class);
        BinaryObjectStorage storage = mock(BinaryObjectStorage.class);
        when(repository.findAll()).thenReturn(List.of(document));
        when(storage.get(document.getStorageKey())).thenReturn(
                new BinaryObjectStorage.StoredObject(bytes, "text/plain", bytes.length));

        new DocumentStorageMigrationService(repository, storage, root.toString()).migrateLocalDocuments();

        verify(storage).store(document.getStorageKey(), bytes, "text/plain");
        assertThat(Files.readAllBytes(source)).isEqualTo(bytes);
        verify(repository, never()).save(any());
    }

    @Test
    void neverOverwritesAnExistingR2Object() throws Exception {
        StoredDocument document = document(8);
        Files.createDirectories(root.resolve("organizations"));
        Files.writeString(root.resolve(document.getStorageKey()), "old file");
        StoredDocumentRepository repository = mock(StoredDocumentRepository.class);
        BinaryObjectStorage storage = mock(BinaryObjectStorage.class);
        when(repository.findAll()).thenReturn(List.of(document));
        when(storage.exists(document.getStorageKey())).thenReturn(true);

        new DocumentStorageMigrationService(repository, storage, root.toString()).migrateLocalDocuments();

        verify(storage, never()).store(any(), any(), any());
    }

    @Test
    void rejectsLocalContentWhoseSizeDoesNotMatchUpload() throws Exception {
        StoredDocument document = document(500);
        Files.createDirectories(root.resolve("organizations"));
        Files.writeString(root.resolve(document.getStorageKey()), "placeholder");
        StoredDocumentRepository repository = mock(StoredDocumentRepository.class);
        BinaryObjectStorage storage = mock(BinaryObjectStorage.class);
        when(repository.findAll()).thenReturn(List.of(document));

        new DocumentStorageMigrationService(repository, storage, root.toString()).migrateLocalDocuments();

        verify(storage, never()).store(any(), any(), any());
    }

    private StoredDocument document(long size) {
        return StoredDocument.builder().id("doc-1").storageProvider(StorageProvider.R2)
                .storageKey("organizations/timesheet.txt").contentType("text/plain").fileSize(size).build();
    }
}
