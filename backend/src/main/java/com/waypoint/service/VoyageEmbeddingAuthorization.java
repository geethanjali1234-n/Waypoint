package com.waypoint.service;

import com.waypoint.entity.ExternalProcessingApproval;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.enums.ExternalAiProvider;
import com.waypoint.enums.ExternalProcessingApprovalStatus;
import com.waypoint.repository.ExternalProcessingApprovalRepository;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Whether policy text of an access level may be sent to Voyage AI to be embedded. Claude approval never implies
 * Voyage approval.
 *
 * <ul>
 *   <li>COMPANY_WIDE: governed, as before, by the deployment switch {@code waypoint.myhr.knowledge.ingestion.enabled}
 *       (described there as sending approved policy text to Voyage), so existing company-wide ingestion is unchanged.
 *   <li>HR_ONLY: denied unless the organization has an ACTIVE Voyage approval covering HR_ONLY.
 *   <li>EMPLOYEE_PRIVATE (or anything else): always denied.
 * </ul>
 * A lookup failure denies.
 */
@Component
public class VoyageEmbeddingAuthorization {

    private final ExternalProcessingApprovalRepository approvals;

    public VoyageEmbeddingAuthorization(ExternalProcessingApprovalRepository approvals) {
        this.approvals = approvals;
    }

    public boolean permitsDocumentEmbedding(String organizationId, ChunkAccessLevel accessLevel) {
        if (accessLevel == ChunkAccessLevel.COMPANY_WIDE) {
            return true;
        }
        if (accessLevel != ChunkAccessLevel.HR_ONLY || organizationId == null || organizationId.isBlank()) {
            return false;
        }
        try {
            List<ExternalProcessingApproval> active = approvals.findByOrganizationIdAndProviderAndStatus(
                    organizationId, ExternalAiProvider.VOYAGE_AI, ExternalProcessingApprovalStatus.ACTIVE);
            return active != null && ExternalProcessingApprovalService.activeOrganizationApproval(
                    active.stream().filter(record -> record != null && organizationId.equals(record.getOrganizationId()))
                            .toList(),
                    ExternalAiProvider.VOYAGE_AI, ChunkAccessLevel.HR_ONLY) != null;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }
}
