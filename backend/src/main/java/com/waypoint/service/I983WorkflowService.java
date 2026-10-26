package com.waypoint.service;

import com.lowagie.text.Image;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.waypoint.dto.myhr.*;
import com.waypoint.entity.*;
import com.waypoint.enums.*;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.*;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Signed I-983 workflow (Immigration & Visa Support):
 *
 * <pre>
 * Employee uploads I-983 -> SUBMITTED ("Submitted - Awaiting HR/Admin Review")
 * HR/Admin: view / download original / upload-replace / apply the organization's
 *           active Authorized Signature at a chosen PDF position -> signed PDF
 * HR/Admin: approve (requires a signed copy of the current version)  -> APPROVED
 *           return for correction (employee may replace + resubmit)  -> RETURNED_FOR_CORRECTION
 *           reject                                                   -> REJECTED
 * </pre>
 *
 * Every file is stored as its own StoredDocument and listed in
 * DocumentRequest.documentVersions - the employee's original is never
 * overwritten or deleted, and the signed PDF is a separate version. Each
 * step is recorded in the request's own history and in the audit log.
 *
 * Reviewers are the request's assigned HR (the employee's primary reporting
 * manager) and every Company Admin, matching the document request RBAC used
 * everywhere else (DocumentRequestService).
 */
@Service
@RequiredArgsConstructor
public class I983WorkflowService {
    private static final Logger log = LoggerFactory.getLogger(I983WorkflowService.class);
    private static final Set<UserRole> ALL_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.EMPLOYEE);
    private static final Set<UserRole> REQUESTER_ROLES = Set.of(UserRole.HR, UserRole.EMPLOYEE);
    private static final Set<UserRole> REVIEWER_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR);
    private static final String RELATED_ENTITY_TYPE = "DOCUMENT_REQUEST";
    private static final String PDF = "application/pdf";
    private static final long MAX_PDF_BYTES = 10L * 1024 * 1024;
    public static final String REQUEST_TITLE = "Signed I-983";
    static final int MAX_SIGNATURES = 20;
    public static final String NO_EMPLOYEE_SIGNATURE_MESSAGE =
            "Upload your signature in My HR Profile before signing this document.";
    public static final String EMPLOYEE_SIGNATURE_SOURCE = "Employee Profile signature";
    public static final String NO_SIGNATURE_MESSAGE ="No authorized organization signature is configured. "
            + "Please configure a signature in Organization Settings before signing this document.";

    private final DocumentRequestRepository requestRepository;
    private final ReportingAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final ObjectProvider<DocumentService> documentServiceProvider;
    private final DocumentRequestService documentRequestService;
    private final AuthorizedSignatureService authorizedSignatureService;
    private final AuditService auditService;
    private final NotificationOrchestrator notificationOrchestrator;
    private final FileStorageService fileStorageService;
    private final MyHrDocumentFulfillmentEmailService fulfillmentEmailService;

    // ---------------------------------------------------------------- employee

    /**
     * Employee creates the request and submits their I-983 in one step,
     * optionally signing it with their own Profile signature in every place
     * they chose (employeeSignatures - empty or null means not signed).
     */
    public I983WorkflowResponse submit(String actorUserId, EmployeeDocumentRequestCreateRequest input, MultipartFile file,
                                       List<I983SignaturePlacement> employeeSignatures) {
        var actor = authenticatedUserService.require(actorUserId, REQUESTER_ROLES, false);
        if (!"I983".equals(input.documentType() == null ? null : input.documentType().trim()))
            throw new BusinessException("Only Signed I-983 requests can be submitted here");
        byte[] content = readPdf(file);
        // Resolve everything that can fail before the request is created.
        List<I983SignaturePlacement> placements = optionalPlacements(employeeSignatures);
        byte[] signatureImage = placements.isEmpty() ? null : employeeProfileSignature(actor);
        byte[] employeeSignedPdf = signatureImage == null ? null : stampSignature(content, signatureImage, placements);
        User manager = requireActiveReviewerManager(actor);
        LocalDateTime now = LocalDateTime.now();
        DocumentRequest request = requestRepository.save(DocumentRequest.builder()
                .organizationId(actor.organizationId()).requestedByUserId(actor.userId())
                .requestedForUserId(manager.getId()).documentType(RequestedDocumentType.I983).requestTitle(REQUEST_TITLE)
                .requestDescription(input.reason().trim()).category("IMMIGRATION_RELATED").priority("NORMAL")
                .requestDirection("TO_MANAGER").requiredBy(input.requiredBy()).status(DocumentRequestStatus.SUBMITTED)
                .documentVersions(new ArrayList<>()).history(new ArrayList<>())
                .submittedAt(now).requestedAt(now).createdAt(now).updatedAt(now).build());
        List<String> storedIds = new ArrayList<>();
        StoredDocument stored;
        StoredDocument employeeSigned = null;
        try {
            stored = store(actor, request, file.getOriginalFilename(), content, 1);
            storedIds.add(stored.getId());
            if (employeeSignedPdf != null) {
                employeeSigned = storeBytes(actor, request, signedName(stored.getFileName(), "employee-signed"), employeeSignedPdf, 2);
                storedIds.add(employeeSigned.getId());
            }
        } catch (RuntimeException failure) {
            DocumentService service = documentServiceProvider.getIfAvailable();
            if (service != null) for (String id : storedIds)
                try { service.delete(id); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            try { requestRepository.deleteById(request.getId()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        addVersion(request, actor, stored, DocumentRequestVersion.ORIGINAL, now);
        request.setOriginalDocumentId(stored.getId());
        request.setCurrentDocumentId(stored.getId());
        addHistory(request, actor, "SUBMITTED", null, DocumentRequestStatus.SUBMITTED, stored.getId(),
                "Submitted " + stored.getFileName(), now);
        if (employeeSigned != null) recordEmployeeSignature(request, actor, stored, employeeSigned, placements, now);
        DocumentRequest saved = save(request, storedIds);
        audit(actor, AuditActionType.I983_SUBMITTED, saved, "Signed I-983 submitted for HR/Admin review",
                null, auditValues(saved, "fileName", stored.getFileName(), "uploadedAt", now.toString()));
        notifySafely(() -> notifyReviewers(actor, saved, "New Signed I-983 for review",
                label(actor) + " submitted a Signed I-983 for review." ));
        return detail(actor, saved);
    }

    /**
     * After a return for correction the employee may upload a corrected I-983
     * (or keep the last one), optionally sign it, and resubmit. Signing
     * without a new file signs the unsigned original they submitted last.
     */
    public I983WorkflowResponse resubmit(String actorUserId, String requestId, MultipartFile file,
                                         List<I983SignaturePlacement> employeeSignatures) {
        var actor = authenticatedUserService.require(actorUserId, REQUESTER_ROLES, false);
        DocumentRequest request = requireI983(actor, requestId);
        if (!actor.userId().equals(request.getRequestedByUserId()))
            throw new ForbiddenException("Only the employee who submitted this I-983 can resubmit it");
        if (request.getStatus() != DocumentRequestStatus.RETURNED_FOR_CORRECTION)
            throw new BusinessException("This I-983 can only be changed after HR/Admin returns it for correction");
        boolean newFile = file != null && !file.isEmpty();
        byte[] content = newFile ? readPdf(file) : null;
        List<I983SignaturePlacement> placements = optionalPlacements(employeeSignatures);
        byte[] signatureImage = placements.isEmpty() ? null : employeeProfileSignature(actor);
        if (signatureImage != null && content == null)
            content = documents().getContent(request.getOriginalDocumentId()).content();
        byte[] employeeSignedPdf = signatureImage == null ? null : stampSignature(content, signatureImage, placements);
        LocalDateTime now = LocalDateTime.now();
        List<String> newIds = new ArrayList<>();
        String detail = "Resubmitted without changing the document";
        StoredDocument original = null;
        if (newFile) {
            original = store(actor, request, file.getOriginalFilename(), content, nextVersion(request));
            newIds.add(original.getId());
            addVersion(request, actor, original, DocumentRequestVersion.ORIGINAL, now);
            request.setOriginalDocumentId(original.getId());
            request.setCurrentDocumentId(original.getId());
            // A signature on the previous file no longer applies to the corrected one.
            request.setOrganizationSignedDocumentId(null);
            detail = "Resubmitted with corrected document " + original.getFileName();
        }
        if (employeeSignedPdf != null) {
            StoredDocument base = original != null ? original : documents().getMetadata(request.getOriginalDocumentId());
            StoredDocument signed = storeBytes(actor, request, signedName(base.getFileName(), "employee-signed"),
                    employeeSignedPdf, nextVersion(request));
            newIds.add(signed.getId());
            recordEmployeeSignature(request, actor, base, signed, placements, now);
            request.setOrganizationSignedDocumentId(null);
            detail += " and signed it";
        }
        request.setStatus(DocumentRequestStatus.SUBMITTED);
        request.setReturnReason(null);
        request.setSubmittedAt(now);
        addHistory(request, actor, "RESUBMITTED", DocumentRequestStatus.RETURNED_FOR_CORRECTION,
                DocumentRequestStatus.SUBMITTED, newIds.isEmpty() ? null : newIds.getFirst(), detail, now);
        DocumentRequest saved = save(request, newIds);
        audit(actor, AuditActionType.I983_RESUBMITTED, saved, detail,
                Map.of("status", DocumentRequestStatus.RETURNED_FOR_CORRECTION.name()), auditValues(saved));
        notifySafely(() -> notifyReviewers(actor, saved, "Signed I-983 resubmitted",
                label(actor) + " resubmitted their Signed I-983 for review."));
        return detail(actor, saved);
    }

    /** The employee's own My HR Profile signature; signing is refused without one. */
    private byte[] employeeProfileSignature(AuthenticatedMyHrUser actor) {
        String path = fileStorageService.findStoredPath(EmployeeManagementService.USER_SIGNATURE_SUBDIRECTORY, actor.userId())
                .orElseThrow(() -> new BusinessException(NO_EMPLOYEE_SIGNATURE_MESSAGE));
        try {
            return fileStorageService.load(path).content();
        } catch (RuntimeException failure) {
            log.warn("Profile signature of user {} could not be loaded", actor.userId(), failure);
            throw new BusinessException(NO_EMPLOYEE_SIGNATURE_MESSAGE);
        }
    }

    private void recordEmployeeSignature(DocumentRequest request, AuthenticatedMyHrUser actor, StoredDocument base,
                                         StoredDocument signed, List<I983SignaturePlacement> placements, LocalDateTime now) {
        DocumentRequestVersion version = addVersion(request, actor, signed, DocumentRequestVersion.EMPLOYEE_SIGNED, now);
        version.setBasedOnDocumentId(base.getId());
        version.setSignatureSource(EMPLOYEE_SIGNATURE_SOURCE);
        version.setSignatoryName(label(actor));
        version.setSignaturePages(pages(placements));
        request.setCurrentDocumentId(signed.getId());
        addHistory(request, actor, "EMPLOYEE_SIGNED", request.getStatus(), request.getStatus(), signed.getId(),
                "Signed " + describePlacements(placements) + " with their Profile signature", now);
    }

    private static String signedName(String fileName, String suffix) {
        String base = Optional.ofNullable(fileName).orElse("I-983.pdf").replaceAll("(?i)\\.pdf$", "");
        return base + "-" + suffix + ".pdf";
    }

    /** Employee signing is optional: no placements means the document is submitted unsigned. */
    private static List<I983SignaturePlacement> optionalPlacements(List<I983SignaturePlacement> placements) {
        return placements == null || placements.isEmpty() ? List.of() : requirePlacements(placements);
    }

    /** One or more signature positions, each fully inside its page. */
    static List<I983SignaturePlacement> requirePlacements(List<I983SignaturePlacement> placements) {
        if (placements == null || placements.isEmpty()) throw new BusinessException("Place at least one signature on the document");
        if (placements.size() > MAX_SIGNATURES) throw new BusinessException("A document can have at most " + MAX_SIGNATURES + " signatures");
        for (I983SignaturePlacement placement : placements) {
            if (placement == null || placement.page() < 1
                    || placement.x() < 0 || placement.y() < 0 || placement.width() < 0.01 || placement.height() < 0.005
                    || placement.x() + placement.width() > 1.0001 || placement.y() + placement.height() > 1.0001)
                throw new BusinessException("Each signature must be placed inside the page");
        }
        return List.copyOf(placements);
    }

    private static List<Integer> pages(List<I983SignaturePlacement> placements) {
        return placements.stream().map(I983SignaturePlacement::page).distinct().sorted().toList();
    }

    /** "on page 2", or "in 3 places on pages 1, 4" for the history. */
    private static String describePlacements(List<I983SignaturePlacement> placements) {
        List<Integer> pages = pages(placements);
        String where = (pages.size() == 1 ? "on page " : "on pages ")
                + pages.stream().map(String::valueOf).collect(Collectors.joining(", "));
        return placements.size() == 1 ? where : "in " + placements.size() + " places " + where;
    }

    // ---------------------------------------------------------------- shared

    public I983WorkflowResponse get(String actorUserId, String requestId) {
        var actor = authenticatedUserService.require(actorUserId, ALL_ROLES, false);
        return detail(actor, requireI983(actor, requestId));
    }

    /**
     * Serves one version of the I-983 (through DocumentRequestService, which
     * enforces who may see which version). A reviewer opening a document is
     * recorded as having reviewed it.
     */
    public DocumentRequestService.RequestDocumentContent document(String actorUserId, String requestId, String documentId, boolean download) {
        var actor = authenticatedUserService.require(actorUserId, ALL_ROLES, false);
        DocumentRequest request = requireI983(actor, requestId);
        DocumentRequestService.RequestDocumentContent content = documentRequestService.getDocumentContent(actorUserId, requestId, documentId);
        if (canReview(actor, request)) {
            DocumentRequestVersion version = findVersion(request, documentId).orElse(null);
            String what = (download ? "Downloaded " : "Viewed ") + describe(version, content.fileName());
            audit(actor, AuditActionType.I983_DOCUMENT_ACCESSED, request, what, null, auditValues(request, "documentId", documentId));
            boolean firstAccessByActor = Optional.ofNullable(request.getHistory()).orElse(List.of()).stream()
                    .noneMatch(entry -> "REVIEWED".equals(entry.getAction()) && actor.userId().equals(entry.getActorUserId())
                            && documentId.equals(entry.getDocumentId()));
            if (firstAccessByActor) {
                addHistory(request, actor, "REVIEWED", request.getStatus(), request.getStatus(), documentId, what, LocalDateTime.now());
                requestRepository.save(request);
            }
        }
        return content;
    }

    // ---------------------------------------------------------------- HR / Admin

    /** HR/Admin uploads a replacement before signing; the previous versions all stay in the history. */
    public I983WorkflowResponse replace(String actorUserId, String requestId, MultipartFile file) {
        var actor = authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        DocumentRequest request = requireReviewable(actor, requestId);
        byte[] content = readPdf(file);
        LocalDateTime now = LocalDateTime.now();
        String previous = request.getCurrentDocumentId();
        StoredDocument stored = store(actor, request, file.getOriginalFilename(), content, nextVersion(request));
        addVersion(request, actor, stored, DocumentRequestVersion.REPLACEMENT, now);
        request.setCurrentDocumentId(stored.getId());
        request.setOrganizationSignedDocumentId(null);
        addHistory(request, actor, "REPLACED", request.getStatus(), request.getStatus(), stored.getId(),
                "Uploaded replacement " + stored.getFileName() + " (version " + versionNumber(request, stored.getId()) + ")", now);
        DocumentRequest saved = save(request, List.of(stored.getId()));
        audit(actor, AuditActionType.I983_DOCUMENT_REPLACED, saved, "Signed I-983 replacement uploaded",
                Map.of("currentDocumentId", previous == null ? "" : previous),
                auditValues(saved, "fileName", stored.getFileName(), "version", versionNumber(saved, stored.getId())));
        return detail(actor, saved);
    }

    public I983SignatureInfoResponse signatureInfo(String actorUserId, String requestId) {
        var actor = authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        DocumentRequest request = requireI983(actor, requestId);
        requireReviewer(actor, request);
        return authorizedSignatureService.findActive(actor.organizationId())
                .map(signature -> new I983SignatureInfoResponse(true, null, signature.signatoryName(),
                        signature.signatoryTitle(), signature.effectiveDate(), signature.source()))
                .orElseGet(() -> new I983SignatureInfoResponse(false, NO_SIGNATURE_MESSAGE, null, null, null, null));
    }

    /** The active signature image, so the reviewer can see and position exactly what will be applied. */
    public AuthorizedSignatureService.ActiveSignature signatureImage(String actorUserId, String requestId) {
        var actor = authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        DocumentRequest request = requireI983(actor, requestId);
        requireReviewer(actor, request);
        return authorizedSignatureService.findActive(actor.organizationId())
                .orElseThrow(() -> new BusinessException(NO_SIGNATURE_MESSAGE));
    }

    /**
     * Permanently applies the organization's active Authorized Signature at
     * the chosen position of the current version and stores the result as a
     * new SIGNED version. The signature always comes from Organization
     * Settings on the server - never from the client.
     */
    public I983WorkflowResponse sign(String actorUserId, String requestId, I983SignRequest input) {
        var actor = authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        DocumentRequest request = requireReviewable(actor, requestId);
        if (!input.documentId().equals(request.getCurrentDocumentId()))
            throw new BusinessException("The document was replaced after you opened it. Reload the request and place the signature again.");
        List<I983SignaturePlacement> placements = requirePlacements(input.placements());
        AuthorizedSignatureService.ActiveSignature signature = authorizedSignatureService.findActive(actor.organizationId())
                .orElseThrow(() -> new BusinessException(NO_SIGNATURE_MESSAGE));
        DocumentService.DocumentContent current = documents().getContent(request.getCurrentDocumentId());
        byte[] signedPdf = stampSignature(current.content(), signature.image(), placements);
        LocalDateTime now = LocalDateTime.now();
        StoredDocument stored = storeBytes(actor, request, signedName(current.metadata().getFileName(), "signed"), signedPdf, nextVersion(request));
        DocumentRequestVersion version = addVersion(request, actor, stored, DocumentRequestVersion.SIGNED, now);
        version.setBasedOnDocumentId(request.getCurrentDocumentId());
        version.setSignatureSource(signature.source());
        version.setSignatoryName(signature.signatoryName());
        version.setSignatoryTitle(signature.signatoryTitle());
        version.setSignatureEffectiveDate(signature.effectiveDate());
        version.setSignaturePages(pages(placements));
        request.setOrganizationSignedDocumentId(stored.getId());
        addHistory(request, actor, "SIGNATURE_APPLIED", request.getStatus(), request.getStatus(), stored.getId(),
                "Applied the authorized signature of " + Optional.ofNullable(signature.signatoryName()).orElse("the organization")
                        + " " + describePlacements(placements) + " (" + signature.source() + ")", now);
        DocumentRequest saved = save(request, List.of(stored.getId()));
        audit(actor, AuditActionType.I983_SIGNATURE_APPLIED, saved, "Organization signature applied to Signed I-983", null,
                auditValues(saved, "signedDocumentId", stored.getId(), "signedVersion", versionNumber(saved, stored.getId()),
                        "signatureSource", signature.source(), "signatoryName", signature.signatoryName(),
                        "signatoryTitle", signature.signatoryTitle(), "signedAt", now.toString(), "signatureCount", placements.size(), "pages", pages(placements)));
        return detail(actor, saved);
    }

    public I983WorkflowResponse approve(String actorUserId, String requestId) {
        var actor = authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        DocumentRequest request = requireReviewable(actor, requestId);
        if (!signedIsCurrent(request))
            throw new BusinessException("Apply the organization signature to the current document before approving");
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(DocumentRequestStatus.APPROVED);
        request.setApprovedByUserId(actor.userId());
        request.setApprovedAt(now);
        request.setReviewedByUserId(actor.userId());
        request.setReviewedAt(now);
        // The signed PDF is the completed document the employee receives.
        request.setFulfilledByUserId(actor.userId());
        request.setFulfilledDocumentId(request.getOrganizationSignedDocumentId());
        request.setFulfilledAt(now);
        addHistory(request, actor, "APPROVED", DocumentRequestStatus.SUBMITTED, DocumentRequestStatus.APPROVED,
                request.getOrganizationSignedDocumentId(), "Approved signed version " + versionNumber(request, request.getOrganizationSignedDocumentId()), now);
        DocumentRequest saved = save(request, List.of());
        audit(actor, AuditActionType.I983_APPROVED, saved, "Signed I-983 approved",
                Map.of("status", DocumentRequestStatus.SUBMITTED.name()),
                auditValues(saved, "finalSignedDocumentId", saved.getOrganizationSignedDocumentId(),
                        "finalSignedVersion", versionNumber(saved, saved.getOrganizationSignedDocumentId())));
        notifySafely(() -> notifyRequester(actor, saved, NotificationType.DOCUMENT_REQUEST_FULFILLED, "Signed I-983 approved",
                "Your Signed I-983 has been approved. The completed signed document is ready to view and download."));
        notifyFulfillmentEmail(saved);
        return detail(actor, saved);
    }

    public I983WorkflowResponse returnForCorrection(String actorUserId, String requestId, String reason) {
        var actor = authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        DocumentRequest request = requireReviewable(actor, requestId);
        String normalized = requireReason(reason, "Return reason");
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(DocumentRequestStatus.RETURNED_FOR_CORRECTION);
        request.setReturnReason(normalized);
        request.setReviewedByUserId(actor.userId());
        request.setReviewedAt(now);
        addHistory(request, actor, "RETURNED_FOR_CORRECTION", DocumentRequestStatus.SUBMITTED,
                DocumentRequestStatus.RETURNED_FOR_CORRECTION, null, normalized, now);
        DocumentRequest saved = save(request, List.of());
        audit(actor, AuditActionType.I983_RETURNED_FOR_CORRECTION, saved, "Signed I-983 returned for correction",
                Map.of("status", DocumentRequestStatus.SUBMITTED.name()), auditValues(saved, "reason", normalized));
        notifySafely(() -> notifyRequester(actor, saved, NotificationType.DOCUMENT_REQUEST_RETURNED, "Signed I-983 returned for correction",
                "Your Signed I-983 was returned for correction. Reason: " + normalized));
        return detail(actor, saved);
    }

    public I983WorkflowResponse reject(String actorUserId, String requestId, String reason) {
        var actor = authenticatedUserService.require(actorUserId, REVIEWER_ROLES, false);
        DocumentRequest request = requireReviewable(actor, requestId);
        String normalized = requireReason(reason, "Rejection reason");
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(DocumentRequestStatus.REJECTED);
        request.setRejectionReason(normalized);
        request.setRejectedByUserId(actor.userId());
        request.setRejectedAt(now);
        request.setReviewedByUserId(actor.userId());
        request.setReviewedAt(now);
        addHistory(request, actor, "REJECTED", DocumentRequestStatus.SUBMITTED, DocumentRequestStatus.REJECTED, null, normalized, now);
        DocumentRequest saved = save(request, List.of());
        audit(actor, AuditActionType.I983_REJECTED, saved, "Signed I-983 rejected",
                Map.of("status", DocumentRequestStatus.SUBMITTED.name()), auditValues(saved, "reason", normalized));
        notifySafely(() -> notifyRequester(actor, saved, NotificationType.DOCUMENT_REQUEST_REJECTED, "Signed I-983 rejected",
                "Your Signed I-983 was rejected. Reason: " + normalized));
        return detail(actor, saved);
    }

    // ---------------------------------------------------------------- PDF

    /**
     * Draws the signature image into every rectangle the signer chose (one
     * or more, on any pages). Each placement is relative to the page as
     * displayed (after /Rotate), so its corners are mapped back into
     * unrotated PDF user space and the image is drawn with an explicit
     * transform that keeps it upright on screen.
     */
    static byte[] stampSignature(byte[] pdf, byte[] signatureImage, List<I983SignaturePlacement> placements) {
        PdfReader reader = null;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            reader = new PdfReader(pdf);
            for (I983SignaturePlacement placement : placements)
                if (placement.page() > reader.getNumberOfPages())
                    throw new BusinessException("The selected page does not exist in this document");
            PdfStamper stamper = new PdfStamper(reader, output);
            Image image = Image.getInstance(signatureImage);
            for (I983SignaturePlacement placement : placements) {
                Rectangle crop = reader.getCropBox(placement.page());
                int rotation = ((reader.getPageRotation(placement.page()) % 360) + 360) % 360;
                double x = placement.x(), y = placement.y(), w = placement.width(), h = placement.height();
                float[] bottomLeft = toUserSpace(crop, rotation, x, y + h);
                float[] bottomRight = toUserSpace(crop, rotation, x + w, y + h);
                float[] topLeft = toUserSpace(crop, rotation, x, y);
                stamper.getOverContent(placement.page()).addImage(image,
                        bottomRight[0] - bottomLeft[0], bottomRight[1] - bottomLeft[1],
                        topLeft[0] - bottomLeft[0], topLeft[1] - bottomLeft[1],
                        bottomLeft[0], bottomLeft[1]);
            }
            stamper.close();
            return output.toByteArray();
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception failure) {
            log.warn("Organization signature could not be applied to I-983 PDF", failure);
            throw new BusinessException("The signature could not be applied to this PDF");
        } finally {
            if (reader != null) reader.close();
        }
    }

    /** (u, v) are fractions of the displayed page from its top-left corner. */
    static float[] toUserSpace(Rectangle crop, int rotation, double u, double v) {
        double left = crop.getLeft(), bottom = crop.getBottom(), width = crop.getWidth(), height = crop.getHeight();
        double px, py;
        switch (rotation) {
            case 90 -> { px = left + v * width; py = bottom + u * height; }
            case 180 -> { px = left + (1 - u) * width; py = bottom + v * height; }
            case 270 -> { px = left + (1 - v) * width; py = bottom + (1 - u) * height; }
            default -> { px = left + u * width; py = bottom + (1 - v) * height; }
        }
        return new float[] {(float) px, (float) py};
    }

    /** Only readable, unprotected PDFs are accepted, so the signature can always be applied later. */
    private byte[] readPdf(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BusinessException("Upload the I-983 document as a PDF");
        if (file.getSize() > MAX_PDF_BYTES) throw new BusinessException("The I-983 document must be 10 MB or smaller");
        byte[] content;
        try { content = file.getBytes(); } catch (IOException failure) { throw new BusinessException("The I-983 document could not be read"); }
        boolean pdfSignature = content.length >= 5 && "%PDF-".equals(new String(content, 0, 5, StandardCharsets.US_ASCII));
        if (!pdfSignature) throw new BusinessException("The I-983 document must be a PDF file");
        PdfReader reader = null;
        try {
            reader = new PdfReader(content);
            if (reader.getNumberOfPages() < 1) throw new BusinessException("The I-983 PDF has no pages");
            if (reader.isEncrypted() && !reader.isOpenedWithFullPermissions())
                throw new BusinessException("This PDF is password-protected and cannot be signed. Upload an unprotected copy.");
        } catch (BusinessException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new BusinessException("The I-983 document is not a valid PDF");
        } finally {
            if (reader != null) reader.close();
        }
        return content;
    }

    // ---------------------------------------------------------------- response

    private I983WorkflowResponse detail(AuthenticatedMyHrUser actor, DocumentRequest request) {
        boolean reviewer = canReview(actor, request);
        boolean requester = actor.userId().equals(request.getRequestedByUserId());
        List<DocumentRequestVersion> versions = Optional.ofNullable(request.getDocumentVersions()).orElse(List.of());
        List<DocumentRequestHistoryEntry> history = Optional.ofNullable(request.getHistory()).orElse(List.of());
        Set<String> userIds = new HashSet<>();
        versions.forEach(version -> userIds.add(version.getUploadedByUserId()));
        userIds.add(request.getReviewedByUserId());
        userIds.add(request.getApprovedByUserId());
        userIds.remove(null);
        Map<String, String> names = names(userIds, actor.organizationId());
        List<I983WorkflowResponse.Version> visibleVersions = versions.stream()
                .filter(version -> reviewer || DocumentRequestService.requesterMaySee(request, version))
                .map(version -> new I983WorkflowResponse.Version(version.getDocumentId(), version.getVersion(), version.getKind(),
                        version.getFileName(), names.getOrDefault(version.getUploadedByUserId(), ""), version.getUploadedByRole(),
                        version.getUploadedAt(), version.getBasedOnDocumentId(), version.getSignatureSource(),
                        version.getSignatoryName(), version.getSignatoryTitle(), version.getSignatureEffectiveDate(),
                        version.getSignaturePages()))
                .toList();
        List<I983WorkflowResponse.HistoryEntry> visibleHistory = history.stream()
                // Document views are internal review activity - the employee sees status changes and their own actions.
                .filter(entry -> reviewer || !"REVIEWED".equals(entry.getAction()))
                .map(entry -> new I983WorkflowResponse.HistoryEntry(entry.getAction(), entry.getActorName(), entry.getActorRole(),
                        entry.getFromStatus(), entry.getToStatus(), entry.getDetail(), entry.getAt()))
                .toList();
        boolean approved = request.getStatus() == DocumentRequestStatus.APPROVED;
        return new I983WorkflowResponse(
                documentRequestService.get(actor.userId(), request.getId()),
                request.getOriginalDocumentId(),
                reviewer ? request.getCurrentDocumentId() : latestEmployeeSubmission(request),
                reviewer || approved ? request.getOrganizationSignedDocumentId() : null,
                signedIsCurrent(request),
                reviewer,
                reviewer && request.getStatus() == DocumentRequestStatus.SUBMITTED,
                requester && request.getStatus() == DocumentRequestStatus.RETURNED_FOR_CORRECTION,
                request.getReturnReason(),
                names.get(request.getReviewedByUserId()), request.getReviewedAt(),
                names.get(request.getApprovedByUserId()), request.getApprovedAt(),
                request.getSubmittedAt(), visibleVersions, visibleHistory);
    }

    // ---------------------------------------------------------------- helpers

    private DocumentRequest requireI983(AuthenticatedMyHrUser actor, String requestId) {
        // Visibility (Company Admin, the requester, the assigned HR) is enforced by DocumentRequestService.
        documentRequestService.get(actor.userId(), requestId);
        DocumentRequest request = requestRepository.findByIdAndOrganizationId(requestId, actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Document request not found"));
        if (request.getDocumentType() != RequestedDocumentType.I983 || request.getOriginalDocumentId() == null)
            throw new ResourceNotFoundException("Signed I-983 request not found");
        return request;
    }

    private DocumentRequest requireReviewable(AuthenticatedMyHrUser actor, String requestId) {
        DocumentRequest request = requireI983(actor, requestId);
        requireReviewer(actor, request);
        if (request.getStatus() != DocumentRequestStatus.SUBMITTED)
            throw new BusinessException("Only submitted I-983 requests awaiting review can be changed");
        return request;
    }

    private void requireReviewer(AuthenticatedMyHrUser actor, DocumentRequest request) {
        if (!canReview(actor, request))
            throw new ForbiddenException("Only the assigned HR or a Company Admin can review this I-983");
    }

    private boolean canReview(AuthenticatedMyHrUser actor, DocumentRequest request) {
        if (actor.userId().equals(request.getRequestedByUserId())) return false;
        return actor.role() == UserRole.COMPANY_ADMIN
                || (actor.role() == UserRole.HR && actor.userId().equals(request.getRequestedForUserId()));
    }

    /** What the employee last submitted: their signed copy if they signed it, otherwise their upload. */
    private static String latestEmployeeSubmission(DocumentRequest request) {
        List<DocumentRequestVersion> versions = Optional.ofNullable(request.getDocumentVersions()).orElse(List.of());
        for (int index = versions.size() - 1; index >= 0; index--) {
            DocumentRequestVersion version = versions.get(index);
            if (request.getRequestedByUserId().equals(version.getUploadedByUserId())
                    && (DocumentRequestVersion.ORIGINAL.equals(version.getKind())
                    || DocumentRequestVersion.EMPLOYEE_SIGNED.equals(version.getKind())))
                return version.getDocumentId();
        }
        return request.getOriginalDocumentId();
    }

    private static boolean signedIsCurrent(DocumentRequest request) {
        String signedId = request.getOrganizationSignedDocumentId();
        if (signedId == null) return false;
        return Optional.ofNullable(request.getDocumentVersions()).orElse(List.of()).stream()
                .anyMatch(version -> signedId.equals(version.getDocumentId())
                        && Objects.equals(version.getBasedOnDocumentId(), request.getCurrentDocumentId()));
    }

    private User requireActiveReviewerManager(AuthenticatedMyHrUser actor) {
        ReportingAssignment assignment = assignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .orElseThrow(() -> new BusinessException("No active primary reporting manager is assigned"));
        User manager = userRepository.findById(assignment.getReportingManagerUserId())
                .filter(user -> actor.organizationId().equals(user.getOrganizationId()))
                .orElseThrow(() -> new BusinessException("An active HR or Company Admin reporting manager is required"));
        if (!REVIEWER_ROLES.contains(manager.getRole()) || manager.getAccountStatus() != AccountStatus.ACTIVE
                || manager.getId().equals(actor.userId()))
            throw new BusinessException("An active HR or Company Admin reporting manager is required");
        return manager;
    }

    private StoredDocument store(AuthenticatedMyHrUser actor, DocumentRequest request, String fileName, byte[] content, int version) {
        String name = fileName == null || fileName.isBlank() ? "I-983.pdf" : fileName;
        return storeBytes(actor, request, name, content, version);
    }

    private StoredDocument storeBytes(AuthenticatedMyHrUser actor, DocumentRequest request, String fileName, byte[] content, int version) {
        return documents().store(new DocumentUploadRequest(actor.organizationId(), request.getRequestedByUserId(),
                DocumentType.REQUESTED_DOCUMENT, RELATED_ENTITY_TYPE, request.getId(), fileName, PDF, content, version, actor.userId()));
    }

    /** Saves the request; if that fails, the files stored for this step are removed so nothing is orphaned. */
    private DocumentRequest save(DocumentRequest request, List<String> newDocumentIds) {
        request.setUpdatedAt(LocalDateTime.now());
        try {
            return requestRepository.save(request);
        } catch (RuntimeException failure) {
            DocumentService service = documentServiceProvider.getIfAvailable();
            if (service != null) for (String id : newDocumentIds)
                try { service.delete(id); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    private DocumentRequestVersion addVersion(DocumentRequest request, AuthenticatedMyHrUser actor, StoredDocument stored,
                                              String kind, LocalDateTime at) {
        if (request.getDocumentVersions() == null) request.setDocumentVersions(new ArrayList<>());
        DocumentRequestVersion version = DocumentRequestVersion.builder().documentId(stored.getId())
                .version(stored.getVersion() == null ? nextVersion(request) : stored.getVersion()).kind(kind)
                .fileName(stored.getFileName()).uploadedByUserId(actor.userId()).uploadedByRole(actor.role().name())
                .uploadedAt(at).build();
        request.getDocumentVersions().add(version);
        return version;
    }

    private int nextVersion(DocumentRequest request) {
        return Optional.ofNullable(request.getDocumentVersions()).orElse(List.of()).stream()
                .mapToInt(DocumentRequestVersion::getVersion).max().orElse(0) + 1;
    }

    private Optional<DocumentRequestVersion> findVersion(DocumentRequest request, String documentId) {
        return Optional.ofNullable(request.getDocumentVersions()).orElse(List.of()).stream()
                .filter(version -> version.getDocumentId().equals(documentId)).findFirst();
    }

    private Integer versionNumber(DocumentRequest request, String documentId) {
        return findVersion(request, documentId).map(DocumentRequestVersion::getVersion).orElse(null);
    }

    private String describe(DocumentRequestVersion version, String fileName) {
        if (version == null) return fileName;
        String kind = switch (version.getKind()) {
            case DocumentRequestVersion.SIGNED -> "signed document";
            case DocumentRequestVersion.EMPLOYEE_SIGNED -> "employee-signed document";
            case DocumentRequestVersion.REPLACEMENT -> "replacement document";
            default -> "original document";
        };
        return kind + " " + fileName + " (version " + version.getVersion() + ")";
    }

    private void addHistory(DocumentRequest request, AuthenticatedMyHrUser actor, String action, DocumentRequestStatus from,
                            DocumentRequestStatus to, String documentId, String detail, LocalDateTime at) {
        if (request.getHistory() == null) request.setHistory(new ArrayList<>());
        request.getHistory().add(DocumentRequestHistoryEntry.builder().action(action).actorUserId(actor.userId())
                .actorName(label(actor)).actorRole(actor.role().name()).fromStatus(from).toStatus(to)
                .documentId(documentId).detail(detail).at(at).build());
    }

    private String requireReason(String reason, String label) {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) throw new BusinessException(label + " is required");
        if (normalized.length() > 1000) throw new BusinessException(label + " must not exceed 1000 characters");
        return normalized;
    }

    private DocumentService documents() {
        DocumentService service = documentServiceProvider.getIfAvailable();
        if (service == null) throw new BusinessException("Document storage is not configured");
        return service;
    }

    private void notifyFulfillmentEmail(DocumentRequest request) {
        try {
            DocumentService.DocumentContent content = documents().getContent(request.getOrganizationSignedDocumentId());
            fulfillmentEmailService.send(request, new EmailAttachment(content.metadata().getFileName(), content.content(),
                    content.contentType()), (String) null);
        } catch (RuntimeException failure) {
            log.warn("Signed I-983 fulfillment email could not be prepared [requestId={}, exceptionType={}]", request.getId(),
                    failure.getClass().getSimpleName());
        }
    }

    private Map<String, Object> auditValues(DocumentRequest request, Object... extra) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("status", request.getStatus().name());
        values.put("originalDocumentId", request.getOriginalDocumentId());
        values.put("currentDocumentId", request.getCurrentDocumentId());
        values.put("signedDocumentId", request.getOrganizationSignedDocumentId());
        for (int index = 0; index + 1 < extra.length; index += 2) values.put(String.valueOf(extra[index]), extra[index + 1]);
        return values;
    }

    private void audit(AuthenticatedMyHrUser actor, AuditActionType action, DocumentRequest request, String description,
                       Object previousValue, Map<String, Object> updatedValue) {
        try {
            User requester = userRepository.findById(request.getRequestedByUserId()).orElse(null);
            if (requester != null) updatedValue.put("requestedBy", participantLabel(requester,
                    employeeProfileRepository.findByUserId(requester.getId()).orElse(null)));
        } catch (RuntimeException enrichmentFailure) {
            log.warn("Could not enrich I-983 audit entry for request {}", request.getId());
        }
        auditService.record(AuditEvent.builder().organizationId(actor.organizationId()).actorUserId(actor.userId())
                .actorLoginId(actor.loginId()).actorRole(actor.role()).actionType(action)
                .entityType("DOCUMENT_REQUEST").entityId(request.getId()).entityDisplayName(request.getRequestTitle())
                .description(description).previousValue(previousValue).updatedValue(updatedValue)
                .outcome("SUCCESS").source("MY_HR").build());
    }

    private void notifySafely(Runnable notification) {
        try { notification.run(); } catch (RuntimeException failure) { log.warn("I-983 notification could not be created", failure); }
    }

    /** The assigned HR plus every active Company Admin, never the person who acted. */
    private void notifyReviewers(AuthenticatedMyHrUser actor, DocumentRequest request, String title, String message) {
        Map<String, User> recipients = new LinkedHashMap<>();
        userRepository.findById(request.getRequestedForUserId())
                .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE)
                .ifPresent(user -> recipients.put(user.getId(), user));
        userRepository.findByOrganizationIdAndAccountStatusAndRoleIn(actor.organizationId(), AccountStatus.ACTIVE,
                Set.of(UserRole.COMPANY_ADMIN)).forEach(user -> recipients.putIfAbsent(user.getId(), user));
        recipients.remove(actor.userId());
        notificationOrchestrator.notifyAll(recipients.values().stream().map(recipient -> NotificationEvent.builder()
                .organizationId(actor.organizationId()).recipientUserId(recipient.getId())
                .type(NotificationType.DOCUMENT_REQUEST_CREATED).title(title).message(message)
                .relatedEntityId(request.getId()).actionPath(link(recipient.getRole(), request))
                .email(true).actionLabel("Review I-983").build()).toList());
    }

    private void notifyRequester(AuthenticatedMyHrUser actor, DocumentRequest request, NotificationType type, String title, String message) {
        if (actor.userId().equals(request.getRequestedByUserId())) return;
        User requester = userRepository.findById(request.getRequestedByUserId()).orElse(null);
        // The approved case already has a dedicated signed-document email, so it stays in-app only.
        notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId())
                .recipientUserId(request.getRequestedByUserId()).type(type).title(title).message(message)
                .relatedEntityId(request.getId()).actionPath(link(requester == null ? UserRole.EMPLOYEE : requester.getRole(), request))
                .email(type != NotificationType.DOCUMENT_REQUEST_FULFILLED).actionLabel("View I-983").build());
    }

    private String link(UserRole role, DocumentRequest request) {
        String persona = role == UserRole.COMPANY_ADMIN ? "admin" : role == UserRole.HR ? "hr" : "employee";
        return "/myhr/" + persona + "/document-requests/" + request.getId();
    }

    private Map<String, String> names(Set<String> userIds, String organizationId) {
        if (userIds.isEmpty()) return Map.of();
        Map<String, EmployeeProfile> profiles = employeeProfileRepository.findByOrganizationId(organizationId).stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, Function.identity(), (left, right) -> left));
        return userRepository.findByIdIn(userIds).stream()
                .collect(Collectors.toMap(User::getId, user -> displayName(user, profiles.get(user.getId())), (left, right) -> left));
    }

    private String label(AuthenticatedMyHrUser actor) {
        User user = User.builder().id(actor.userId()).loginId(actor.loginId()).build();
        return displayName(user, employeeProfileRepository.findByUserId(actor.userId()).orElse(null));
    }

    private String participantLabel(User user, EmployeeProfile profile) {
        String employeeId = profile == null ? null : profile.getEmployeeId();
        String identity = displayName(user, profile);
        return employeeId == null || employeeId.isBlank() ? identity : identity + " (" + employeeId + ")";
    }

    private String displayName(User user, EmployeeProfile profile) {
        if (profile != null) {
            String full = ((profile.getFirstName() == null ? "" : profile.getFirstName()) + " "
                    + (profile.getLastName() == null ? "" : profile.getLastName())).trim();
            if (!full.isBlank()) return full;
            if (profile.getName() != null && !profile.getName().isBlank()) return profile.getName();
        }
        return user.getLoginId();
    }
}
