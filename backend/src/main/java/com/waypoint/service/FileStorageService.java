package com.waypoint.service;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

@Service
public class FileStorageService {

    private static final String GRIDFS_BUCKET_NAME = "profile-images";
    private static final String GRIDFS_PATH_PREFIX = "gridfs/";

    private final Path uploadRoot;
    private final List<Path> uploadRoots;
    private final GridFSBucket sharedStorage;
    private final boolean localStorageAllowed;

    @Autowired
    public FileStorageService(
            @Value("${file.upload-dir:./uploads}") String uploadDir,
            MongoTemplate mongoTemplate) {
        this(uploadDir, createSharedStorage(mongoTemplate), false);
    }

    /**
     * Local-only constructor retained for unit tests and explicit local
     * development tools. The application constructor above uses GridFS by
     * default so uploaded bytes are stored with the shared MongoDB database.
     */
    public FileStorageService(String uploadDir) {
        this(uploadDir, null, true);
    }

    FileStorageService(String uploadDir, GridFSBucket sharedStorage) {
        this(uploadDir, sharedStorage, false);
    }

    private FileStorageService(String uploadDir, GridFSBucket sharedStorage, boolean localStorageAllowed) {
        Path configuredRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
        this.uploadRoots = candidateStorageRoots(uploadDir);
        this.uploadRoot = selectWriteRoot(configuredRoot, this.uploadRoots);
        this.sharedStorage = sharedStorage;
        this.localStorageAllowed = localStorageAllowed;
    }

    /**
     * Legacy local files may have been written relative to whatever working
     * directory the backend happened to start from (repo root vs. the
     * backend module directory), so a single configured root can miss files
     * that are really there. Mirrors ResumeStorageService's candidate-root
     * search so migration/removal/loading find them regardless of which
     * directory the process is launched from.
     */
    private List<Path> candidateStorageRoots(String configuredStorageRoot) {
        Path configuredPath = Paths.get(configuredStorageRoot);
        if (configuredPath.isAbsolute()) {
            return List.of(configuredPath.toAbsolutePath().normalize());
        }

        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        Path currentDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

        for (int level = 0; level < 6 && currentDirectory != null; level++) {
            candidates.add(currentDirectory.resolve(configuredPath).normalize());
            currentDirectory = currentDirectory.getParent();
        }

        return List.copyOf(candidates);
    }

    private Path selectWriteRoot(Path configuredRoot, List<Path> candidates) {
        return candidates.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("profile-pictures"))
                        || Files.isDirectory(candidate.resolve("company-logos")))
                .findFirst()
                .orElse(configuredRoot);
    }

    private static GridFSBucket createSharedStorage(MongoTemplate mongoTemplate) {
        if (mongoTemplate == null) {
            return null;
        }

        return GridFSBuckets.create(mongoTemplate.getDb(), GRIDFS_BUCKET_NAME);
    }

    public boolean isSharedStorageEnabled() {
        return sharedStorage != null;
    }

    /**
     * Stores a multipart file for {@code subdirectory/ownerId}, keeping the
     * uploader's original file name (sanitized) so it can be displayed back to
     * them later. Any file already stored for that owner/subdirectory is
     * replaced, so each owner only ever has the one current file.
     *
     * @return the relative path to persist on the entity. When shared storage
     *     is available this is a {@code gridfs/<objectId>/<fileName>} path
     *     that can be resolved from any backend instance; otherwise it is a
     *     {@code subdirectory/ownerId/fileName} path local to this machine.
     */
    public String store(MultipartFile file, String subdirectory, String ownerId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("No file was provided");
        }

        String sanitizedFileName = sanitizeFileName(file.getOriginalFilename());

        if (sharedStorage != null) {
            return storeInSharedStorage(file, subdirectory, ownerId, sanitizedFileName);
        }

        if (!localStorageAllowed) {
            throw new BusinessException(
                    "Shared file storage is not configured. Set MONGODB_URI and restart the backend.");
        }

        return storeLocally(file, subdirectory, ownerId, sanitizedFileName);
    }

    /**
     * Deletes any file(s) currently stored for {@code subdirectory/ownerId},
     * leaving the owner with no file in that category. Removes from shared
     * storage and best-effort cleans up any legacy local file, since either
     * may be the one currently referenced by the entity.
     */
    public void remove(String subdirectory, String ownerId) {
        if (sharedStorage != null) {
            deleteSharedFiles(subdirectory, ownerId);
        }

        for (Path root : uploadRoots) {
            Path ownerDir = root.resolve(subdirectory).resolve(ownerId).normalize();
            if (!ownerDir.startsWith(root)) {
                throw new BusinessException("Invalid file destination");
            }

            try {
                clearDirectory(ownerDir);
            } catch (IOException exception) {
                throw new BusinessException("Failed to remove uploaded file");
            }
        }
    }

    /**
     * The stored path of the one current file for {@code subdirectory/ownerId}
     * (the same path {@link #store} returned for it), if there is one - for
     * callers that identify a file by its owner instead of keeping the path
     * on an entity.
     */
    public Optional<String> findStoredPath(String subdirectory, String ownerId) {
        if (sharedStorage != null) {
            GridFSFile file = sharedStorage.find(Filters.and(
                            Filters.eq("metadata.subdirectory", subdirectory),
                            Filters.eq("metadata.ownerId", ownerId)))
                    .sort(new Document("uploadDate", -1))
                    .first();
            if (file != null) {
                Document metadata = file.getMetadata();
                String fileName = metadata != null && metadata.getString("fileName") != null
                        ? metadata.getString("fileName") : file.getFilename();
                return Optional.of(gridFsStoredPath(file.getObjectId(), fileName));
            }
        }

        for (Path root : uploadRoots) {
            Path ownerDir = root.resolve(subdirectory).resolve(ownerId).normalize();
            if (!ownerDir.startsWith(root) || !Files.isDirectory(ownerDir)) {
                continue;
            }
            try (Stream<Path> files = Files.list(ownerDir)) {
                Optional<Path> current = files.filter(Files::isRegularFile).findFirst();
                if (current.isPresent()) {
                    return Optional.of(subdirectory + "/" + ownerId + "/" + current.get().getFileName());
                }
            } catch (IOException ignored) {
                // An unreadable directory means no usable file for this owner.
            }
        }
        return Optional.empty();
    }

    /**
     * Loads a previously stored file's bytes, resolving either a shared
     * GridFS path or a legacy local path.
     */
    public StoredFile load(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new ResourceNotFoundException("File is not available");
        }

        String normalizedStoredPath = normalizeStoredPath(storedPath);
        if (normalizedStoredPath.startsWith(GRIDFS_PATH_PREFIX)) {
            ObjectId objectId = parseGridFsId(normalizedStoredPath)
                    .orElseThrow(() -> new ResourceNotFoundException("File is not available"));
            return loadFromSharedStorage(objectId);
        }

        Path file = resolveStoredPaths(normalizedStoredPath).stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("File is not available"));

        try {
            byte[] content = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();
            MediaType mediaType = MediaTypeFactory.getMediaType(fileName)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            return new StoredFile(content, fileName, mediaType);
        } catch (IOException exception) {
            throw new ResourceNotFoundException("File is not available");
        }
    }

    /**
     * Moves legacy local files into GridFS without deleting the originals.
     * The returned map is keyed by the old relative path so profile
     * snapshots can be updated safely by the migration component.
     */
    public Map<String, String> migrateLocalFilesToSharedStorage() {
        if (sharedStorage == null) {
            return Map.of();
        }

        Map<String, String> migratedPaths = new LinkedHashMap<>();
        for (String subdirectory : List.of("profile-pictures", "company-logos")) {
            for (Path root : uploadRoots) {
                Path subdirectoryRoot = root.resolve(subdirectory).normalize();
                if (!subdirectoryRoot.startsWith(root) || !Files.isDirectory(subdirectoryRoot)) {
                    continue;
                }

                try (var ownerDirectories = Files.list(subdirectoryRoot)) {
                    for (Path ownerDirectory : ownerDirectories.filter(Files::isDirectory).toList()) {
                        String ownerId = ownerDirectory.getFileName().toString();

                        try (var files = Files.list(ownerDirectory)) {
                            for (Path file : files.filter(Files::isRegularFile).toList()) {
                                String legacyPath = subdirectory + "/" + ownerId + "/" + file.getFileName();
                                migrateLocalFile(file, subdirectory, ownerId, legacyPath)
                                        .ifPresent(newPath -> migratedPaths.putIfAbsent(legacyPath, newPath));
                            }
                        } catch (IOException ignored) {
                            // Continue migrating other owner directories.
                        }
                    }
                } catch (IOException ignored) {
                    // Continue migrating the other candidate root.
                }
            }
        }

        return Map.copyOf(migratedPaths);
    }

    /**
     * Copies a file from a different, previously-used GridFS bucket into
     * this service's bucket/metadata convention, so a profile still pointing
     * at a bare GridFS object id from a retired storage implementation keeps
     * working. Leaves the original bucket's copy untouched.
     *
     * @return the new {@code gridfs/<objectId>/<fileName>} path, or empty if
     *     shared storage is unavailable or the legacy file cannot be found.
     */
    public Optional<String> copyLegacyObjectIdPicture(
            GridFSBucket legacyBucket, String legacyObjectIdHex, String subdirectory, String ownerId) {
        if (sharedStorage == null || !ObjectId.isValid(legacyObjectIdHex)) {
            return Optional.empty();
        }

        ObjectId legacyId = new ObjectId(legacyObjectIdHex);
        GridFSFile legacyFile;
        try {
            legacyFile = legacyBucket.find(Filters.eq("_id", legacyId)).first();
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
        if (legacyFile == null) {
            return Optional.empty();
        }

        String fileName = sharedFileName(legacyFile);
        MediaType mediaType = sharedMediaType(fileName, legacyFile);
        Document metadata = new Document()
                .append("subdirectory", subdirectory)
                .append("ownerId", ownerId)
                .append("fileName", fileName)
                .append("contentType", mediaType.toString());

        try (var inputStream = legacyBucket.openDownloadStream(legacyId)) {
            ObjectId newId = sharedStorage.uploadFromStream(
                    fileName, inputStream, new GridFSUploadOptions().metadata(metadata));
            return Optional.of(gridFsStoredPath(newId, fileName));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    public FileMetadata getMetadata(String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            return null;
        }

        Path path = uploadRoot.resolve(relativePath).normalize();
        if (!Files.exists(path)) {
            return null;
        }

        try {
            long sizeBytes = Files.size(path);
            LocalDateTime uploadedAt = LocalDateTime.ofInstant(
                    Files.getLastModifiedTime(path).toInstant(), ZoneId.systemDefault());
            String fileName = path.getFileName().toString();
            return new FileMetadata(fileName, sizeBytes, uploadedAt);
        } catch (IOException exception) {
            return null;
        }
    }

    private List<Path> resolveStoredPaths(String normalizedStoredPath) {
        Path relativePath;
        try {
            relativePath = Paths.get(normalizedStoredPath);
        } catch (InvalidPathException exception) {
            throw new ResourceNotFoundException("File is not available");
        }

        if (relativePath.isAbsolute()) {
            throw new ResourceNotFoundException("File is not available");
        }

        List<Path> resolvedPaths = new ArrayList<>();
        for (Path root : uploadRoots) {
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root)) {
                throw new ResourceNotFoundException("File is not available");
            }
            resolvedPaths.add(file);
        }
        return resolvedPaths;
    }

    private String storeInSharedStorage(
            MultipartFile file, String subdirectory, String ownerId, String fileName) {
        deleteSharedFiles(subdirectory, ownerId);

        MediaType mediaType = MediaTypeFactory.getMediaType(fileName)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
        Document metadata = new Document()
                .append("subdirectory", subdirectory)
                .append("ownerId", ownerId)
                .append("fileName", fileName)
                .append("contentType", mediaType.toString());

        try (InputStream inputStream = file.getInputStream()) {
            ObjectId objectId = sharedStorage.uploadFromStream(
                    fileName, inputStream, new GridFSUploadOptions().metadata(metadata));
            return gridFsStoredPath(objectId, fileName);
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("Failed to store uploaded file");
        }
    }

    private String storeLocally(
            MultipartFile file, String subdirectory, String ownerId, String sanitizedFileName) {
        Path ownerDir = uploadRoot.resolve(subdirectory).resolve(ownerId).normalize();
        if (!ownerDir.startsWith(uploadRoot)) {
            throw new BusinessException("Invalid file destination");
        }

        try {
            Files.createDirectories(ownerDir);
            clearDirectory(ownerDir);

            Path targetPath = ownerDir.resolve(sanitizedFileName);
            try (var inputStream = file.getInputStream()) {
                Files.copy(inputStream, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new BusinessException("Failed to store uploaded file");
        }

        return subdirectory + "/" + ownerId + "/" + sanitizedFileName;
    }

    private void deleteSharedFiles(String subdirectory, String ownerId) {
        if (sharedStorage == null) {
            return;
        }

        try {
            for (GridFSFile file : sharedStorage.find(Filters.and(
                    Filters.eq("metadata.subdirectory", subdirectory),
                    Filters.eq("metadata.ownerId", ownerId)))) {
                sharedStorage.delete(file.getObjectId());
            }
        } catch (RuntimeException ignored) {
            // Best-effort cleanup; a missing shared file should not block the caller.
        }
    }

    private Optional<String> migrateLocalFile(Path file, String subdirectory, String ownerId, String legacyPath) {
        try {
            GridFSFile existing = sharedStorage.find(Filters.eq("metadata.legacyPath", legacyPath)).first();
            if (existing != null) {
                return Optional.of(gridFsStoredPath(existing.getObjectId(), sharedFileName(existing)));
            }

            String fileName = sanitizeFileName(file.getFileName().toString());
            MediaType mediaType = MediaTypeFactory.getMediaType(fileName)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            Document metadata = new Document()
                    .append("subdirectory", subdirectory)
                    .append("ownerId", ownerId)
                    .append("fileName", fileName)
                    .append("contentType", mediaType.toString())
                    .append("legacyPath", legacyPath);

            ObjectId objectId;
            try (InputStream inputStream = Files.newInputStream(file)) {
                objectId = sharedStorage.uploadFromStream(
                        fileName, inputStream, new GridFSUploadOptions().metadata(metadata));
            }
            return Optional.of(gridFsStoredPath(objectId, fileName));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private StoredFile loadFromSharedStorage(ObjectId objectId) {
        if (sharedStorage == null) {
            throw new ResourceNotFoundException("File is not available");
        }

        GridFSFile file;
        try {
            file = sharedStorage.find(Filters.eq("_id", objectId)).first();
        } catch (RuntimeException exception) {
            throw new ResourceNotFoundException("File is not available");
        }
        if (file == null) {
            throw new ResourceNotFoundException("File is not available");
        }

        String fileName = sharedFileName(file);
        try (var inputStream = sharedStorage.openDownloadStream(objectId);
             var outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            byte[] content = outputStream.toByteArray();
            MediaType mediaType = sharedMediaType(fileName, file);
            return new StoredFile(content, fileName, mediaType);
        } catch (IOException | RuntimeException exception) {
            throw new ResourceNotFoundException("File is not available");
        }
    }

    private String sharedFileName(GridFSFile file) {
        Document metadata = file.getMetadata();
        String metadataFileName = metadata == null ? null : metadata.getString("fileName");
        return sanitizeFileName(metadataFileName == null ? file.getFilename() : metadataFileName);
    }

    private MediaType sharedMediaType(String fileName, GridFSFile file) {
        Document metadata = file.getMetadata();
        String contentType = metadata == null ? null : metadata.getString("contentType");
        if (contentType != null && !contentType.isBlank()) {
            try {
                return MediaType.parseMediaType(contentType);
            } catch (IllegalArgumentException ignored) {
                // Fall back to the file extension below.
            }
        }
        return MediaTypeFactory.getMediaType(fileName).orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    private String gridFsStoredPath(ObjectId objectId, String fileName) {
        return GRIDFS_PATH_PREFIX + objectId.toHexString() + "/" + fileName;
    }

    private Optional<ObjectId> parseGridFsId(String storedPath) {
        if (storedPath == null || !storedPath.startsWith(GRIDFS_PATH_PREFIX)) {
            return Optional.empty();
        }

        String remainder = storedPath.substring(GRIDFS_PATH_PREFIX.length());
        int slash = remainder.indexOf('/');
        String objectId = slash >= 0 ? remainder.substring(0, slash) : remainder;
        return ObjectId.isValid(objectId) ? Optional.of(new ObjectId(objectId)) : Optional.empty();
    }

    private String normalizeStoredPath(String storedPath) {
        String normalizedStoredPath = storedPath.trim().replace('\\', '/');
        while (normalizedStoredPath.startsWith("/")) {
            normalizedStoredPath = normalizedStoredPath.substring(1);
        }
        return normalizedStoredPath;
    }

    private void clearDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (Stream<Path> entries = Files.list(directory)) {
            entries.sorted(Comparator.reverseOrder()).forEach(entry -> {
                try {
                    Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                    // best-effort cleanup of the previous file
                }
            });
        }
    }

    private String sanitizeFileName(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "file";
        }
        String baseName = Paths.get(originalFilename).getFileName().toString();
        return baseName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public record FileMetadata(String fileName, long sizeBytes, LocalDateTime uploadedAt) {
    }

    public record StoredFile(byte[] content, String fileName, MediaType mediaType) {
    }
}
