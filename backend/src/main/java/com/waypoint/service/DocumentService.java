package com.waypoint.service;

import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.StorageProvider;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ObjectStorageException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.storage.BinaryObjectStorage;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@ConditionalOnBean(BinaryObjectStorage.class)
public class DocumentService {

    private static final int MAX_SAFE_FILE_NAME_LENGTH = 120;

    private final StoredDocumentRepository storedDocumentRepository;
    private final BinaryObjectStorage binaryObjectStorage;

    public StoredDocument store(DocumentUploadRequest request) {
        validate(request);

        String safeFileName = safeFileName(request.fileName());
        String storageKey = generateStorageKey(
                request.organizationId(), request.documentType().name(), safeFileName);
        byte[] content = request.content().clone();

        binaryObjectStorage.store(storageKey, content, request.contentType().trim());

        StoredDocument metadata = StoredDocument.builder()
                .organizationId(request.organizationId().trim())
                .ownerUserId(trimToNull(request.ownerUserId()))
                .documentType(request.documentType())
                .relatedEntityType(trimToNull(request.relatedEntityType()))
                .relatedEntityId(trimToNull(request.relatedEntityId()))
                .fileName(safeFileName)
                .storageProvider(StorageProvider.R2)
                .storageKey(storageKey)
                .contentType(request.contentType().trim())
                .fileSize((long) content.length)
                .version(request.version() == null ? 1 : request.version())
                .status(DocumentStatus.ACTIVE)
                .uploadedByUserId(request.uploadedByUserId().trim())
                .createdAt(LocalDateTime.now())
                .build();

        try {
            return storedDocumentRepository.save(metadata);
        } catch (RuntimeException metadataFailure) {
            compensateForMetadataFailure(storageKey, metadataFailure);
            throw new ObjectStorageException(
                    "Document metadata could not be saved; uploaded object was rolled back", metadataFailure);
        }
    }

    public StoredDocument getMetadata(String documentId) {
        return storedDocumentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found"));
    }

    /** Batch metadata lookup (one query) keyed by document id; unknown ids are simply absent. */
    public Map<String, StoredDocument> getMetadataByIds(Collection<String> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) return Map.of();
        Map<String, StoredDocument> found = new HashMap<>();
        storedDocumentRepository.findAllById(documentIds).forEach(document -> found.put(document.getId(), document));
        return found;
    }

    public DocumentContent getContent(String documentId) {
        return getStoredContent(documentId);
    }

    /** Returns the original uploaded bytes and rejects missing or replaced content. */
    public DocumentContent getStoredContent(String documentId) {
        StoredDocument metadata = getMetadata(documentId);
        requireR2Document(metadata);
        BinaryObjectStorage.StoredObject storedObject = binaryObjectStorage.get(metadata.getStorageKey());
        if (storedObject == null || storedObject.content() == null || storedObject.content().length == 0) {
            throw new ObjectStorageException("Stored document content is unavailable", null);
        }
        if ((metadata.getFileSize() != null && metadata.getFileSize() != storedObject.content().length)
                || isGeneratedFallbackText(storedObject.content())) {
            throw new ObjectStorageException("Stored document content does not match the uploaded document", null);
        }
        String contentType = resolveContentType(storedObject.content(), storedObject.contentType(), metadata.getFileName());
        return new DocumentContent(metadata, storedObject.content(), contentType, storedObject.contentLength());
    }

    public void delete(String documentId) {
        StoredDocument metadata = getMetadata(documentId);
        requireR2Document(metadata);

        binaryObjectStorage.delete(metadata.getStorageKey());
        storedDocumentRepository.delete(metadata);
    }

    private void validate(DocumentUploadRequest request) {
        if (request == null) {
            throw new BusinessException("Document upload request is required");
        }
        requireText(request.organizationId(), "Organization ID is required");
        if (request.documentType() == null) {
            throw new BusinessException("Document type is required");
        }
        requireText(request.fileName(), "File name is required");
        requireText(request.contentType(), "Content type is required");
        requireText(request.uploadedByUserId(), "Uploaded-by user ID is required");
        if (request.content() == null || request.content().length == 0) {
            throw new BusinessException("Document content is required");
        }
        if (request.version() != null && request.version() < 1) {
            throw new BusinessException("Document version must be at least 1");
        }
    }

    private String generateStorageKey(String organizationId, String documentType, String safeFileName) {
        return "organizations/"
                + safePathSegment(organizationId)
                + "/"
                + documentType.toLowerCase(Locale.ROOT)
                + "/"
                + UUID.randomUUID()
                + "-"
                + safeFileName;
    }

    private String safeFileName(String originalFileName) {
        String normalized = originalFileName.replace('\\', '/');
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1).trim();
        String safe = baseName.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            safe = "document";
        }
        if (safe.length() > MAX_SAFE_FILE_NAME_LENGTH) {
            safe = safe.substring(safe.length() - MAX_SAFE_FILE_NAME_LENGTH);
        }
        return safe;
    }

    private String safePathSegment(String value) {
        String safe = value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank() || safe.equals(".") || safe.equals("..")) {
            throw new BusinessException("Organization ID cannot be used in a storage key");
        }
        return safe;
    }

    private void compensateForMetadataFailure(String storageKey, RuntimeException metadataFailure) {
        try {
            binaryObjectStorage.delete(storageKey);
        } catch (RuntimeException cleanupFailure) {
            metadataFailure.addSuppressed(cleanupFailure);
        }
    }

    private void requireR2Document(StoredDocument metadata) {
        if (metadata.getStorageProvider() != StorageProvider.R2) {
            throw new BusinessException("This Document Service currently manages new R2 documents only");
        }
    }

    private void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(message);
        }
    }

    private String resolveContentType(byte[] content, String declaredContentType, String fileName) {
        String detected = detectContentType(content);
        String declared = declaredContentType == null ? "" : declaredContentType.trim();
        if (detected != null) {
            if (declared.isBlank()
                    || declared.equalsIgnoreCase("application/octet-stream")
                    || declared.toLowerCase(Locale.ROOT).startsWith("text/")
                    || !"text/plain".equals(detected)
                    || isGeneratedFallbackText(content)) {
                return detected;
            }
        }
        if (!declared.isBlank()) {
            return declared;
        }
        if (fileName != null) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".pdf")) return "application/pdf";
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".csv")) return "text/csv";
            if (lower.endsWith(".txt")) return "text/plain";
        }
        return "application/octet-stream";
    }

    private String detectContentType(byte[] content) {
        if (content == null || content.length == 0) {
            return null;
        }
        if (startsWith(content, "%PDF".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
        if (content.length >= 3
                && (content[0] & 0xFF) == 0xFF
                && (content[1] & 0xFF) == 0xD8
                && (content[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (content.length >= 8
                && (content[0] & 0xFF) == 0x89
                && content[1] == 0x50
                && content[2] == 0x4E
                && content[3] == 0x47
                && content[4] == 0x0D
                && content[5] == 0x0A
                && content[6] == 0x1A
                && content[7] == 0x0A) return "image/png";
        if (content.length >= 6
                && (startsWith(content, "GIF87a".getBytes(StandardCharsets.US_ASCII))
                || startsWith(content, "GIF89a".getBytes(StandardCharsets.US_ASCII)))) return "image/gif";
        if (content.length >= 12
                && startsWith(content, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && content[8] == 'W'
                && content[9] == 'E'
                && content[10] == 'B'
                && content[11] == 'P') return "image/webp";
        if (looksLikeText(content)) {
            return "text/plain";
        }
        return null;
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (content[i] != prefix[i]) return false;
        }
        return true;
    }

    private boolean looksLikeText(byte[] content) {
        int length = Math.min(content.length, 512);
        for (int i = 0; i < length; i++) {
            int value = content[i] & 0xFF;
            if (value == 0) return false;
            if (value < 0x09) return false;
            if (value > 0x0D && value < 0x20) return false;
        }
        return true;
    }

    private boolean isGeneratedFallbackText(byte[] content) {
        String sample = new String(content, 0, Math.min(content.length, 96), StandardCharsets.UTF_8);
        return sample.startsWith("Waypoint Certified Supporting Document")
                || sample.startsWith("Waypoint Document:");
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record DocumentContent(
            StoredDocument metadata,
            byte[] content,
            String contentType,
            long contentLength) {
    }
}
