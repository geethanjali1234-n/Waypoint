package com.waypoint.service;

import com.waypoint.storage.BinaryObjectStorage;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * One-time bridge for moving locally uploaded MyHR documents into the configured
 * shared object store. Local development stores object bytes under
 * {@code uploads/object-store} using the same storage keys saved in MongoDB.
 * When R2 is enabled, this service can copy those bytes to R2 without changing
 * database metadata because the keys stay identical.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "waypoint.startup.object-storage-migration.enabled", havingValue = "true")
public class ObjectStorageMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(ObjectStorageMigrationService.class);
    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final BinaryObjectStorage objectStorage;

    @Value("${waypoint.documents.local.root:./uploads/object-store}")
    private String localRoot;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLocalObjectStoreToSharedStorage() {
        MigrationResult result = migrate(Path.of(localRoot));
        logger.info("Object storage migration completed: scanned={}, migrated={}, skippedExisting={}, failed={}",
                result.scanned(), result.migrated(), result.skippedExisting(), result.failed());
    }

    MigrationResult migrate(Path sourceRoot) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            logger.info("Object storage migration skipped; local object store root does not exist: {}", root);
            return new MigrationResult(0, 0, 0, 0);
        }

        int scanned = 0;
        int migrated = 0;
        int skippedExisting = 0;
        int failed = 0;

        try (Stream<Path> paths = Files.walk(root)) {
            for (Path file : paths.filter(Files::isRegularFile).filter(path -> !isMetadataFile(path)).toList()) {
                scanned++;
                String storageKey = root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
                try {
                    if (objectStorage.exists(storageKey)) {
                        skippedExisting++;
                        continue;
                    }
                    objectStorage.store(storageKey, Files.readAllBytes(file), readContentType(file));
                    migrated++;
                } catch (RuntimeException | IOException exception) {
                    failed++;
                    logger.warn("Failed to migrate local object {}", storageKey, exception);
                }
            }
        } catch (IOException exception) {
            logger.warn("Object storage migration could not scan local root {}", root, exception);
            failed++;
        }

        return new MigrationResult(scanned, migrated, skippedExisting, failed);
    }

    private boolean isMetadataFile(Path path) {
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        return fileName.endsWith(".meta");
    }

    private String readContentType(Path file) {
        Path meta = file.resolveSibling(file.getFileName() + ".meta");
        if (!Files.isRegularFile(meta)) {
            return DEFAULT_CONTENT_TYPE;
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(meta)) {
            properties.load(input);
            String contentType = properties.getProperty("contentType");
            return contentType == null || contentType.isBlank() ? DEFAULT_CONTENT_TYPE : contentType;
        } catch (IOException exception) {
            return DEFAULT_CONTENT_TYPE;
        }
    }

    record MigrationResult(int scanned, int migrated, int skippedExisting, int failed) {
    }
}
