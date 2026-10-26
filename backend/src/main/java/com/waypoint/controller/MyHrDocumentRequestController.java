package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.*;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.DocumentRequestService;
import com.waypoint.service.OfferLetterService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/myhr/document-requests")
@RequiredArgsConstructor
public class MyHrDocumentRequestController {
    private final CurrentUserResolver currentUserResolver;
    private final DocumentRequestService service;
    private final OfferLetterService offerLetterService;

    @GetMapping public ApiResponse<DocumentRequestPageResponse> list(HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status, @RequestParam(required = false) String priority,
            @RequestParam(required = false) String category, @RequestParam(required = false) String query,
            @RequestParam(required = false) String workspace) {
        return new ApiResponse<>(true, "Document requests retrieved",
                service.list(userId(request), page, size, status, priority, category, query, workspace));
    }
    @GetMapping("/requestable-employees") public ApiResponse<List<RequestableEmployeeResponse>> requestable(HttpServletRequest request) {
        return new ApiResponse<>(true, "Requestable employees retrieved", service.requestableEmployees(userId(request)));
    }
    @GetMapping("/{requestId}") public ApiResponse<DocumentRequestResponse> get(HttpServletRequest request, @PathVariable String requestId) {
        return new ApiResponse<>(true, "Document request retrieved", service.get(userId(request), requestId));
    }
    @GetMapping("/{requestId}/offer-letter") public ApiResponse<OfferLetterResponse> offerLetter(HttpServletRequest request, @PathVariable String requestId) {
        return new ApiResponse<>(true, "Offer letter retrieved", offerLetterService.get(userId(request), requestId));
    }
    @PutMapping("/{requestId}/offer-letter") public ApiResponse<OfferLetterResponse> saveOfferLetter(HttpServletRequest request, @PathVariable String requestId, @Valid @RequestBody OfferLetterInput input) {
        return new ApiResponse<>(true, "Offer letter draft saved", offerLetterService.save(userId(request), requestId, input));
    }
    @PostMapping("/{requestId}/offer-letter/generate") public ApiResponse<OfferLetterResponse> generateOfferLetter(HttpServletRequest request, @PathVariable String requestId, @Valid @RequestBody OfferLetterInput input) {
        return new ApiResponse<>(true, "Offer letter generated", offerLetterService.generate(userId(request), requestId, input));
    }
    @PostMapping("/{requestId}/offer-letter/send") public ApiResponse<OfferLetterResponse> sendOfferLetter(HttpServletRequest request, @PathVariable String requestId) {
        return new ApiResponse<>(true, "Offer letter sent", offerLetterService.send(userId(request), requestId));
    }
    @GetMapping("/{requestId}/offer-letter/email-draft") public ApiResponse<OfferLetterEmailDraftResponse> offerLetterEmailDraft(HttpServletRequest request, @PathVariable String requestId,
            @RequestParam(required = false) String designation, @RequestParam(required = false) String employeeName) {
        return new ApiResponse<>(true, "Offer letter email draft retrieved", offerLetterService.emailDraft(userId(request), requestId, designation, employeeName));
    }
    @PostMapping("/{requestId}/offer-letter/email") public ApiResponse<Void> emailOfferLetter(HttpServletRequest request, @PathVariable String requestId, @Valid @RequestBody OfferLetterEmailRequest input) {
        offerLetterService.email(userId(request), requestId, input);
        return new ApiResponse<>(true, "Offer letter emailed", null);
    }
    @PostMapping public ApiResponse<DocumentRequestResponse> create(HttpServletRequest request,
                                                                    @Valid @RequestBody DocumentRequestCreateRequest input) {
        return new ApiResponse<>(true, "Document request created", service.createManagerRequest(userId(request), input));
    }
    @PostMapping(path = "/employee", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentRequestResponse> createEmployee(HttpServletRequest request,
            @Valid @RequestPart("request") EmployeeDocumentRequestCreateRequest input,
            @RequestPart(value = "files", required = false) List<MultipartFile> files) {
        return new ApiResponse<>(true, "Document request created",
                service.createSelfServiceRequest(userId(request), input, files == null ? List.of() : files));
    }
    @PostMapping(path = "/{requestId}/fulfill", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentFulfillmentResponse> fulfill(HttpServletRequest request, @PathVariable String requestId,
                                                         @RequestPart("file") MultipartFile file,
                                                         @Valid @RequestPart(value = "email", required = false) DocumentFulfillmentEmailRequest email) throws IOException {
        return new ApiResponse<>(true, "Document request fulfilled", service.fulfill(userId(request), requestId,
                file.getOriginalFilename(), file.getContentType(), file.getBytes(), email));
    }
    @GetMapping("/{requestId}/fulfillment-email-draft") public ApiResponse<DocumentFulfillmentEmailDraftResponse> fulfillmentEmailDraft(
            HttpServletRequest request, @PathVariable String requestId, @RequestParam(required = false) String recipientEmail) {
        return new ApiResponse<>(true, "Document email draft retrieved", service.fulfillmentEmailDraft(userId(request), requestId, recipientEmail));
    }
    @PostMapping("/{requestId}/fulfillment-email") public ApiResponse<DocumentEmailDeliveryResponse> resendFulfillmentEmail(
            HttpServletRequest request, @PathVariable String requestId, @Valid @RequestBody(required = false) DocumentFulfillmentEmailRequest email) {
        return new ApiResponse<>(true, "Document email attempted", service.resendFulfillmentEmail(userId(request), requestId, email));
    }
    @PostMapping(path = "/{requestId}/signed-response", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<DocumentRequestResponse> submitSignedResponse(HttpServletRequest request, @PathVariable String requestId,
            @RequestPart(value = "signature", required = false) MultipartFile signature,
            @RequestPart(value = "signedDocument", required = false) MultipartFile signedDocument) throws IOException {
        return new ApiResponse<>(true, "Signed response submitted", service.submitSignedResponse(userId(request), requestId,
                signature == null ? null : signature.getOriginalFilename(), signature == null ? null : signature.getContentType(), signature == null ? null : signature.getBytes(),
                signedDocument == null ? null : signedDocument.getOriginalFilename(), signedDocument == null ? null : signedDocument.getContentType(), signedDocument == null ? null : signedDocument.getBytes()));
    }
    @PostMapping("/{requestId}/reject") public ApiResponse<DocumentRequestResponse> reject(HttpServletRequest request,
            @PathVariable String requestId, @Valid @RequestBody DocumentRequestRejectRequest input) {
        return new ApiResponse<>(true, "Document request rejected", service.reject(userId(request), requestId, input.reason()));
    }
    @GetMapping("/{requestId}/documents/{documentId}") public ResponseEntity<byte[]> document(HttpServletRequest request,
            @PathVariable String requestId, @PathVariable String documentId,
            @RequestParam(defaultValue = "false") boolean download) {
        var content = service.getDocumentContent(userId(request), requestId, documentId);
        return DocumentDownloads.of(content.fileName(), content.contentType(), content.content(), download);
    }
    private String userId(HttpServletRequest request) { return currentUserResolver.resolveUserId(request); }
}
