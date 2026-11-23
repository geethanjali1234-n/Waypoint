package com.waypoint.service;

import com.waypoint.entity.StoredDocument;
import com.waypoint.enums.DocumentStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.KnowledgeIngestionFailure;
import com.waypoint.enums.StorageProvider;
import com.waypoint.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reads the stored bytes of an approved knowledge base source and proves they are the bytes HR
 * approved: the same ACTIVE COMPANY_POLICY document in the caller's organization, stored in R2,
 * with the approved length and SHA-256.
 *
 * <p>Metadata is checked before any bytes are fetched, so another organization's document, or one
 * that is no longer eligible, is never read. Bytes come only from
 * {@link DocumentService#getStoredContent}, never getContent (which can synthesize placeholder
 * documents), and the metadata that call re-reads must still describe the upload checked first.
 *
 * <p>Failures carry a fixed message and a reason that maps onto {@link KnowledgeIngestionFailure};
 * storage and database exceptions are never attached as causes because their messages can name
 * storage keys, which contain file names. Logs carry reasons and exception class names only.
 */
@Component
@RequiredArgsConstructor
public class KnowledgeSourceDocumentReader {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Logger log = LoggerFactory.getLogger(KnowledgeSourceDocumentReader.class);

    private final DocumentService documentService;

    /**
     * Returns the verified bytes or throws {@link SourceDocumentException}.
     *
     * @param organizationId trusted server-side organization: a {@code VerifiedMyHrActor}'s, or that of
     *                       a knowledge document loaded for one; never a request value
     * @param expectedSha256 the approved version's lowercase hex SHA-256
     */
    public VerifiedSourceContent read(String organizationId, String storedDocumentId, String expectedSha256) {
        if (isBlank(organizationId) || isBlank(storedDocumentId)) {
            throw new IllegalArgumentException("An organization and a stored document are required");
        }
        if (expectedSha256 == null || !SHA256_HEX.matcher(expectedSha256).matches()) {
            throw new IllegalArgumentException("The approved SHA-256 must be 64 lowercase hex characters");
        }

        StoredDocument checked;
        try {
            checked = documentService.getMetadata(storedDocumentId);
        } catch (ResourceNotFoundException missing) {
            throw fail(Reason.NOT_FOUND);
        } catch (RuntimeException unavailable) {
            throw fail(Reason.STORAGE_UNAVAILABLE, unavailable);
        }
        requireEligible(checked, storedDocumentId, organizationId);

        DocumentService.DocumentContent stored;
        try {
            stored = documentService.getStoredContent(storedDocumentId);
        } catch (ResourceNotFoundException missing) {
            throw fail(Reason.NOT_FOUND);
        } catch (RuntimeException unavailable) {
            throw fail(Reason.STORAGE_UNAVAILABLE, unavailable);
        }
        if (stored == null || stored.content() == null || stored.content().length == 0) {
            throw fail(Reason.STORAGE_UNAVAILABLE);
        }

        // getStoredContent loads the metadata again; it must still be the upload checked above.
        StoredDocument current = stored.metadata();
        requireEligible(current, storedDocumentId, organizationId);
        if (!Objects.equals(checked.getStorageKey(), current.getStorageKey())
                || !Objects.equals(checked.getFileName(), current.getFileName())
                || !Objects.equals(checked.getFileSize(), current.getFileSize())) {
            throw fail(Reason.CONTENT_CHANGED);
        }

        byte[] content = stored.content();
        if (content.length != current.getFileSize()) {
            throw fail(Reason.CONTENT_CHANGED);
        }
        String actualSha256 = sha256(content);
        if (!MessageDigest.isEqual(actualSha256.getBytes(StandardCharsets.US_ASCII),
                expectedSha256.getBytes(StandardCharsets.US_ASCII))) {
            throw fail(Reason.CONTENT_CHANGED);
        }
        return new VerifiedSourceContent(current, content, actualSha256);
    }

    /** Another organization's document is reported as not found, so its existence is not revealed. */
    private static void requireEligible(StoredDocument metadata, String storedDocumentId, String organizationId) {
        if (metadata == null
                || !storedDocumentId.equals(metadata.getId())
                || !organizationId.equals(metadata.getOrganizationId())) {
            throw fail(Reason.NOT_FOUND);
        }
        if (metadata.getDocumentType() != DocumentType.COMPANY_POLICY || metadata.getStatus() != DocumentStatus.ACTIVE) {
            throw fail(Reason.INELIGIBLE);
        }
        if (metadata.getStorageProvider() != StorageProvider.R2
                || isBlank(metadata.getStorageKey())
                || isBlank(metadata.getFileName())
                || metadata.getFileSize() == null
                || metadata.getFileSize() <= 0) {
            throw fail(Reason.METADATA_INCOMPLETE);
        }
    }

    private static SourceDocumentException fail(Reason reason) {
        log.info("Knowledge source document rejected ({})", reason);
        return new SourceDocumentException(reason);
    }

    private static SourceDocumentException fail(Reason reason, RuntimeException failure) {
        // Class name only: storage and driver messages can name storage keys or connection details.
        log.warn("Knowledge source document could not be read ({}, {})", reason, failure.getClass().getSimpleName());
        return new SourceDocumentException(reason);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available", unavailable);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** The approved bytes and the metadata that describes them. Holds document content: never log it. */
    public record VerifiedSourceContent(StoredDocument metadata, byte[] content, String contentSha256) {

        /** Identifies the document and size only; no file name, storage key, hash or bytes. */
        @Override
        public String toString() {
            return "VerifiedSourceContent[storedDocumentId=" + metadata.getId() + ", bytes=" + content.length + "]";
        }
    }

    public enum Reason {
        NOT_FOUND(KnowledgeIngestionFailure.SOURCE_DOCUMENT_INELIGIBLE,
                "The approved source document was not found"),
        INELIGIBLE(KnowledgeIngestionFailure.SOURCE_DOCUMENT_INELIGIBLE,
                "The approved source document is no longer an active company policy"),
        METADATA_INCOMPLETE(KnowledgeIngestionFailure.SOURCE_DOCUMENT_INELIGIBLE,
                "The approved source document's metadata is incomplete"),
        STORAGE_UNAVAILABLE(KnowledgeIngestionFailure.STORAGE_UNAVAILABLE,
                "The approved source document's content could not be read"),
        CONTENT_CHANGED(KnowledgeIngestionFailure.CONTENT_CHANGED,
                "The source document's content no longer matches the approved version");

        private final KnowledgeIngestionFailure failure;
        private final String message;

        Reason(KnowledgeIngestionFailure failure, String message) {
            this.failure = failure;
            this.message = message;
        }

        public KnowledgeIngestionFailure failure() {
            return failure;
        }
    }

    /** Fixed message per reason and no cause, so it is safe to log or record against a knowledge version. */
    public static class SourceDocumentException extends RuntimeException {

        private final Reason reason;

        public SourceDocumentException(Reason reason) {
            super(reason.message);
            this.reason = reason;
        }

        public Reason getReason() {
            return reason;
        }

        public KnowledgeIngestionFailure getFailure() {
            return reason.failure;
        }
    }
}
