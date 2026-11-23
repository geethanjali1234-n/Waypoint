package com.waypoint.service;

import com.waypoint.service.KnowledgeRetrievalService.RetrievedChunk;

import java.util.List;

/**
 * An {@link ExternalPolicyProcessingAuthorization} that permits nothing, for callers and tests that have no approval
 * records. Production uses {@link RecordedExternalPolicyProcessingAuthorization}, which is equally deny-all until a
 * Company Admin records an organization approval and per-document approvals.
 */
public class NoRecordedExternalPolicyProcessingAuthorization implements ExternalPolicyProcessingAuthorization {

    @Override
    public boolean permits(String organizationId, List<RetrievedChunk> chunks) {
        return false;
    }

    @Override
    public boolean mayPermit(String organizationId) {
        return false;
    }
}
