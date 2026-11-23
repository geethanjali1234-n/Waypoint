package com.waypoint.enums;

/** What an external-processing approval covers. */
public enum ExternalProcessingApprovalScope {
    /** The organization's approval to use the provider for the listed content access levels. */
    ORGANIZATION,
    /** One knowledge document's exact activated version (knowledge document id plus ingestion id). */
    DOCUMENT_VERSION
}
