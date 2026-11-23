package com.waypoint.exception;

/**
 * A document could not be turned into text for the MyHR knowledge base.
 *
 * <p>Messages are fixed per reason and never include document content, so the exception is safe
 * to log or record against the source document. Parser exceptions are deliberately not attached
 * as causes: their messages can quote fragments of the malformed document.
 */
public class DocumentExtractionException extends RuntimeException {

    public enum Reason {
        EMPTY("Document content is empty"),
        TOO_LARGE("Document exceeds the maximum file size for text extraction"),
        UNSUPPORTED_FORMAT("Document format is not supported for text extraction"),
        FORMAT_MISMATCH("Document content does not match its file extension"),
        ENCRYPTED("Encrypted documents cannot be extracted"),
        TOO_MANY_PAGES("Document exceeds the maximum page count for text extraction"),
        TOO_MANY_ROWS("Spreadsheet exceeds the maximum row count for text extraction"),
        TOO_MANY_COLUMNS("Spreadsheet exceeds the maximum column count for text extraction"),
        ARCHIVE_LIMIT_EXCEEDED("Document archive exceeds the safe entry count or decompressed size"),
        TEXT_LIMIT_EXCEEDED("Document text exceeds the maximum length for extraction"),
        MALFORMED("Document is malformed and could not be read"),
        NO_TEXT("Document contains no extractable text");

        private final String message;

        Reason(String message) {
            this.message = message;
        }
    }

    private final Reason reason;

    public DocumentExtractionException(Reason reason) {
        super(reason.message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
