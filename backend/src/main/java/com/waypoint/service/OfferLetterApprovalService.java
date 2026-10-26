package com.waypoint.service;

import com.waypoint.dto.myhr.I983SignaturePlacement;
import com.waypoint.dto.myhr.OfferLetterApprovalListResponse;
import com.waypoint.dto.myhr.OfferLetterApprovalResponse;
import com.waypoint.dto.myhr.OfferLetterApprovalSubmitRequest;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.DocumentRequestHistoryEntry;
import com.waypoint.entity.DocumentRequestVersion;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.OfferLetterApprovalDetails;
import com.waypoint.entity.OfferLetterApprovalDetails.SignaturePlacement;
import com.waypoint.entity.Organization;
import com.waypoint.entity.StoredDocument;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.DocumentRequestStatus;
import com.waypoint.enums.DocumentType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.DocumentRequestRepository;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import com.waypoint.service.AuthorizedSignatureService.ActiveSignature;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Company Admin approval of a document before HR may send it to the employee - an offer letter or any other
 * document HR delivers (prepared letters, uploaded documents, LCA / PERM / I-140). A Company Admin delivers
 * directly, and the Signed I-983 keeps its own review workflow. The class, its stored field and its enum values
 * keep the "OfferLetter" names so approvals, notifications and audit entries created for offer letters stay readable.
 *
 * <p>The lifecycle reuses the document request's existing status and approval fields:
 * PENDING (draft) -> SUBMITTED (pending approval) -> APPROVED -> FULFILLED (sent), and
 * SUBMITTED -> RETURNED_FOR_CORRECTION (rejected by the Admin) -> SUBMITTED again.
 * Every transition is an atomic compare-and-set on the status, so a double click or two
 * concurrent users can never create a duplicate submission, approval or notification.
 * Sending itself stays in {@link DocumentRequestService#deliverApprovedOfferLetter} and
 * {@link OfferLetterService#email}, which refuse anything that was not approved here.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfferLetterApprovalService {

    private static final Set<UserRole> SENDERS = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);
    private static final Set<UserRole> ADMINS = Set.of(UserRole.COMPANY_ADMIN);
    private static final String ENTITY_TYPE = "DOCUMENT_REQUEST";
    private static final int MAX_REASON_LENGTH = 1000;

    static final String CONCURRENT_CHANGE = "This offer letter was just changed. Refresh the page and try again.";

    private final DocumentRequestRepository requests;
    private final UserRepository users;
    private final EmployeeProfileRepository profiles;
    private final MyHrAuthenticatedUserService auth;
    private final DocumentService documents;
    private final AuditService audit;
    private final NotificationOrchestrator notifications;
    private final OrganizationRepository organizations;
    private final AuthorizedSignatureService signatures;

    // ------------------------------------------------------------------ HR: submit

    public OfferLetterApprovalResponse submit(String actorUserId, String requestId, OfferLetterApprovalSubmitRequest input,
                                              String fileName, String contentType, byte[] content) {
        AuthenticatedMyHrUser actor = auth.require(actorUserId, SENDERS, false);
        DocumentRequest request = requireApprovalDocument(actor, requestId);
        requireSender(actor, request);
        if (input == null) throw new BusinessException("Document details are required");
        requireFile(request, content);
        boolean offer = isOfferLetter(request);
        SignaturePlacement placement = signaturePlacement(actor, input.authorizedSignature(), content);
        String storedContentType = offer || placement != null ? "application/pdf" : safeContentType(contentType);
        DocumentRequestStatus previous = request.getStatus();
        switch (previous) {
            case PENDING, RETURNED_FOR_CORRECTION -> { }
            case SUBMITTED -> throw new BusinessException("This " + noun(request) + " is already waiting for Admin approval.");
            case APPROVED -> throw new BusinessException("This " + noun(request) + " is already approved. Send it to the employee.");
            case FULFILLED -> throw new BusinessException("This " + noun(request) + " has already been sent to the employee.");
            default -> throw new BusinessException("This " + noun(request) + " can no longer be submitted for approval.");
        }
        // A Company Admin's own document needs no separate approval - unless it carries the company admin's
        // signature and they are not the signatory: then it waits for the signatory like HR's documents do.
        boolean adminSubmitter = actor.role() == UserRole.COMPANY_ADMIN && (placement == null || isSignatory(actor));
        DocumentRequestStatus target = adminSubmitter ? DocumentRequestStatus.APPROVED : DocumentRequestStatus.SUBMITTED;
        int version = versionNumber(request);
        String originalName = offer ? "offer-letter.pdf" : safeFileName(fileName);
        if (placement != null && !originalName.toLowerCase().endsWith(".pdf")) originalName = originalName + ".pdf";
        StoredDocument stored = documents.store(new DocumentUploadRequest(actor.organizationId(), request.getRequestedByUserId(),
                DocumentType.REQUESTED_DOCUMENT, ENTITY_TYPE, request.getId(),
                offer ? "offer-letter-approval-v" + version + ".pdf" : "approval-v" + version + "-" + originalName,
                storedContentType, content, version, actor.userId()));
        SignedCopy signed = null;
        try {
            if (adminSubmitter && placement != null) signed = sign(actor, request, originalName, content, placement, version + 1);
        } catch (RuntimeException failure) {
            deleteQuietly(stored.getId());
            throw failure;
        }
        LocalDateTime now = LocalDateTime.now();
        if (!claim(request, Set.of(previous), target, now)) {
            deleteQuietly(stored.getId());
            if (signed != null) deleteQuietly(signed.document().getId());
            throw new BusinessException(CONCURRENT_CHANGE);
        }
        try {
            String submitter = displayName(actor.userId(), actor.loginId());
            request.setOfferLetterApproval(OfferLetterApprovalDetails.builder()
                    .documentType(request.getDocumentType() == null ? null : request.getDocumentType().name())
                    .documentTitle(request.getRequestTitle()).fileName(originalName).contentType(storedContentType)
                    .employeeName(trim(input.employeeName())).designation(trim(input.designation()))
                    .offerType(trim(input.offerType())).employmentCategory(trim(input.employmentCategory()))
                    .joiningDate(trim(input.joiningDate())).issueDate(trim(input.issueDate())).payRate(trim(input.payRate()))
                    .workLocation(trim(input.workLocation())).rolesAndResponsibilities(trim(input.rolesAndResponsibilities()))
                    .submittedByUserId(actor.userId()).submittedByName(submitter).submittedAt(now)
                    .formJson(input.formJson()).authorizedSignature(placement).build());
            request.setCurrentDocumentId(stored.getId());
            List<DocumentRequestVersion> versions = new ArrayList<>(orEmpty(request.getDocumentVersions()));
            versions.add(DocumentRequestVersion.builder().documentId(stored.getId()).version(version).kind("OFFER_LETTER")
                    .fileName(stored.getFileName()).uploadedByUserId(actor.userId()).uploadedByRole(actor.role().name())
                    .uploadedAt(now).build());
            request.setDocumentVersions(versions);
            if (signed != null) recordSignedCopy(request, actor, signed, stored.getId(), now);
            request.setStatus(target);
            request.setSubmittedAt(now);
            request.setReviewedByUserId(null);
            request.setReviewedAt(null);
            request.setReturnReason(null);
            request.setApprovedByUserId(adminSubmitter ? actor.userId() : null);
            request.setApprovedAt(adminSubmitter ? now : null);
            request.setUpdatedAt(now);
            addHistory(request, actor, previous == DocumentRequestStatus.RETURNED_FOR_CORRECTION ? "RESUBMITTED" : "SUBMITTED",
                    previous, DocumentRequestStatus.SUBMITTED, stored.getId(), label(request, true) + " v" + version + " submitted for approval", now);
            if (adminSubmitter)
                addHistory(request, actor, "APPROVED", DocumentRequestStatus.SUBMITTED, DocumentRequestStatus.APPROVED, request.getCurrentDocumentId(),
                        signed != null ? "Prepared and signed by the company admin, so no separate approval is needed"
                                : "Prepared by a Company Admin, so no separate approval is needed", now);
            request = requests.save(request);
        } catch (RuntimeException failure) {
            // Put the request back exactly as it was and drop the unreferenced files.
            claim(request, Set.of(target), previous, LocalDateTime.now());
            deleteQuietly(stored.getId());
            if (signed != null) deleteQuietly(signed.document().getId());
            throw failure;
        }
        recordAudit(actor, AuditActionType.OFFER_SUBMITTED_FOR_APPROVAL, request, label(request, true) + " submitted for approval", previous, DocumentRequestStatus.SUBMITTED, null);
        if (adminSubmitter) {
            recordAudit(actor, AuditActionType.OFFER_APPROVED, request, label(request, true) + " approved (prepared by a Company Admin)",
                    DocumentRequestStatus.SUBMITTED, DocumentRequestStatus.APPROVED, null);
        } else {
            DocumentRequest saved = request;
            notifySafely(() -> notifyAdminsApprovalRequired(actor, saved));
        }
        return toResponse(actor, request);
    }

    // ------------------------------------------------------------------ Admin: approve / reject

    public OfferLetterApprovalResponse approve(String actorUserId, String requestId) {
        AuthenticatedMyHrUser actor = auth.require(actorUserId, ADMINS, false);
        DocumentRequest request = requireApprovalDocument(actor, requestId);
        requireAwaitingApproval(request, "approved");
        SignaturePlacement placement = request.getOfferLetterApproval().getAuthorizedSignature();
        if (placement != null) requireSignatory(actor, request, "approve");
        // Approving a document that names the company admin is their signature: their stored signature is
        // applied to the exact PDF HR submitted, on the server, and the signed copy is what HR then sends.
        String unsignedId = request.getCurrentDocumentId();
        SignedCopy signed = placement == null ? null
                : sign(actor, request, request.getOfferLetterApproval().getFileName(), documents.getContent(unsignedId).content(), placement, versionNumber(request));
        LocalDateTime now = LocalDateTime.now();
        if (!claim(request, Set.of(DocumentRequestStatus.SUBMITTED), DocumentRequestStatus.APPROVED, now)) {
            if (signed != null) deleteQuietly(signed.document().getId());
            throw new BusinessException(CONCURRENT_CHANGE);
        }
        try {
            if (signed != null) recordSignedCopy(request, actor, signed, unsignedId, now);
            request.setStatus(DocumentRequestStatus.APPROVED);
            request.setApprovedByUserId(actor.userId());
            request.setApprovedAt(now);
            request.setReviewedByUserId(actor.userId());
            request.setReviewedAt(now);
            request.setReturnReason(null);
            request.setUpdatedAt(now);
            addHistory(request, actor, "APPROVED", DocumentRequestStatus.SUBMITTED, DocumentRequestStatus.APPROVED,
                    request.getCurrentDocumentId(), label(request, true) + " approved", now);
            request = requests.save(request);
        } catch (RuntimeException failure) {
            claim(request, Set.of(DocumentRequestStatus.APPROVED), DocumentRequestStatus.SUBMITTED, LocalDateTime.now());
            if (signed != null) {
                request.setCurrentDocumentId(unsignedId);
                deleteQuietly(signed.document().getId());
            }
            throw failure;
        }
        recordAudit(actor, AuditActionType.OFFER_APPROVED, request, label(request, true)
                + (signed != null ? " approved and signed by the company admin" : " approved"), DocumentRequestStatus.SUBMITTED, DocumentRequestStatus.APPROVED, null);
        DocumentRequest saved = request;
        notifySafely(() -> notifySubmitter(actor, saved, true, null));
        return toResponse(actor, request);
    }

    /** Compatibility overload for the approval dialog's optional comment. */
    public OfferLetterApprovalResponse approve(String actorUserId, String requestId, String comment) {
        return approve(actorUserId, requestId);
    }

    public OfferLetterApprovalResponse reject(String actorUserId, String requestId, String reason) {
        AuthenticatedMyHrUser actor = auth.require(actorUserId, ADMINS, false);
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) throw new BusinessException("Rejection reason is required");
        if (normalized.length() > MAX_REASON_LENGTH) throw new BusinessException("Rejection reason must not exceed " + MAX_REASON_LENGTH + " characters");
        DocumentRequest request = requireApprovalDocument(actor, requestId);
        requireAwaitingApproval(request, "rejected");
        if (request.getOfferLetterApproval().getAuthorizedSignature() != null) requireSignatory(actor, request, "reject");
        LocalDateTime now = LocalDateTime.now();
        if (!claim(request, Set.of(DocumentRequestStatus.SUBMITTED), DocumentRequestStatus.RETURNED_FOR_CORRECTION, now))
            throw new BusinessException(CONCURRENT_CHANGE);
        try {
            request.setStatus(DocumentRequestStatus.RETURNED_FOR_CORRECTION);
            request.setReturnReason(normalized);
            request.setReviewedByUserId(actor.userId());
            request.setReviewedAt(now);
            request.setApprovedByUserId(null);
            request.setApprovedAt(null);
            request.setUpdatedAt(now);
            addHistory(request, actor, "REJECTED", DocumentRequestStatus.SUBMITTED, DocumentRequestStatus.RETURNED_FOR_CORRECTION,
                    request.getCurrentDocumentId(), normalized, now);
            request = requests.save(request);
        } catch (RuntimeException failure) {
            claim(request, Set.of(DocumentRequestStatus.RETURNED_FOR_CORRECTION), DocumentRequestStatus.SUBMITTED, LocalDateTime.now());
            throw failure;
        }
        recordAudit(actor, AuditActionType.OFFER_REJECTED, request, label(request, true) + " rejected", DocumentRequestStatus.SUBMITTED,
                DocumentRequestStatus.RETURNED_FOR_CORRECTION, normalized);
        DocumentRequest saved = request;
        notifySafely(() -> notifySubmitter(actor, saved, false, normalized));
        return toResponse(actor, request);
    }

    // ------------------------------------------------------------------ reads

    public OfferLetterApprovalResponse get(String actorUserId, String requestId) {
        AuthenticatedMyHrUser actor = auth.require(actorUserId, SENDERS, false);
        DocumentRequest request = requireApprovalDocument(actor, requestId);
        if (actor.role() == UserRole.HR && !isSender(actor, request)) throw new ResourceNotFoundException("Document request not found");
        return toResponse(actor, request);
    }

    /** Admin approvals page: every offer letter that was submitted for approval, with counts. */
    public OfferLetterApprovalListResponse list(String actorUserId) {
        AuthenticatedMyHrUser actor = auth.require(actorUserId, ADMINS, false);
        List<OfferLetterApprovalResponse> items = requests
                .findByOrganizationIdAndOfferLetterApprovalNotNullOrderByUpdatedAtDesc(actor.organizationId()).stream()
                .map(request -> toResponse(actor, request)).toList();
        long pending = items.stream().filter(item -> "PENDING_APPROVAL".equals(item.approvalStatus())).count();
        long rejected = items.stream().filter(item -> "REJECTED".equals(item.approvalStatus())).count();
        long approved = items.stream().filter(item -> "APPROVED".equals(item.approvalStatus()) || "SENT".equals(item.approvalStatus())).count();
        return new OfferLetterApprovalListResponse(pending, approved, rejected, items);
    }

    /** The approval status label for a request, shared with the document request list. */
    public static String approvalStatus(DocumentRequest request) {
        if (request.getOfferLetterApproval() == null) {
            return request.getStatus() == DocumentRequestStatus.FULFILLED ? "SENT" : "NONE";
        }
        return switch (request.getStatus()) {
            case SUBMITTED -> "PENDING_APPROVAL";
            case APPROVED -> "APPROVED";
            case RETURNED_FOR_CORRECTION -> "REJECTED";
            case FULFILLED -> "SENT";
            default -> "NONE";
        };
    }

    /** True once a Company Admin approved this letter (it may since have been sent). */
    public static boolean isApproved(DocumentRequest request) {
        return request.getOfferLetterApproval() != null && request.getApprovedByUserId() != null && request.getApprovedAt() != null
                && (request.getStatus() == DocumentRequestStatus.APPROVED || request.getStatus() == DocumentRequestStatus.FULFILLED);
    }

    // ------------------------------------------------------------------ internals

    private OfferLetterApprovalResponse toResponse(AuthenticatedMyHrUser actor, DocumentRequest request) {
        OfferLetterApprovalDetails details = request.getOfferLetterApproval();
        String status = approvalStatus(request);
        boolean sender = actor.role() == UserRole.COMPANY_ADMIN || isSender(actor, request);
        boolean canSubmit = sender && (request.getStatus() == DocumentRequestStatus.PENDING
                || request.getStatus() == DocumentRequestStatus.RETURNED_FOR_CORRECTION);
        boolean signedBySignatory = details != null && details.getAuthorizedSignature() != null;
        Signatory signatory = signedBySignatory ? findSignatory(request.getOrganizationId()).orElse(null) : null;
        boolean canApprove = actor.role() == UserRole.COMPANY_ADMIN && request.getStatus() == DocumentRequestStatus.SUBMITTED && details != null
                && (!signedBySignatory || (signatory != null && actor.userId().equals(signatory.userId())));
        boolean canSend = sender && request.getStatus() == DocumentRequestStatus.APPROVED && isApproved(request);
        String reviewedBy = request.getReviewedByUserId() == null ? null : displayName(request.getReviewedByUserId(), null);
        String rejection = request.getStatus() == DocumentRequestStatus.RETURNED_FOR_CORRECTION ? request.getReturnReason() : null;
        return new OfferLetterApprovalResponse(request.getId(), status,
                details == null ? null : details.getEmployeeName(), details == null ? null : details.getDesignation(),
                details == null ? null : details.getOfferType(), details == null ? null : details.getEmploymentCategory(),
                details == null ? null : details.getJoiningDate(), details == null ? null : details.getIssueDate(),
                details == null ? null : details.getPayRate(), details == null ? null : details.getWorkLocation(),
                details == null ? null : details.getRolesAndResponsibilities(), details == null ? null : details.getSubmittedByUserId(),
                details == null ? null : details.getSubmittedByName(), details == null ? null : details.getSubmittedAt(),
                reviewedBy, request.getReviewedAt(), rejection, details == null ? null : request.getCurrentDocumentId(),
                details == null ? null : details.getFormJson(), canSubmit, canApprove, canSend,
                details == null ? null : (details.getDocumentType() == null ? "OFFER_LETTER" : details.getDocumentType()),
                details == null ? null : (details.getDocumentTitle() == null ? "Offer Letter" : details.getDocumentTitle()),
                details == null ? null : details.getFileName(), details == null ? null : details.getContentType(),
                signedBySignatory, signatory == null ? null : signatory.name());
    }

    private DocumentRequest requireApprovalDocument(AuthenticatedMyHrUser actor, String requestId) {
        DocumentRequest request = requests.findByIdAndOrganizationId(requestId, actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Document request not found"));
        if (!requiresApproval(request)) throw new BusinessException("This document does not need Admin approval");
        return request;
    }

    /**
     * True for a document HR delivers to an employee: an offer letter, or any request the employee made
     * to their manager. The Signed I-983 has its own review workflow, and a document the employee is
     * asked to provide (HR requested it) is not delivered by HR.
     */
    public static boolean requiresApproval(DocumentRequest request) {
        if (request.getDocumentType() == RequestedDocumentType.OFFER_LETTER) return true;
        return request.getDocumentType() != RequestedDocumentType.I983 && "TO_MANAGER".equals(request.getRequestDirection());
    }

    private static boolean isOfferLetter(DocumentRequest request) {
        return request.getDocumentType() == RequestedDocumentType.OFFER_LETTER;
    }

    /** "offer letter" for offer letters, otherwise "document" - used in messages. */
    private static String noun(DocumentRequest request) {
        return isOfferLetter(request) ? "offer letter" : "document";
    }

    /** The document's name for messages and audit descriptions ("Offer letter" / the request title). */
    private static String label(DocumentRequest request, boolean sentenceStart) {
        String name = isOfferLetter(request) ? "offer letter" : (hasText(request.getRequestTitle()) ? request.getRequestTitle().trim() : "document");
        return sentenceStart && !name.isEmpty() ? Character.toUpperCase(name.charAt(0)) + name.substring(1) : name;
    }

    /** Same rule as fulfilling the request: a Company Admin, or the HR the request is assigned to. */
    private void requireSender(AuthenticatedMyHrUser actor, DocumentRequest request) {
        if (actor.role() == UserRole.COMPANY_ADMIN) return;
        if (!isSender(actor, request)) throw new ForbiddenException("Only the assigned HR or a company admin can submit this " + noun(request));
    }

    private boolean isSender(AuthenticatedMyHrUser actor, DocumentRequest request) {
        return actor.userId().equals(request.getRequestedForUserId())
                || (request.getOfferLetterApproval() != null && actor.userId().equals(request.getOfferLetterApproval().getSubmittedByUserId()));
    }

    private void requireAwaitingApproval(DocumentRequest request, String verb) {
        if (request.getOfferLetterApproval() == null) throw new BusinessException("This " + noun(request) + " was not submitted for approval");
        switch (request.getStatus()) {
            case SUBMITTED -> { }
            case APPROVED, FULFILLED -> throw new BusinessException("This " + noun(request) + " has already been approved, so it cannot be " + verb + " again.");
            case RETURNED_FOR_CORRECTION -> throw new BusinessException("This " + noun(request) + " has already been rejected, so it cannot be " + verb + " again.");
            default -> throw new BusinessException("Only " + noun(request) + "s waiting for approval can be " + verb + ".");
        }
    }

    // ------------------------------------------------------------------ Company Admin signature

    /**
     * The company's admin account: the organization's active Company Admin. The Admin Email in Organization Settings is
     * a contact/notification address and may differ from the login email, so it is not required to match. When the
     * organization has several active Company Admins, the one whose login email equals the Admin Email is used; if that
     * still does not identify exactly one account the result is empty (never an arbitrary admin). Only this account can
     * approve, reject or sign a document that carries the company admin's signature.
     */
    private Optional<Signatory> findSignatory(String organizationId) {
        Organization organization = organizationId == null ? null : organizations.findById(organizationId).orElse(null);
        if (organization == null) return Optional.empty();
        List<User> admins = users.findByOrganizationIdAndAccountStatusAndRoleIn(organizationId, AccountStatus.ACTIVE, ADMINS)
                .stream()
                .filter(user -> organizationId.equals(user.getOrganizationId()))
                .filter(user -> user.getRole() == UserRole.COMPANY_ADMIN && user.getAccountStatus() == AccountStatus.ACTIVE)
                .toList();
        if (admins.size() > 1 && hasText(organization.getAdminEmail())) {
            String email = organization.getAdminEmail().trim();
            admins = admins.stream().filter(user -> hasText(user.getEmail()) && email.equalsIgnoreCase(user.getEmail().trim())).toList();
        }
        if (admins.size() != 1) return Optional.empty();
        User admin = admins.get(0);
        return Optional.of(new Signatory(admin.getId(), hasText(organization.getAdminName())
                ? organization.getAdminName().trim() : displayName(admin.getId(), admin.getLoginId())));
    }

    private Signatory requireSignatoryAccount(String organizationId) {
        return findSignatory(organizationId).orElseThrow(() -> new BusinessException(
                "The company admin account could not be found. Check that the organization has exactly one active "
                        + "Company Admin account."));
    }

    /**
     * Validates where the company admin signs a submitted PDF (null = the document has no admin signature). The admin
     * must have a signature in their My HR Profile, so nothing waits for an approval that could never sign it.
     */
    private SignaturePlacement signaturePlacement(AuthenticatedMyHrUser actor, I983SignaturePlacement input, byte[] content) {
        if (input == null) return null;
        I983WorkflowService.requirePlacements(List.of(input));
        if (content.length < 5 || !"%PDF-".equals(new String(content, 0, 5, StandardCharsets.US_ASCII)))
            throw new BusinessException("A document signed by the company admin must be a PDF");
        requireSignature(requireSignatoryAccount(actor.organizationId()));
        return new SignaturePlacement(input.page(), input.x(), input.y(), input.width(), input.height());
    }

    private ActiveSignature requireSignature(Signatory signatory) {
        return signatures.findProfileSignature(signatory.userId(), signatory.name(), null).orElseThrow(() -> new BusinessException(
                signatory.name() + " (the company admin) has not uploaded a signature in My HR Profile yet. "
                        + "Ask them to add it before this document can be signed."));
    }

    private boolean isSignatory(AuthenticatedMyHrUser actor) {
        return findSignatory(actor.organizationId()).map(Signatory::userId).filter(actor.userId()::equals).isPresent();
    }

    private void requireSignatory(AuthenticatedMyHrUser actor, DocumentRequest request, String verb) {
        if (isSignatory(actor)) return;
        String name = findSignatory(actor.organizationId()).map(Signatory::name).orElse("the company admin");
        throw new ForbiddenException("This " + noun(request) + " is signed by the company admin, so only " + name + " can " + verb + " it.");
    }

    /** Applies the company admin's Profile signature to the PDF on the server and stores the signed copy (not yet referenced). */
    private SignedCopy sign(AuthenticatedMyHrUser actor, DocumentRequest request, String name, byte[] pdf, SignaturePlacement placement, int version) {
        ActiveSignature signature = requireSignature(requireSignatoryAccount(actor.organizationId()));
        byte[] signedPdf = I983WorkflowService.stampSignature(pdf, signature.image(), List.of(new I983SignaturePlacement(
                placement.getPage(), placement.getX(), placement.getY(), placement.getWidth(), placement.getHeight())));
        String fileName = hasText(name) ? name : "document.pdf";
        StoredDocument stored = documents.store(new DocumentUploadRequest(actor.organizationId(), request.getRequestedByUserId(),
                DocumentType.REQUESTED_DOCUMENT, ENTITY_TYPE, request.getId(), "signed-v" + version + "-" + fileName,
                "application/pdf", signedPdf, version, actor.userId()));
        return new SignedCopy(stored, signature, placement.getPage(), version);
    }

    private record Signatory(String userId, String name) {}

    /** Makes the signed copy the request's current document - the one HR sends - and records who signed it. */
    private void recordSignedCopy(DocumentRequest request, AuthenticatedMyHrUser actor, SignedCopy signed, String unsignedId, LocalDateTime at) {
        List<DocumentRequestVersion> versions = new ArrayList<>(orEmpty(request.getDocumentVersions()));
        versions.add(DocumentRequestVersion.builder().documentId(signed.document().getId()).version(signed.version())
                .kind(DocumentRequestVersion.SIGNED).fileName(signed.document().getFileName()).uploadedByUserId(actor.userId())
                .uploadedByRole(actor.role().name()).uploadedAt(at).basedOnDocumentId(unsignedId)
                .signatureSource(signed.signature().source()).signatoryName(signed.signature().signatoryName())
                .signatoryTitle(signed.signature().signatoryTitle()).signatureEffectiveDate(signed.signature().effectiveDate())
                .signaturePages(List.of(signed.page())).build());
        request.setDocumentVersions(versions);
        request.setCurrentDocumentId(signed.document().getId());
        addHistory(request, actor, "SIGNATURE_APPLIED", request.getStatus(), request.getStatus(), signed.document().getId(),
                "Applied the authorized signature of " + (hasText(signed.signature().signatoryName()) ? signed.signature().signatoryName() : "the organization")
                        + " (" + signed.signature().source() + ")", at);
    }

    private record SignedCopy(StoredDocument document, ActiveSignature signature, int page, int version) {}

    private boolean claim(DocumentRequest request, Set<DocumentRequestStatus> from, DocumentRequestStatus to, LocalDateTime at) {
        return requests.transitionOfferLetterStatus(request.getId(), request.getOrganizationId(),
                from.stream().map(Enum::name).toList(), to.name(), at) == 1;
    }

    private void requireFile(DocumentRequest request, byte[] content) {
        if (content == null || content.length == 0) throw new BusinessException("The document file is required");
        if (isOfferLetter(request) && (content.length < 5 || !"%PDF-".equals(new String(content, 0, 5, StandardCharsets.US_ASCII))))
            throw new BusinessException("The offer letter PDF is required");
    }

    private static String safeContentType(String contentType) {
        return hasText(contentType) && contentType.matches("[\\w.+-]+/[\\w.+-]+") ? contentType : "application/octet-stream";
    }

    private static String safeFileName(String fileName) {
        String name = hasText(fileName) ? fileName.replace('\\', '/') : "document";
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[^A-Za-z0-9._ -]", "_").trim();
        return name.isEmpty() ? "document" : name;
    }

    private int versionNumber(DocumentRequest request) {
        return orEmpty(request.getDocumentVersions()).stream().mapToInt(DocumentRequestVersion::getVersion).max().orElse(0) + 1;
    }

    private void addHistory(DocumentRequest request, AuthenticatedMyHrUser actor, String action, DocumentRequestStatus from,
                            DocumentRequestStatus to, String documentId, String detail, LocalDateTime at) {
        List<DocumentRequestHistoryEntry> history = new ArrayList<>(orEmpty(request.getHistory()));
        history.add(DocumentRequestHistoryEntry.builder().action(action).actorUserId(actor.userId())
                .actorName(displayName(actor.userId(), actor.loginId())).actorRole(actor.role().name())
                .fromStatus(from).toStatus(to).documentId(documentId).detail(detail).at(at).build());
        request.setHistory(history);
    }

    private void recordAudit(AuthenticatedMyHrUser actor, AuditActionType type, DocumentRequest request, String description,
                             DocumentRequestStatus from, DocumentRequestStatus to, String reason) {
        try {
            Map<String, Object> updated = new LinkedHashMap<>();
            updated.put("status", to.name());
            updated.put("approvalStatus", approvalStatus(request));
            if (request.getOfferLetterApproval() != null) updated.put("employeeName", request.getOfferLetterApproval().getEmployeeName());
            if (reason != null) updated.put("reason", reason);
            audit.record(actor, type, ENTITY_TYPE, request.getId(), request.getRequestTitle(), description,
                    Map.of("status", from.name()), updated);
        } catch (RuntimeException failure) {
            log.error("Offer letter approval audit failed [requestId={}, action={}, reason={}]", request.getId(), type, failure.getMessage());
        }
    }

    // ------------------------------------------------------------------ notifications

    private void notifyAdminsApprovalRequired(AuthenticatedMyHrUser actor, DocumentRequest request) {
        OfferLetterApprovalDetails details = request.getOfferLetterApproval();
        boolean offer = isOfferLetter(request);
        String link = "/myhr/admin/offer-letter-approvals/" + request.getId();
        String title = offer ? "Offer Letter Approval Required" : "Document Approval Required";
        String message = offer
                ? "HR has submitted an offer letter for " + details.getEmployeeName() + " for the position of "
                        + details.getDesignation() + ". Please review and approve or reject the offer letter."
                : "HR has submitted the " + label(request, false) + " for " + details.getEmployeeName()
                        + ". Please review and approve or reject the document.";
        String subject = offer ? title + " – " + oneLine(details.getEmployeeName())
                : title + " – " + oneLine(details.getEmployeeName()) + " – " + oneLine(label(request, false));
        String emailMessage = offer
                ? "HR has submitted an offer letter. Admin approval is required before it can be sent to the employee. "
                        + "Please review the offer letter in WayPoint and approve or reject it."
                : "HR has submitted a document. Admin approval is required before it can be sent to the employee. "
                        + "Please review the document in WayPoint and approve or reject it.";
        // A document signed on approval goes to the company admin account only - no other Admin can approve it.
        String signatoryId = details.getAuthorizedSignature() == null ? null
                : findSignatory(actor.organizationId()).map(Signatory::userId).orElse(null);
        List<User> admins = users.findByOrganizationIdAndAccountStatusAndRoleIn(actor.organizationId(), AccountStatus.ACTIVE, ADMINS);
        for (User admin : admins) {
            if (admin.getId().equals(actor.userId())) continue;
            if (details.getAuthorizedSignature() != null && !admin.getId().equals(signatoryId)) continue;
            notifySafely(() -> notifications.notify(NotificationEvent.builder()
                    .organizationId(actor.organizationId()).recipientUserId(admin.getId())
                    .type(NotificationType.OFFER_LETTER_APPROVAL_REQUESTED).title(title).message(message)
                    .relatedEntityId(request.getId()).actionPath(link)
                    .email(true).emailSubject(subject).emailMessage(emailMessage)
                    .detailRows(approvalRows(request, details)).actionLabel(offer ? "Review Offer Letter" : "Review Document").build()));
        }
    }

    private void notifySubmitter(AuthenticatedMyHrUser actor, DocumentRequest request, boolean approved, String reason) {
        OfferLetterApprovalDetails details = request.getOfferLetterApproval();
        String hrId = details.getSubmittedByUserId();
        if (hrId == null || hrId.equals(actor.userId())) return;
        boolean offer = isOfferLetter(request);
        String link = offer ? "/myhr/hr/document-requests/" + request.getId() + "/offer-letter"
                : "/myhr/hr/document-requests/" + request.getId();
        String title = offer ? (approved ? "Offer Letter Approved" : "Offer Letter Rejected") : (approved ? "Document Approved" : "Document Rejected");
        String what = offer ? "offer letter" : "document (" + label(request, false) + ")";
        String approver = approverName(actor.organizationId());
        String message = approved
                ? "The " + (offer ? "offer letter" : label(request, false)) + " for " + details.getEmployeeName()
                        + " has been approved by the " + approver + ". You can now send it to the employee."
                : "The " + (offer ? "offer letter" : label(request, false)) + " for " + details.getEmployeeName()
                        + " was rejected by the " + approver + ".\n\nReason:\n" + reason;
        List<EmailDetailRow> rows = new ArrayList<>(approvalRows(request, details));
        if (!approved) rows.add(new EmailDetailRow("Reason", reason));
        notifySafely(() -> notifications.notify(NotificationEvent.builder()
                .organizationId(actor.organizationId()).recipientUserId(hrId)
                .type(approved ? NotificationType.OFFER_LETTER_APPROVED : NotificationType.OFFER_LETTER_REJECTED)
                .title(title).message(message).relatedEntityId(request.getId()).actionPath(link)
                .email(true).emailSubject(title + " – " + oneLine(details.getEmployeeName()))
                .emailMessage(approved
                        ? "The " + what + " has been approved by the " + approver + ". You can now send it to the employee."
                        : "The " + what + " was rejected by the " + approver + ". Please correct it and submit it for approval again.")
                .detailRows(rows)
                .actionLabel(approved ? (offer ? "Send Offer Letter" : "Open Document Request") : (offer ? "Review Offer Letter" : "Open Document Request"))
                .build()));
    }

    private List<EmailDetailRow> approvalRows(DocumentRequest request, OfferLetterApprovalDetails details) {
        List<EmailDetailRow> rows = new ArrayList<>();
        rows.add(new EmailDetailRow("Employee", details.getEmployeeName()));
        if (!isOfferLetter(request)) rows.add(new EmailDetailRow("Document", label(request, true)));
        if (hasText(details.getDesignation())) rows.add(new EmailDetailRow("Designation", details.getDesignation()));
        if (hasText(details.getOfferType())) rows.add(new EmailDetailRow("Offer Type", details.getOfferType()));
        if (hasText(details.getEmploymentCategory())) rows.add(new EmailDetailRow("Employment Category", details.getEmploymentCategory()));
        if (hasText(details.getJoiningDate())) rows.add(new EmailDetailRow("Joining Date", details.getJoiningDate()));
        rows.add(new EmailDetailRow("Submitted by", details.getSubmittedByName()));
        return rows;
    }

    /** Notifications and emails are best effort: a failure never undoes or reports a failed approval step. */
    private void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException failure) {
            log.warn("Offer letter approval notification failed [exceptionType={}, reason={}]", failure.getClass().getSimpleName(), failure.getMessage());
        }
    }

    private void deleteQuietly(String documentId) {
        try {
            documents.delete(documentId);
        } catch (RuntimeException cleanup) {
            log.warn("Could not remove an unreferenced offer letter approval document [documentId={}]", documentId);
        }
    }

    private String displayName(String userId, String fallback) {
        try {
            EmployeeProfile profile = profiles.findByUserId(userId).orElse(null);
            if (profile != null) {
                String full = ((profile.getFirstName() == null ? "" : profile.getFirstName()) + " "
                        + (profile.getLastName() == null ? "" : profile.getLastName())).trim();
                if (!full.isBlank()) return full;
                if (hasText(profile.getName())) return profile.getName().trim();
            }
            if (fallback == null) return users.findById(userId).map(User::getLoginId).orElse(null);
        } catch (RuntimeException ignored) {
            // fall through to the login id
        }
        return fallback;
    }

    private static String oneLine(String value) {
        return value == null ? "" : value.replaceAll("[\\r\\n\\p{Cntrl}]+", " ").trim();
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The approver as HR sees it: "<Organization> Admin", or "Company Admin" when the organization has no name. */
    private String approverName(String organizationId) {
        String name = organizationId == null ? null
                : organizations.findById(organizationId).map(Organization::getOrganizationName).orElse(null);
        return hasText(name) ? name.trim() + " Admin" : "Company Admin";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
