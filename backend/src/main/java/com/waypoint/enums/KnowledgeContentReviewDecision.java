package com.waypoint.enums;

/**
 * A future, explicitly bound human decision for findings reported during local extraction.
 * REPLACE_DOCUMENT and DO_NOT_APPROVE are terminal review outcomes and can never authorize ingestion.
 */
public enum KnowledgeContentReviewDecision {
    INCLUDE_FLAGGED_TEXT,
    EXCLUDE_FLAGGED_TEXT,
    REPLACE_DOCUMENT,
    DO_NOT_APPROVE
}
