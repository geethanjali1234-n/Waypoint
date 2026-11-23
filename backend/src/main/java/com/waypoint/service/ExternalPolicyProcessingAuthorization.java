package com.waypoint.service;

import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;

import java.util.List;

/**
 * Organization-level authorization to send approved, active company-policy excerpts to an external AI provider
 * (Claude) for answer generation.
 *
 * <p>Internal read permission is not external disclosure permission: an excerpt a user may read in Waypoint is
 * not, for that reason, permitted to leave Waypoint. The deployment switch
 * {@code waypoint.myhr.chat.knowledge-grounding.allow-external-provider} only allows external processing to be
 * considered at all; this authorization decides it for a specific organization and specific retrieved content.
 * The chat service additionally never sends anything but {@code COMPANY_WIDE} content externally, whatever an
 * implementation answers.
 *
 * <p>The production implementation, {@link RecordedExternalPolicyProcessingAuthorization}, reads the auditable
 * Company Admin approval records (organization approval plus per-document-version approvals) and denies everything
 * while none exist. It never reads a value supplied by the request.
 *
 * <p>Implementations must be side-effect free and must not log policy text.
 */
@FunctionalInterface
public interface ExternalPolicyProcessingAuthorization {

    /** Permits nothing; used wherever no organization-level approval exists. */
    ExternalPolicyProcessingAuthorization DENY_ALL = new ExternalPolicyProcessingAuthorization() {
        @Override
        public boolean permits(String organizationId, List<RetrievedChunk> chunks) {
            return false;
        }

        @Override
        public boolean mayPermit(String organizationId) {
            return false;
        }

        @Override
        public String toString() {
            return "ExternalPolicyProcessingAuthorization.DENY_ALL";
        }
    };

    /**
     * Whether this organization has explicitly authorized sending exactly these retrieved, already-authorized
     * chunks (identified by knowledge document and activated ingestion) to the external provider.
     */
    boolean permits(String organizationId, List<RetrievedChunk> chunks);

    /**
     * Whether anything of this organization could be permitted; checked before retrieval so a question is not
     * embedded when no provider could ever receive the result. A true answer is not a permission.
     */
    default boolean mayPermit(String organizationId) {
        return true;
    }
}
