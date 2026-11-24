package com.waypoint.enums;

/**
 * Which documents an ORGANIZATION approval for Claude covers. A record without a value (every record created before
 * this setting existed) means REVIEWED_VERSIONS, so existing approvals keep exactly their original meaning.
 */
public enum ExternalProcessingApprovalCoverage {
    /** Only document versions a Company Admin approved one by one (DOCUMENT_VERSION records). The default. */
    REVIEWED_VERSIONS,
    /**
     * Additionally, the currently published (LIVE, activated) version of every COMPANY_WIDE policy, unless the policy is
     * excluded or its Claude approval was revoked without a later explicit decision. Requires its own approval record.
     */
    ALL_PUBLISHED_COMPANY_WIDE
}
