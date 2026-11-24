package com.waypoint.enums;

/** What an external-processing approval covers. */
public enum ExternalProcessingApprovalScope {
    /** The organization's approval to use the provider for the listed content access levels. */
    ORGANIZATION,
    /** One knowledge document's exact activated version (knowledge document id plus ingestion id). */
    DOCUMENT_VERSION,
    /**
     * A Company Admin's explicit exclusion of one knowledge document (every version) from the provider. While ACTIVE it
     * overrides every approval for that document; lifting it (REVOKED) is itself a recorded decision.
     */
    DOCUMENT_EXCLUSION
}
