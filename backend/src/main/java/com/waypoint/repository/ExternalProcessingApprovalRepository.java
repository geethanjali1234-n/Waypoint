package com.waypoint.repository;

import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

/**
 * External-processing approvals, always looked up within one organization. Every organizationId argument must come
 * from the verified, server-side actor, never from request input. Records are never deleted, so no delete methods
 * are declared and inherited ones must not be used.
 */
public interface ExternalProcessingApprovalRepository extends MongoRepository<ExternalProcessingApproval, String> {

    List<ExternalProcessingApproval> findByOrganizationIdAndProviderAndStatus(
            String organizationId, ExternalAiProvider provider, ExternalProcessingApprovalStatus status);

    List<ExternalProcessingApproval> findByOrganizationIdOrderByApprovedAtDesc(String organizationId);
}
