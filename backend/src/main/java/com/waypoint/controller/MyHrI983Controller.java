package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.*;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.I983WorkflowService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Signed I-983 workflow endpoints (Immigration & Visa Support). Role and
 * request-level access are enforced in I983WorkflowService.
 */
@RestController
@RequestMapping("/api/myhr/document-requests")
@RequiredArgsConstructor
public class MyHrI983Controller {
    private final CurrentUserResolver currentUserResolver;
    private final I983WorkflowService service;

    @PostMapping(path = "/i983", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<I983WorkflowResponse> submit(HttpServletRequest request,
            @Valid @RequestPart("request") EmployeeDocumentRequestCreateRequest input,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "signatures", required = false) List<I983SignaturePlacement> signatures) {
        return new ApiResponse<>(true, "Signed I-983 submitted", service.submit(userId(request), input, file, signatures));
    }

    @GetMapping("/{requestId}/i983")
    public ApiResponse<I983WorkflowResponse> get(HttpServletRequest request, @PathVariable String requestId) {
        return new ApiResponse<>(true, "Signed I-983 retrieved", service.get(userId(request), requestId));
    }

    @PostMapping(path = "/{requestId}/i983/resubmit", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<I983WorkflowResponse> resubmit(HttpServletRequest request, @PathVariable String requestId,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "signatures", required = false) List<I983SignaturePlacement> signatures) {
        return new ApiResponse<>(true, "Signed I-983 resubmitted", service.resubmit(userId(request), requestId, file, signatures));
    }

    @GetMapping("/{requestId}/i983/documents/{documentId}")
    public ResponseEntity<byte[]> document(HttpServletRequest request, @PathVariable String requestId,
            @PathVariable String documentId, @RequestParam(defaultValue = "false") boolean download) {
        var content = service.document(userId(request), requestId, documentId, download);
        return DocumentDownloads.of(content.fileName(), content.contentType(), content.content(), download);
    }

    @PostMapping(path = "/{requestId}/i983/replace", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<I983WorkflowResponse> replace(HttpServletRequest request, @PathVariable String requestId,
            @RequestPart("file") MultipartFile file) {
        return new ApiResponse<>(true, "Replacement document uploaded", service.replace(userId(request), requestId, file));
    }

    @GetMapping("/{requestId}/i983/signature")
    public ApiResponse<I983SignatureInfoResponse> signature(HttpServletRequest request, @PathVariable String requestId) {
        return new ApiResponse<>(true, "Authorized signature", service.signatureInfo(userId(request), requestId));
    }

    @GetMapping("/{requestId}/i983/signature/image")
    public ResponseEntity<byte[]> signatureImage(HttpServletRequest request, @PathVariable String requestId) {
        var signature = service.signatureImage(userId(request), requestId);
        return ResponseEntity.ok().header("X-Content-Type-Options", "nosniff").header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType(signature.contentType())).body(signature.image());
    }

    @PostMapping("/{requestId}/i983/sign")
    public ApiResponse<I983WorkflowResponse> sign(HttpServletRequest request, @PathVariable String requestId,
            @Valid @RequestBody I983SignRequest input) {
        return new ApiResponse<>(true, "Signature applied", service.sign(userId(request), requestId, input));
    }

    @PostMapping("/{requestId}/i983/approve")
    public ApiResponse<I983WorkflowResponse> approve(HttpServletRequest request, @PathVariable String requestId) {
        return new ApiResponse<>(true, "Signed I-983 approved", service.approve(userId(request), requestId));
    }

    @PostMapping("/{requestId}/i983/return")
    public ApiResponse<I983WorkflowResponse> returnForCorrection(HttpServletRequest request, @PathVariable String requestId,
            @Valid @RequestBody DocumentRequestRejectRequest input) {
        return new ApiResponse<>(true, "Signed I-983 returned for correction",
                service.returnForCorrection(userId(request), requestId, input.reason()));
    }

    @PostMapping("/{requestId}/i983/reject")
    public ApiResponse<I983WorkflowResponse> reject(HttpServletRequest request, @PathVariable String requestId,
            @Valid @RequestBody DocumentRequestRejectRequest input) {
        return new ApiResponse<>(true, "Signed I-983 rejected", service.reject(userId(request), requestId, input.reason()));
    }

    private String userId(HttpServletRequest request) { return currentUserResolver.resolveUserId(request); }
}
