package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.DocumentFulfillmentResponse;
import com.waypoint.dto.myhr.DocumentFulfillmentEmailRequest;
import com.waypoint.dto.myhr.OfferLetterApprovalListResponse;
import com.waypoint.dto.myhr.OfferLetterApprovalApproveRequest;
import com.waypoint.dto.myhr.OfferLetterApprovalRejectRequest;
import com.waypoint.dto.myhr.OfferLetterApprovalResponse;
import com.waypoint.dto.myhr.OfferLetterApprovalSubmitRequest;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.DocumentRequestService;
import com.waypoint.service.OfferLetterApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Admin approval of documents HR delivers to employees (offer letters and every other document). HR submits and later
 * delivers an approved document; only a Company Admin can approve or reject. Roles and organization are enforced in the service.
 */
@RestController
@RequestMapping("/api/myhr")
@RequiredArgsConstructor
public class MyHrOfferLetterApprovalController {

    private final CurrentUserResolver currentUserResolver;
    private final OfferLetterApprovalService approvals;
    private final DocumentRequestService documentRequests;

    @PostMapping(path = {"/document-requests/{requestId}/approval/submit", "/document-requests/{requestId}/offer-letter/approval/submit"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<OfferLetterApprovalResponse> submit(HttpServletRequest request, @PathVariable String requestId,
            @Valid @RequestPart("details") OfferLetterApprovalSubmitRequest details,
            @RequestPart("file") MultipartFile file) throws IOException {
        return new ApiResponse<>(true, "Submitted for approval",
                approvals.submit(userId(request), requestId, details, file.getOriginalFilename(), file.getContentType(), file.getBytes()));
    }

    @GetMapping({"/document-requests/{requestId}/approval", "/document-requests/{requestId}/offer-letter/approval"})
    public ApiResponse<OfferLetterApprovalResponse> get(HttpServletRequest request, @PathVariable String requestId) {
        return new ApiResponse<>(true, "Offer letter approval retrieved", approvals.get(userId(request), requestId));
    }

    /** HR sends an approved document to the employee (for an offer letter the existing email step follows). */
    @PostMapping({"/document-requests/{requestId}/approval/deliver", "/document-requests/{requestId}/offer-letter/approval/deliver"})
    public ApiResponse<DocumentFulfillmentResponse> deliver(HttpServletRequest request, @PathVariable String requestId,
            @Valid @RequestBody(required = false) DocumentFulfillmentEmailRequest email,
            @RequestParam(required = false) String recipientEmail) {
        return new ApiResponse<>(true, "Sent to the employee",
                email == null
                        ? documentRequests.deliverApprovedOfferLetter(userId(request), requestId, recipientEmail)
                        : documentRequests.deliverApprovedDocument(userId(request), requestId, email));
    }

    @GetMapping("/offer-letter-approvals")
    public ApiResponse<OfferLetterApprovalListResponse> list(HttpServletRequest request) {
        return new ApiResponse<>(true, "Offer letter approvals retrieved", approvals.list(userId(request)));
    }

    @PostMapping("/offer-letter-approvals/{requestId}/approve")
    public ApiResponse<OfferLetterApprovalResponse> approve(HttpServletRequest request, @PathVariable String requestId,
            @Valid @RequestBody(required = false) OfferLetterApprovalApproveRequest input) {
        return new ApiResponse<>(true, "Offer letter approved", approvals.approve(userId(request), requestId,
                input == null ? null : input.comment()));
    }

    @PostMapping("/offer-letter-approvals/{requestId}/reject")
    public ApiResponse<OfferLetterApprovalResponse> reject(HttpServletRequest request, @PathVariable String requestId,
            @Valid @RequestBody OfferLetterApprovalRejectRequest input) {
        return new ApiResponse<>(true, "Offer letter rejected", approvals.reject(userId(request), requestId, input.reason()));
    }

    private String userId(HttpServletRequest request) {
        return currentUserResolver.resolveUserId(request);
    }
}
