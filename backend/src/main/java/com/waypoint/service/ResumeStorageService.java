package com.waypoint.service;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.mongodb.client.gridfs.model.GridFSFile;
import com.mongodb.client.gridfs.model.GridFSUploadOptions;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class ResumeStorageService {

    private static final long DEFAULT_MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final String GRIDFS_BUCKET_NAME = "resumes";
    private static final String GRIDFS_PATH_PREFIX = "gridfs/";
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "doc", "docx");

    private final Path storageRoot;
    private final List<Path> storageRoots;
    private final long maxFileSize;
    private final GridFSBucket sharedStorage;
    private final boolean localStorageAllowed;

    @Autowired
    public ResumeStorageService(
            @Value("${waypoint.resume-storage-root:uploads}") String storageRoot,
            @Value("${waypoint.resume-max-file-size:10485760}") long maxFileSize,
            MongoTemplate mongoTemplate) {
        this(
                storageRoot,
                maxFileSize,
                createSharedStorage(mongoTemplate),
                false);
    }

    /**
     * Local-only constructor retained for unit tests and explicit local
     * development tools. The application constructor above uses GridFS by
     * default so uploaded bytes are stored with the shared MongoDB database.
     */
    public ResumeStorageService(String storageRoot) {
        this(storageRoot, DEFAULT_MAX_FILE_SIZE, null, true);
    }

    ResumeStorageService(String storageRoot, GridFSBucket sharedStorage) {
        this(storageRoot, DEFAULT_MAX_FILE_SIZE, sharedStorage, false);
    }

    private ResumeStorageService(
            String configuredStorageRoot,
            long maxFileSize,
            GridFSBucket sharedStorage,
            boolean localStorageAllowed) {
        Path configuredRoot = Path.of(configuredStorageRoot).toAbsolutePath().normalize();
        this.storageRoots = candidateStorageRoots(configuredStorageRoot);
        this.storageRoot = selectWriteRoot(configuredRoot, this.storageRoots);
        this.maxFileSize = maxFileSize;
        this.sharedStorage = sharedStorage;
        this.localStorageAllowed = localStorageAllowed;
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
     * Returns whether a stored path points to a GridFS object rather than a
     * machine-local legacy file. Applications must use this form when shared
     * storage is available so another computer can resolve the same bytes.
     */
    public boolean isSharedStoragePath(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }

        return parseGridFsId(normalizeStoredPath(storedPath)).isPresent();
    }

    public boolean exists(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }

        String normalizedStoredPath = normalizeStoredPath(storedPath);
        if (normalizedStoredPath.startsWith(GRIDFS_PATH_PREFIX)) {
            return parseGridFsId(normalizedStoredPath)
                    .flatMap(this::findSharedFile)
                    .isPresent();
        }

        try {
            return resolveStoredPaths(normalizedStoredPath).stream().anyMatch(Files::isRegularFile);
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /**
     * Finds the newest resume already stored for a profile. This keeps resumes
     * uploaded by an older build visible when the profile document was not yet
     * linked to its stored path.
     */
    public Optional<String> findLatestStoredPath(String ownerId) {
        if (ownerId == null || !ownerId.matches("[A-Za-z0-9._-]+")) {
            return Optional.empty();
        }

        List<StoredCandidate> candidates = new ArrayList<>();
        if (sharedStorage != null) {
            try {
                GridFSFile file = sharedStorage.find(Filters.eq("metadata.ownerId", ownerId))
                        .sort(Sorts.descending("uploadDate"))
                        .first();
                if (file != null && ALLOWED_EXTENSIONS.contains(extensionOf(file.getFilename()))) {
                    candidates.add(new StoredCandidate(
                            gridFsStoredPath(file.getObjectId(), file.getFilename()),
                            file.getUploadDate()));
                }
            } catch (RuntimeException ignored) {
                // The normal request still reports no resume if the shared
                // bucket cannot be queried. The database request itself will
                // surface connectivity issues to the caller separately.
            }
        }

        for (Path root : storageRoots) {
            Path ownerDirectory = root.resolve("resumes").resolve(ownerId).normalize();
            if (!ownerDirectory.startsWith(root) || !Files.isDirectory(ownerDirectory)) {
                continue;
            }

            try (var files = Files.list(ownerDirectory)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> ALLOWED_EXTENSIONS.contains(extensionOf(file.getFileName().toString())))
                        .forEach(file -> {
                            try {
                                candidates.add(new StoredCandidate(
                                        root.relativize(file).toString().replace('\\', '/'),
                                        new Date(Files.getLastModifiedTime(file).toMillis())));
                            } catch (IOException ignored) {
                                // Ignore files that disappear while the directory is scanned.
                            }
                        });
            } catch (IOException ignored) {
                // A missing or inaccessible candidate root should not hide
                // resumes in another root.
            }
        }

        return candidates.stream()
                .max(Comparator.comparing(StoredCandidate::lastModified))
                .map(StoredCandidate::storedPath);
    }

    /**
     * Finds a stored resume for the same jobSeeker and exact file name. This is
     * intentionally narrower than the latest-resume lookup: it can repair a
     * stale application link only when the matching jobSeeker file is present,
     * without substituting an unrelated resume.
     */
    public Optional<String> findStoredPathByFileName(String ownerId, String fileName) {
        if (ownerId == null || !ownerId.matches("[A-Za-z0-9._-]+") || fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }

        String normalizedFileName = safeFileName(fileName);
        List<StoredCandidate> candidates = new ArrayList<>();
        if (sharedStorage != null) {
            try {
                GridFSFile file = sharedStorage.find(Filters.and(
                                Filters.eq("metadata.ownerId", ownerId),
                                Filters.eq("metadata.fileName", normalizedFileName)))
                        .sort(Sorts.descending("uploadDate"))
                        .first();
                if (file != null && ALLOWED_EXTENSIONS.contains(extensionOf(file.getFilename()))) {
                    candidates.add(new StoredCandidate(
                            gridFsStoredPath(file.getObjectId(), file.getFilename()),
                            file.getUploadDate()));
                }
            } catch (RuntimeException ignored) {
                // Continue with the local legacy fallback below.
            }
        }

        for (Path root : storageRoots) {
            Path ownerDirectory = root.resolve("resumes").resolve(ownerId).normalize();
            if (!ownerDirectory.startsWith(root) || !Files.isDirectory(ownerDirectory)) {
                continue;
            }

            try (var files = Files.list(ownerDirectory)) {
                files.filter(Files::isRegularFile)
                        .filter(file -> ALLOWED_EXTENSIONS.contains(extensionOf(file.getFileName().toString())))
                        .filter(file -> {
                            String localFileName = file.getFileName().toString();
                            return localFileName.equals(normalizedFileName)
                                    || localFileName.endsWith("-" + normalizedFileName);
                        })
                        .forEach(file -> {
                            try {
                                candidates.add(new StoredCandidate(
                                        root.relativize(file).toString().replace('\\', '/'),
                                        new Date(Files.getLastModifiedTime(file).toMillis())));
                            } catch (IOException ignored) {
                                // Ignore files that disappear while the directory is scanned.
                            }
                        });
            } catch (IOException ignored) {
                // Continue with another candidate root.
            }
        }

        return candidates.stream()
                .max(Comparator.comparing(StoredCandidate::lastModified))
                .map(StoredCandidate::storedPath);
    }

    public StoredResume store(MultipartFile file, String ownerId) {
        String originalFileName = validateUpload(file, ownerId);
        MediaType mediaType = MediaTypeFactory.getMediaType(originalFileName)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);

        if (sharedStorage != null) {
            return storeInSharedStorage(file, ownerId, originalFileName, mediaType);
        }

        if (!localStorageAllowed) {
            throw new BusinessException(
                    "Shared resume storage is not configured. Set MONGODB_URI and restart the backend.");
        }

        return storeLocally(file, ownerId, originalFileName, mediaType);
    }

    /**
     * Moves legacy local files into GridFS without deleting the originals.
     * The returned map is keyed by the old relative path so profile and
     * application snapshots can be updated safely by the migration component.
     */
    public Map<String, String> migrateLocalFilesToSharedStorage() {
        if (sharedStorage == null) {
            return Map.of();
        }

        Map<String, String> migratedPaths = new LinkedHashMap<>();
        for (Path root : storageRoots) {
            Path resumesRoot = root.resolve("resumes").normalize();
            if (!resumesRoot.startsWith(root) || !Files.isDirectory(resumesRoot)) {
                continue;
            }

            try (var ownerDirectories = Files.list(resumesRoot)) {
                for (Path ownerDirectory : ownerDirectories
                        .filter(Files::isDirectory)
                        .toList()) {
                    String ownerId = ownerDirectory.getFileName().toString();
                    if (!ownerId.matches("[A-Za-z0-9._-]+")) {
                        continue;
                    }

                    try (var files = Files.list(ownerDirectory)) {
                        for (Path file : files
                                .filter(Files::isRegularFile)
                                .filter(candidate -> ALLOWED_EXTENSIONS.contains(
                                        extensionOf(candidate.getFileName().toString())))
                                .toList()) {
                            String legacyPath = root.relativize(file)
                                    .toString()
                                    .replace('\\', '/');
                            migrateLocalFile(file, ownerId, legacyPath)
                                    .ifPresent(sharedPath -> migratedPaths.putIfAbsent(legacyPath, sharedPath));
                        }
                    } catch (IOException ignored) {
                        // Continue migrating other owner directories.
                    }
                }
            } catch (IOException ignored) {
                // Continue migrating other candidate roots.
            }
        }

        return Map.copyOf(migratedPaths);
    }

    public ResumeDocument load(String storedPath) {
        if (storedPath == null || storedPath.isBlank()) {
            throw new ResourceNotFoundException("Resume file is not available");
        }

        String normalizedStoredPath = normalizeStoredPath(storedPath);
        if (normalizedStoredPath.startsWith(GRIDFS_PATH_PREFIX)) {
            ObjectId objectId = parseGridFsId(normalizedStoredPath)
                    .orElseThrow(() -> new ResourceNotFoundException("Resume file is not available"));
            return loadFromSharedStorage(objectId);
        }

        Path file = resolveStoredPaths(normalizedStoredPath).stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Resume file is not available"));
        if (!Files.isRegularFile(file)) {
            throw new ResourceNotFoundException("Resume file is not available");
        }

        try {
            byte[] content = Files.readAllBytes(file);
            String fileName = file.getFileName().toString();
            MediaType mediaType = MediaTypeFactory.getMediaType(fileName)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);

            return new ResumeDocument(content, fileName, mediaType, content.length);
        } catch (IOException exception) {
            throw new ResourceNotFoundException("Resume file is not available");
        }
    }

    private StoredResume storeInSharedStorage(
            MultipartFile file,
            String ownerId,
            String originalFileName,
            MediaType mediaType) {
        Document metadata = new Document()
                .append("ownerId", ownerId)
                .append("fileName", originalFileName)
                .append("contentType", mediaType.toString());

        try (InputStream inputStream = file.getInputStream()) {
            ObjectId objectId = sharedStorage.uploadFromStream(
                    originalFileName,
                    inputStream,
                    new GridFSUploadOptions().metadata(metadata));

            return new StoredResume(
                    gridFsStoredPath(objectId, originalFileName),
                    originalFileName,
                    mediaType,
                    file.getSize());
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("Resume file could not be stored in shared storage");
        }
    }

    private StoredResume storeLocally(
            MultipartFile file,
            String ownerId,
            String originalFileName,
            MediaType mediaType) {
        String storedFileName = UUID.randomUUID() + "-" + originalFileName;
        Path relativePath = Path.of("resumes", ownerId, storedFileName);
        Path target = storageRoot.resolve(relativePath).normalize();
        if (!target.startsWith(storageRoot)) {
            throw new BusinessException("Resume file could not be stored");
        }

        try {
            Files.createDirectories(target.getParent());
            try (InputStream inputStream = file.getInputStream()) {
                Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
            }

            return new StoredResume(
                    relativePath.toString().replace('\\', '/'),
                    originalFileName,
                    mediaType,
                    Files.size(target));
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
                // Preserve the original storage error for the caller.
            }
            throw new BusinessException("Resume file could not be stored");
        }
    }

    private Optional<String> migrateLocalFile(Path file, String ownerId, String legacyPath) {
        try {
            GridFSFile existing = sharedStorage.find(Filters.eq("metadata.legacyPath", legacyPath)).first();
            if (existing != null) {
                return Optional.of(gridFsStoredPath(existing.getObjectId(), existing.getFilename()));
            }

            long contentLength = Files.size(file);
            if (contentLength > maxFileSize) {
                return Optional.empty();
            }

            String fileName = safeFileName(file.getFileName().toString());
            MediaType mediaType = MediaTypeFactory.getMediaType(fileName)
                    .orElse(MediaType.APPLICATION_OCTET_STREAM);
            Document metadata = new Document()
                    .append("ownerId", ownerId)
                    .append("fileName", fileName)
                    .append("contentType", mediaType.toString())
                    .append("legacyPath", legacyPath);

            ObjectId objectId;
            try (InputStream inputStream = Files.newInputStream(file)) {
                objectId = sharedStorage.uploadFromStream(
                        fileName,
                        inputStream,
                        new GridFSUploadOptions().metadata(metadata));
            }
            return Optional.of(gridFsStoredPath(objectId, fileName));
        } catch (IOException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    private ResumeDocument loadFromSharedStorage(ObjectId objectId) {
        if (sharedStorage == null) {
            throw new ResourceNotFoundException("Resume file is not available");
        }

        GridFSFile file = findSharedFile(objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Resume file is not available"));
        String fileName = sharedFileName(file);

        try (var inputStream = sharedStorage.openDownloadStream(objectId);
             var outputStream = new ByteArrayOutputStream()) {
            inputStream.transferTo(outputStream);
            byte[] content = outputStream.toByteArray();
            MediaType mediaType = sharedMediaType(fileName, file);
            return new ResumeDocument(content, fileName, mediaType, content.length);
        } catch (IOException | RuntimeException exception) {
            throw new ResourceNotFoundException("Resume file is not available");
        }
    }

    private Optional<GridFSFile> findSharedFile(ObjectId objectId) {
        if (sharedStorage == null) {
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(sharedStorage.find(Filters.eq("_id", objectId)).first());
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    private String validateUpload(MultipartFile file, String ownerId) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Please select a resume file");
        }

        if (file.getSize() > maxFileSize) {
            throw new BusinessException("Resume file must be 10 MB or smaller");
        }

        String originalFileName = safeFileName(file.getOriginalFilename());
        String extension = extensionOf(originalFileName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException("Resume must be a PDF, DOC, or DOCX file");
        }

        if (ownerId == null || !ownerId.matches("[A-Za-z0-9._-]+")) {
            throw new BusinessException("JobSeeker profile is not valid");
        }

        return originalFileName;
    }

    private List<Path> resolveStoredPaths(String storedPath) {
        String normalizedStoredPath = normalizeStoredPath(storedPath);

        final Path relativePath;
        try {
            relativePath = Path.of(normalizedStoredPath);
        } catch (InvalidPathException exception) {
            throw new ResourceNotFoundException("Resume file is not available");
        }

        if (relativePath.isAbsolute()) {
            throw new ResourceNotFoundException("Resume file is not available");
        }

        List<Path> resolvedPaths = new ArrayList<>();
        for (Path root : storageRoots) {
            Path file = root.resolve(relativePath).normalize();
            if (!file.startsWith(root)) {
                throw new ResourceNotFoundException("Resume file is not available");
            }
            resolvedPaths.add(file);
        }
        return resolvedPaths;
    }

    private List<Path> candidateStorageRoots(String configuredStorageRoot) {
        Path configuredPath = Path.of(configuredStorageRoot);
        if (configuredPath.isAbsolute()) {
            return List.of(configuredPath.toAbsolutePath().normalize());
        }

        LinkedHashSet<Path> candidates = new LinkedHashSet<>();
        Path currentDirectory = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();

        for (int level = 0; level < 6 && currentDirectory != null; level++) {
            candidates.add(currentDirectory.resolve(configuredPath).normalize());
            currentDirectory = currentDirectory.getParent();
        }

        return List.copyOf(candidates);
    }

    private Path selectWriteRoot(Path configuredRoot, List<Path> candidates) {
        return candidates.stream()
                .filter(candidate -> Files.isDirectory(candidate.resolve("resumes")))
                .findFirst()
                .orElse(configuredRoot);
    }

    private Optional<ObjectId> parseGridFsId(String storedPath) {
        if (storedPath == null || !storedPath.startsWith(GRIDFS_PATH_PREFIX)) {
            return Optional.empty();
        }

        String remainder = storedPath.substring(GRIDFS_PATH_PREFIX.length());
        int slash = remainder.indexOf('/');
        String objectId = slash >= 0 ? remainder.substring(0, slash) : remainder;
        return ObjectId.isValid(objectId)
                ? Optional.of(new ObjectId(objectId))
                : Optional.empty();
    }

    private String gridFsStoredPath(ObjectId objectId, String fileName) {
        return GRIDFS_PATH_PREFIX + objectId.toHexString() + "/" + safeFileName(fileName);
    }

    private String sharedFileName(GridFSFile file) {
        Document metadata = file.getMetadata();
        String metadataFileName = metadata == null ? null : metadata.getString("fileName");
        return safeFileName(metadataFileName == null ? file.getFilename() : metadataFileName);
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
        return MediaTypeFactory.getMediaType(fileName)
                .orElse(MediaType.APPLICATION_OCTET_STREAM);
    }

    private String normalizeStoredPath(String storedPath) {
        String normalizedStoredPath = storedPath.trim().replace('\\', '/');
        while (normalizedStoredPath.startsWith("/")) {
            normalizedStoredPath = normalizedStoredPath.substring(1);
        }
        return normalizedStoredPath;
    }

    private String safeFileName(String originalFileName) {
        String normalizedOriginalFileName = originalFileName == null ? "" : originalFileName.replace('\\', '/');
        int lastSlash = normalizedOriginalFileName.lastIndexOf('/');
        String fileName = normalizedOriginalFileName.isBlank()
                ? "resume"
                : normalizedOriginalFileName.substring(lastSlash + 1);

        String sanitized = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (sanitized.isBlank()) {
            return "resume";
        }

        return sanitized.length() > 120 ? sanitized.substring(sanitized.length() - 120) : sanitized;
    }

    private String extensionOf(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot >= 0 ? fileName.substring(lastDot + 1).toLowerCase(Locale.ROOT) : "";
    }

    public record ResumeDocument(
            byte[] content,
            String fileName,
            MediaType mediaType,
            long contentLength) {

        public Resource resource() {
            return new ByteArrayResource(content);
        }
    }

    public record StoredResume(
            String storedPath,
            String fileName,
            MediaType mediaType,
            long contentLength) {
    }

    private record StoredCandidate(String storedPath, Date lastModified) {
    }
}
