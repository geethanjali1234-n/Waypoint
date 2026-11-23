package com.waypoint.dto.myhr;

/**
 * The entry after an ingest, activate or revoke request, a machine-readable outcome, and a fixed,
 * user-facing message. document is null only when the entry could not be re-read.
 */
public record KnowledgeOperationResponse(KnowledgeDocumentResponse document, String outcome, String message) {
}
