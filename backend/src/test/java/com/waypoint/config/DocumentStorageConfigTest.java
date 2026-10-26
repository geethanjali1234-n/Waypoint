package com.waypoint.config;

import com.waypoint.storage.BinaryObjectStorage;
import com.waypoint.storage.LocalFileSystemObjectStorage;
import com.waypoint.storage.R2ObjectStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentStorageConfigTest {
    @TempDir Path root;
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(R2StorageConfig.class, LocalObjectStorageConfig.class);

    @Test
    void defaultsToR2WithNoLocalFallback() {
        runner.withPropertyValues("waypoint.documents.r2.account-id=test-account",
                "waypoint.documents.r2.access-key-id=test-access",
                "waypoint.documents.r2.secret-access-key=test-secret",
                "waypoint.documents.r2.bucket-name=test-bucket",
                "waypoint.documents.r2.endpoint=https://test-account.r2.cloudflarestorage.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(BinaryObjectStorage.class);
                    assertThat(context.getBean(BinaryObjectStorage.class)).isInstanceOf(R2ObjectStorage.class);
                    assertThat(context).doesNotHaveBean(LocalFileSystemObjectStorage.class);
                });
    }

    @Test
    void missingCredentialsFailInsteadOfSilentlyStoringLocally() {
        runner.withPropertyValues("waypoint.documents.r2.account-id=",
                "waypoint.documents.r2.access-key-id=", "waypoint.documents.r2.secret-access-key=",
                "waypoint.documents.r2.endpoint=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void localStorageRequiresExplicitOptOut() {
        runner.withPropertyValues("waypoint.documents.r2.enabled=false",
                "waypoint.documents.local.root=" + root)
                .run(context -> {
                    assertThat(context).hasSingleBean(BinaryObjectStorage.class);
                    assertThat(context.getBean(BinaryObjectStorage.class))
                            .isInstanceOf(LocalFileSystemObjectStorage.class);
                });
    }
}
