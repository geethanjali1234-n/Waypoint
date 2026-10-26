package com.waypoint.storage;

import com.waypoint.exception.ObjectStorageException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Filesystem-backed {@link BinaryObjectStorage} used for local development when
 * Cloudflare R2 is not configured ({@code waypoint.documents.r2.enabled=false}).
 * Objects are written under a local root directory keyed by their storage key;
 * a sidecar {@code .meta} file records the content type so reads round-trip
 * exactly.
 *
 * <p>Production always uses {@link R2ObjectStorage}. {@code StoredDocument}
 * metadata still records {@code StorageProvider.R2} (the production target) even
 * when this implementation is active, so documents uploaded on a developer
 * machine are not expected to resolve against a real bucket.
 */
public class LocalFileSystemObjectStorage implements BinaryObjectStorage {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private final Path root;

    public LocalFileSystemObjectStorage(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    @Override
    public void store(String storageKey, byte[] content, String contentType) {
        Path target = resolve(storageKey);
        try {
            Files.createDirectories(target.getParent());
            Files.write(target, content);
            writeContentType(target, contentType);
        } catch (IOException exception) {
            throw storageFailure("store", storageKey, exception);
        }
    }

    @Override
    public StoredObject get(String storageKey) {
        Path target = resolveExisting(storageKey);
        try {
            byte[] content = Files.readAllBytes(target);
            return new StoredObject(content, readContentType(target), content.length);
        } catch (IOException exception) {
            throw storageFailure("read", storageKey, exception);
        }
    }

    @Override
    public void delete(String storageKey) {
        Path target = resolveExisting(storageKey);
        try {
            Files.deleteIfExists(target);
            Files.deleteIfExists(metaPath(target));
        } catch (IOException exception) {
            throw storageFailure("delete", storageKey, exception);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        return Files.exists(resolveExisting(storageKey));
    }

    private Path resolveExisting(String storageKey) {
        Path primary = resolve(storageKey);
        if (Files.exists(primary)) {
            return primary;
        }
        try {
            if (root.getParent() != null) {
                Path candidate1 = root.getParent().getParent() != null
                        ? root.getParent().getParent().resolve("uploads").resolve("object-store").resolve(storageKey).normalize()
                        : null;
                if (candidate1 != null && Files.exists(candidate1)) return candidate1;

                Path candidate2 = root.getParent().resolve("backend").resolve("uploads").resolve("object-store").resolve(storageKey).normalize();
                if (Files.exists(candidate2)) return candidate2;
            }
        } catch (Exception ignored) {
        }
        return primary;
    }

    private Path resolve(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new ObjectStorageException(
                    "Rejected storage key that escapes the object-store root: " + storageKey, null);
        }
        return resolved;
    }

    private Path metaPath(Path target) {
        return target.resolveSibling(target.getFileName() + ".meta");
    }

    private void writeContentType(Path target, String contentType) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("contentType", contentType == null || contentType.isBlank()
                ? DEFAULT_CONTENT_TYPE : contentType);
        try (OutputStream out = Files.newOutputStream(metaPath(target))) {
            properties.store(out, "waypoint local object metadata");
        }
    }

    private String readContentType(Path target) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(metaPath(target))) {
            properties.load(in);
        } catch (NoSuchFileException exception) {
            return DEFAULT_CONTENT_TYPE;
        } catch (IOException exception) {
            return DEFAULT_CONTENT_TYPE;
        }
        return properties.getProperty("contentType", DEFAULT_CONTENT_TYPE);
    }

    private ObjectStorageException storageFailure(String operation, String storageKey, Exception cause) {
        return new ObjectStorageException(
                "Failed to " + operation + " object in local object store: " + storageKey, cause);
    }
}
