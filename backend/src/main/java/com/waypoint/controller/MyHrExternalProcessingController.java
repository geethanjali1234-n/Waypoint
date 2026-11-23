package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.ExternalProcessingApprovalRequests;
import com.waypoint.dto.myhr.ExternalProcessingApprovalResponse;
import com.waypoint.dto.myhr.ExternalProcessingStatusResponse;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.service.ExternalProcessingApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * External AI processing approvals for the MyHR knowledge base. HR and Company Admins may view; only a Company Admin
 * may approve or revoke (enforced by the service). The organization and approver always come from the verified
 * token and database record, never from the request body.
 */
@RestController
@ConditionalOnProperty(name = "waypoint.myhr.knowledge.api.enabled", havingValue = "true")
@RequestMapping("/api/myhr/knowledge/external-processing")
@RequiredArgsConstructor
public class MyHrExternalProcessingController {

    private final MyHrActorResolver actorResolver;
    private final ExternalProcessingApprovalService approvalService;

    @GetMapping
    public ApiResponse<ExternalProcessingStatusResponse> status(HttpServletRequest request) {
        return new ApiResponse<>(true, "External AI processing approvals",
                approvalService.status(actorResolver.resolveKnowledgeManager(request)));
    }

    @PostMapping("/organization/approve")
    public ApiResponse<ExternalProcessingApprovalResponse> approveOrganization(
            HttpServletRequest request, @Valid @RequestBody ExternalProcessingApprovalRequests.OrganizationApproval body) {
        return new ApiResponse<>(true, "External AI processing approved", approvalService.approveOrganization(
                actorResolver.resolveKnowledgeManager(request), body.provider(), body.accessLevels(),
                body.approvalReference(), body.acknowledgeExternalTransmission()));
    }

    @PostMapping("/organization/revoke")
    public ApiResponse<List<ExternalProcessingApprovalResponse>> revokeOrganization(
            HttpServletRequest request, @Valid @RequestBody ExternalProcessingApprovalRequests.Revocation body) {
        return new ApiResponse<>(true, "External AI processing revoked", approvalService.revokeOrganization(
                actorResolver.resolveKnowledgeManager(request), body.provider(), body.reason()));
    }

    @PostMapping("/documents/{knowledgeDocumentId}/approve")
    public ApiResponse<ExternalProcessingApprovalResponse> approveDocument(
            HttpServletRequest request, @PathVariable String knowledgeDocumentId,
            @Valid @RequestBody ExternalProcessingApprovalRequests.DocumentApproval body) {
        return new ApiResponse<>(true, "Policy version approved for external AI processing",
                approvalService.approveDocument(actorResolver.resolveKnowledgeManager(request), body.provider(),
                        knowledgeDocumentId, body.expectedIngestionId(), body.approvalReference(),
                        body.acknowledgeExternalTransmission()));
    }

    @PostMapping("/documents/{knowledgeDocumentId}/revoke")
    public ApiResponse<List<ExternalProcessingApprovalResponse>> revokeDocument(
            HttpServletRequest request, @PathVariable String knowledgeDocumentId,
            @Valid @RequestBody ExternalProcessingApprovalRequests.Revocation body) {
        return new ApiResponse<>(true, "Policy approval revoked", approvalService.revokeDocument(
                actorResolver.resolveKnowledgeManager(request), body.provider(), knowledgeDocumentId, body.reason()));
    }
}
