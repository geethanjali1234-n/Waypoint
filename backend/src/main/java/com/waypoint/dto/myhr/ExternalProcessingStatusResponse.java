package com.waypoint.dto.myhr;

import java.util.List;

/**
 * The organization's external AI processing state. claudeSwitchEnabled and voyageIngestionEnabled are the deployment
 * switches; an approval has no effect while its switch is off. claudeApprovedForCompanyWide and
 * voyageApprovedForHrOnly report the organization-level approvals; Claude additionally needs a per-document approval.
 */
public record ExternalProcessingStatusResponse(
        boolean claudeSwitchEnabled,
        boolean voyageIngestionEnabled,
        boolean claudeApprovedForCompanyWide,
        boolean voyageApprovedForHrOnly,
        List<ExternalProcessingApprovalResponse> approvals) {

    public ExternalProcessingStatusResponse {
        approvals = approvals == null ? List.of() : List.copyOf(approvals);
    }
}
