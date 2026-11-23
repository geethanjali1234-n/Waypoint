package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.KnowledgeDocumentResponse;
import com.waypoint.dto.myhr.KnowledgeContentPreviewResponse;
import com.waypoint.dto.myhr.KnowledgeContentReviewRequest;
import com.waypoint.dto.myhr.KnowledgeManagementSettingsResponse;
import com.waypoint.dto.myhr.KnowledgeOperationResponse;
import com.waypoint.dto.myhr.KnowledgeVersionConfirmationRequest;
import com.waypoint.dto.myhr.KnowledgeVersionRestoreRequest;
import com.waypoint.enums.ChunkAccessLevel;
import com.waypoint.security.MyHrActorResolver;
import com.waypoint.service.KnowledgeManagementService;
import com.waypoint.service.KnowledgeContentReviewService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * HR and company-admin management of the MyHR knowledge base. Every request resolves an active
 * HR or COMPANY_ADMIN actor from the signed token and the user's current database record; the
 * organization always comes from that record, never from the request.
 */
@RestController
@ConditionalOnProperty(name = "waypoint.myhr.knowledge.api.enabled", havingValue = "true")
@RequestMapping("/api/myhr/knowledge")
@RequiredArgsConstructor
public class MyHrKnowledgeController {

    private final MyHrActorResolver actorResolver;
    private final KnowledgeManagementService knowledgeManagementService;
    private final KnowledgeContentReviewService knowledgeContentReviewService;

    @GetMapping("/settings")
    public ApiResponse<KnowledgeManagementSettingsResponse> settings(HttpServletRequest request) {
        return new ApiResponse<>(true, "Knowledge base settings",
                knowledgeManagementService.settings(actorResolver.resolveKnowledgeManager(request)));
    }

    @GetMapping("/documents")
    public ApiResponse<List<KnowledgeDocumentResponse>> list(HttpServletRequest request) {
        return new ApiResponse<>(true, "Knowledge documents",
                knowledgeManagementService.list(actorResolver.resolveKnowledgeManager(request)));
    }

    @GetMapping("/documents/{knowledgeDocumentId}/pending/extracted-text")
    public ApiResponse<KnowledgeContentPreviewResponse> previewPendingExtractedText(
            HttpServletRequest request, @PathVariable String knowledgeDocumentId) {
        return new ApiResponse<>(true, "Pending extracted text", knowledgeContentReviewService.preview(
                actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId));
    }

    @PostMapping("/documents/{knowledgeDocumentId}/content-review")
    public ApiResponse<Void> reviewPendingContent(
            HttpServletRequest request, @PathVariable String knowledgeDocumentId,
            @Valid @RequestBody KnowledgeContentReviewRequest body) {
        knowledgeContentReviewService.review(actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId, body);
        return new ApiResponse<>(true, "Content review recorded", null);
    }

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<KnowledgeDocumentResponse>> addPolicy(
            HttpServletRequest request,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "accessLevel", required = false) ChunkAccessLevel accessLevel) {
        KnowledgeDocumentResponse added = knowledgeManagementService.addPolicy(
                actorResolver.resolveKnowledgeManager(request), file, title, accessLevel);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Policy approved for the knowledge base", added));
    }

    @PostMapping(value = "/documents/{knowledgeDocumentId}/replacement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeDocumentResponse> replacePolicy(
            HttpServletRequest request,
            @PathVariable String knowledgeDocumentId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "accessLevel", required = false) ChunkAccessLevel accessLevel) {
        return new ApiResponse<>(true, "Replacement approved", knowledgeManagementService.replacePolicy(
                actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId, file, accessLevel));
    }

    @PostMapping("/documents/{knowledgeDocumentId}/restore-version")
    public ApiResponse<KnowledgeDocumentResponse> restoreHistoricalVersion(
            HttpServletRequest request,
            @PathVariable String knowledgeDocumentId,
            @Valid @RequestBody KnowledgeVersionRestoreRequest body) {
        return new ApiResponse<>(true, "Historical version approved for restoration", knowledgeManagementService
                .restoreHistoricalVersion(actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId,
                        body.expectedHistoricalSourceDocumentId()));
    }

    @PostMapping("/documents/{knowledgeDocumentId}/accept-incomplete-text")
    public ApiResponse<KnowledgeDocumentResponse> acceptIncompleteText(
            HttpServletRequest request,
            @PathVariable String knowledgeDocumentId,
            @Valid @RequestBody KnowledgeVersionConfirmationRequest body) {
        return new ApiResponse<>(true, "Incomplete text accepted", knowledgeManagementService.acceptIncompleteText(
                actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId, body.expectedVersionId()));
    }

    @PostMapping("/documents/{knowledgeDocumentId}/ingest")
    public ApiResponse<KnowledgeOperationResponse> ingest(
            HttpServletRequest request, @PathVariable String knowledgeDocumentId) {
        KnowledgeOperationResponse result = knowledgeManagementService.ingest(
                actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId);
        return new ApiResponse<>(true, result.message(), result);
    }

    @PostMapping("/documents/{knowledgeDocumentId}/activate")
    public ApiResponse<KnowledgeOperationResponse> activate(
            HttpServletRequest request,
            @PathVariable String knowledgeDocumentId,
            @Valid @RequestBody KnowledgeVersionConfirmationRequest body) {
        KnowledgeOperationResponse result = knowledgeManagementService.activate(
                actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId, body.expectedVersionId());
        return new ApiResponse<>(true, result.message(), result);
    }

    @PostMapping("/documents/{knowledgeDocumentId}/revoke")
    public ApiResponse<KnowledgeOperationResponse> revoke(
            HttpServletRequest request, @PathVariable String knowledgeDocumentId) {
        KnowledgeOperationResponse result = knowledgeManagementService.revoke(
                actorResolver.resolveKnowledgeManager(request), knowledgeDocumentId);
        return new ApiResponse<>(true, result.message(), result);
    }
}
