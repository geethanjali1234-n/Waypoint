package com.waypoint.service;

import com.waypoint.storage.BinaryObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObjectStorageMigrationServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void migratesLocalObjectStoreFilesUsingRelativeStorageKeysAndContentTypes() throws IOException {
        Path root = tempDir.resolve("object-store");
        Path document = root.resolve("organizations/org-1/timesheet_supporting_document/doc-1-hours.pdf");
        Files.createDirectories(document.getParent());
        Files.write(document, "pdf-bytes".getBytes(StandardCharsets.UTF_8));
        Files.writeString(document.resolveSibling(document.getFileName() + ".meta"), "contentType=application/pdf\n");

        InMemoryStorage storage = new InMemoryStorage();
        ObjectStorageMigrationService service = new ObjectStorageMigrationService(storage);

        ObjectStorageMigrationService.MigrationResult result = service.migrate(root);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.migrated()).isEqualTo(1);
        assertThat(result.skippedExisting()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(storage.content).containsKey("organizations/org-1/timesheet_supporting_document/doc-1-hours.pdf");
        assertThat(storage.contentType.get("organizations/org-1/timesheet_supporting_document/doc-1-hours.pdf")).isEqualTo("application/pdf");
    }

    @Test
    void skipsObjectsAlreadyPresentInSharedStorage() throws IOException {
        Path root = tempDir.resolve("object-store");
        Path document = root.resolve("organizations/org-1/timesheet_supporting_document/doc-1-hours.pdf");
        Files.createDirectories(document.getParent());
        Files.write(document, "pdf-bytes".getBytes(StandardCharsets.UTF_8));

        InMemoryStorage storage = new InMemoryStorage();
        storage.store("organizations/org-1/timesheet_supporting_document/doc-1-hours.pdf", "existing".getBytes(StandardCharsets.UTF_8), "application/pdf");
        ObjectStorageMigrationService service = new ObjectStorageMigrationService(storage);

        ObjectStorageMigrationService.MigrationResult result = service.migrate(root);

        assertThat(result.scanned()).isEqualTo(1);
        assertThat(result.migrated()).isZero();
        assertThat(result.skippedExisting()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(new String(storage.content.get("organizations/org-1/timesheet_supporting_document/doc-1-hours.pdf"), StandardCharsets.UTF_8)).isEqualTo("existing");
    }

    private static final class InMemoryStorage implements BinaryObjectStorage {
        private final Map<String, byte[]> content = new HashMap<>();
        private final Map<String, String> contentType = new HashMap<>();

        @Override
        public void store(String storageKey, byte[] content, String contentType) {
            this.content.put(storageKey, content);
            this.contentType.put(storageKey, contentType);
        }

        @Override
        public StoredObject get(String storageKey) {
            byte[] bytes = content.get(storageKey);
            return new StoredObject(bytes, contentType.get(storageKey), bytes == null ? 0 : bytes.length);
        }

        @Override
        public void delete(String storageKey) {
            content.remove(storageKey);
            contentType.remove(storageKey);
        }

        @Override
        public boolean exists(String storageKey) {
            return content.containsKey(storageKey);
        }
    }
}
