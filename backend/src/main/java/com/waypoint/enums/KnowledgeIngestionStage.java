package com.waypoint.enums;

/**
 * Progress of one KnowledgeDocumentVersion. INDEXED means every chunk is written and valid; the
 * version serves only once it is also the entry's activeVersion.
 */
public enum KnowledgeIngestionStage {
    APPROVED,
    NEEDS_REVIEW,
    INGESTING,
    INDEXED,
    FAILED
}
