package com.waypoint.storage;

import com.waypoint.exception.ObjectStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileSystemObjectStorageTest {

    @TempDir
    Path root;

    private LocalFileSystemObjectStorage storage;

    @BeforeEach
    void setUp() {
        storage = new LocalFileSystemObjectStorage(root);
    }

    @Test
    void storesAndReadsBinaryContentWithContentType() {
        byte[] content = "supporting document".getBytes(StandardCharsets.UTF_8);

        storage.store("organizations/org-1/timesheet_supporting_document/abc-file.pdf", content, "application/pdf");

        assertThat(storage.exists("organizations/org-1/timesheet_supporting_document/abc-file.pdf")).isTrue();
        BinaryObjectStorage.StoredObject stored =
                storage.get("organizations/org-1/timesheet_supporting_document/abc-file.pdf");
        assertThat(stored.content()).isEqualTo(content);
        assertThat(stored.contentType()).isEqualTo("application/pdf");
        assertThat(stored.contentLength()).isEqualTo(content.length);
    }

    @Test
    void deleteRemovesObjectAndSidecarMetadata() {
        storage.store("organizations/org-1/report/key.txt", new byte[]{1, 2, 3}, "text/plain");

        storage.delete("organizations/org-1/report/key.txt");

        assertThat(storage.exists("organizations/org-1/report/key.txt")).isFalse();
    }

    @Test
    void readingMissingObjectFails() {
        assertThatThrownBy(() -> storage.get("organizations/org-1/report/missing.txt"))
                .isInstanceOf(ObjectStorageException.class);
    }

    @Test
    void rejectsStorageKeysThatEscapeTheRoot() {
        assertThatThrownBy(() -> storage.store("../escape.txt", new byte[]{1}, "text/plain"))
                .isInstanceOf(ObjectStorageException.class)
                .hasMessageContaining("escapes the object-store root");
    }
}
