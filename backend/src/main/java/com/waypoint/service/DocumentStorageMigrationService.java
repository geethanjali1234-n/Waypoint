package com.waypoint.service;

import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.StorageProvider;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.storage.BinaryObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/** Copies legacy local objects to R2 under their existing keys, preserving attachments. */
@Service
@ConditionalOnProperty(name = {"waypoint.documents.r2.enabled",
        "waypoint.documents.migrate-local-on-startup"}, havingValue = "true", matchIfMissing = true)
public class DocumentStorageMigrationService {
    private static final Logger logger = LoggerFactory.getLogger(DocumentStorageMigrationService.class);
    private final StoredDocumentRepository repository;
    private final BinaryObjectStorage storage;
    private final List<Path> roots;

    public DocumentStorageMigrationService(StoredDocumentRepository repository, BinaryObjectStorage storage,
            @Value("${waypoint.documents.local.root:./uploads/object-store}") String localRoot) {
        this.repository = repository;
        this.storage = storage;
        Path configured = Path.of(localRoot);
        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        candidates.add(configured.toAbsolutePath().normalize());
        if (!configured.isAbsolute()) {
            Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            for (int level = 0; level < 6 && current != null; level++, current = current.getParent()) {
                candidates.add(current.resolve(configured).normalize());
                candidates.add(current.resolve("backend").resolve(configured).normalize());
            }
        }
        roots = List.copyOf(candidates);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLocalDocuments() {
        if (roots.stream().noneMatch(Files::isDirectory)) return;
        for (StoredDocument document : repository.findAll()) {
            if (document.getStorageProvider() != StorageProvider.R2 || document.getStorageKey() == null) continue;
            try {
                Path relative = Path.of(document.getStorageKey());
                if (relative.isAbsolute()) continue;
                if (relative.normalize().startsWith("..")) continue;
                Path source = roots.stream().map(root -> root.resolve(relative).normalize())
                        .filter(Files::isRegularFile).findFirst().orElse(null);
                if (source == null || storage.exists(document.getStorageKey())) continue;
                byte[] content = Files.readAllBytes(source);
                if (content.length == 0 || (document.getFileSize() != null
                        && document.getFileSize() != content.length)) {
                    logger.warn("Skipping local document {}: size does not match uploaded file", document.getId());
                    continue;
                }
                String prefix = new String(content, 0, Math.min(content.length, 128),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (prefix.startsWith("Waypoint Certified Supporting Document")
                        || prefix.startsWith("Waypoint Document:")) continue;
                storage.store(document.getStorageKey(), content,
                        document.getContentType() == null ? "application/octet-stream" : document.getContentType());
                if (!Arrays.equals(content, storage.get(document.getStorageKey()).content())) {
                    throw new IllegalStateException("R2 verification failed");
                }
                logger.info("Copied local document {} to R2", document.getId());
            } catch (Exception exception) {
                logger.warn("Could not migrate local document {}; original file retained ({})",
                        document.getId(), exception.getClass().getSimpleName());
            }
        }
    }
}
