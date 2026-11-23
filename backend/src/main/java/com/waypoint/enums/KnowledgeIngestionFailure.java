package com.waypoint.enums;

/** Why a knowledge version failed to ingest. Codes only: no failure detail includes document content. */
public enum KnowledgeIngestionFailure {
    SOURCE_DOCUMENT_INELIGIBLE,
    STORAGE_UNAVAILABLE,
    CONTENT_CHANGED,
    EXTRACTION_FAILED,
    TOO_MANY_CHUNKS,
    EMBEDDING_CONFIGURATION,
    EMBEDDING_REQUEST_REJECTED,
    EMBEDDING_UNAVAILABLE,
    INVALID_EMBEDDING,
    CHUNK_WRITE_FAILED,
    ACTIVATION_CONFLICT,
    /** A reviewer rejected the pending content; replacement is required before ingestion. */
    CONTENT_REVIEW_REJECTED,
    /** The organization has not approved sending this content's access level to the embedding provider. */
    EXTERNAL_PROCESSING_NOT_APPROVED
}
