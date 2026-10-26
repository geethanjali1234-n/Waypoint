package com.waypoint.service;

import com.lowagie.text.Image;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.PdfStamper;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.PageSize;
import com.waypoint.dto.myhr.*;
import com.waypoint.entity.*;
import com.waypoint.enums.*;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.*;
import com.waypoint.security.MyHrAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DocumentRequestService {
    private static final Logger log = LoggerFactory.getLogger(DocumentRequestService.class);
    private static final Set<UserRole> ALL_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.EMPLOYEE);
    private static final Set<UserRole> MANAGER_ROLES = Set.of(UserRole.COMPANY_ADMIN, UserRole.HR);
    private static final Set<String> CATEGORIES = Set.of(
            "PRE_EMPLOYMENT_ONBOARDING", "TAX_PAYROLL", "BENEFITS_ENROLLMENT",
            "IMMIGRATION_RELATED", "EXIT_OFFBOARDING");
    // Shown for offer letters and for every other document HR delivers (the name is kept for the offer-letter callers).
    static final String OFFER_LETTER_NEEDS_APPROVAL = "The document must be approved by the Company Admin before it can be sent to the employee.";
    private static final Set<String> PRIORITIES = Set.of("NORMAL", "HIGH", "URGENT");
    private static final Set<String> MANAGER_DOCUMENTS = Set.of(
            "Government-issued photo ID (driver's license, passport, state ID)",
            "Social Security card (or proof of SSN)", "Form I-9 supporting documents (List A, B, or C)",
            "Proof of work authorization (H-1B approval notice, EAD card, etc.)",
            "Signed offer letter / employment agreement", "Educational certificates / degrees (transcripts, diplomas)",
            "Professional license / certification copies", "Background check consent form",
            "Drug test consent / results", "Previous employment verification / reference letters",
            "Form W-4 (federal tax withholding)", "State tax withholding form",
            "Direct deposit authorization form + voided check / bank details", "Form W-9 (independent contractor)",
            "Health insurance enrollment form", "Dependent information (marriage certificate, birth certificates)",
            "Beneficiary designation forms (401(k), life insurance)", "COBRA election forms (incoming)",
            "FSA / HSA enrollment forms", "I-983", "Visa / EAD renewal documents",
            "Passport copies (for visa processing)", "Form I-9 reverification documents",
            "Signed separation agreement", "Company property return acknowledgment (laptop, badge, etc.)",
            "Exit interview form", "COBRA election form (outgoing)", "Other document");
    private static final String RELATED_ENTITY_TYPE = "DOCUMENT_REQUEST";
    private static final String EMPLOYEE_SIGNATURE_FIELD = "waypoint_employee_signature";
    private static final DateTimeFormatter REQUIRED_BY_FORMAT = DateTimeFormatter.ofPattern("MM-dd-uuuu");

    private final DocumentRequestRepository requestRepository;
    private final ReportingAssignmentRepository assignmentRepository;
    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final ObjectProvider<DocumentService> documentServiceProvider;
    private final AuditService auditService;
    private final NotificationOrchestrator notificationOrchestrator;
    private final MyHrDocumentFulfillmentEmailService fulfillmentEmailService;
    private final MongoTemplate mongoTemplate;

    public List<RequestableEmployeeResponse> requestableEmployees(String actorUserId) {
        var actor = authenticatedUserService.require(actorUserId, MANAGER_ROLES, false);
        List<User> users;
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            users = userRepository.findByOrganizationIdAndAccountStatusAndRoleIn(
                    actor.organizationId(), AccountStatus.ACTIVE, Set.of(UserRole.HR, UserRole.EMPLOYEE));
        } else {
            Set<String> directReports = directReportIds(actor.organizationId(), actor.userId());
            users = directReports.isEmpty() ? List.of() : userRepository.findByIdIn(directReports).stream()
                    .filter(user -> actor.organizationId().equals(user.getOrganizationId()))
                    .filter(user -> user.getRole() == UserRole.EMPLOYEE)
                    .filter(user -> user.getAccountStatus() == AccountStatus.ACTIVE).toList();
        }
        Map<String, EmployeeProfile> profiles = profiles(actor.organizationId());
        return users.stream().map(user -> requestable(user, profiles.get(user.getId()))).toList();
    }

    public DocumentRequestPageResponse list(String actorUserId, int page, int size, String status,
                                             String priority, String category, String search, String workspace) {
        var actor = authenticatedUserService.require(actorUserId, ALL_ROLES, false);
        int boundedPage = Math.max(page, 0);
        int boundedSize = Math.min(Math.max(size, 1), 50);
        Criteria organization = Criteria.where("organizationId").is(actor.organizationId());
        List<Criteria> constraints = new ArrayList<>();
        if (actor.role() == UserRole.EMPLOYEE) {
            constraints.add(new Criteria().orOperator(Criteria.where("requestedByUserId").is(actor.userId()),
                    Criteria.where("requestedForUserId").is(actor.userId())));
        } else if (actor.role() == UserRole.HR) {
            Set<String> visibleIds = new HashSet<>(directReportIds(actor.organizationId(), actor.userId()));
            visibleIds.add(actor.userId());
            constraints.add(new Criteria().orOperator(Criteria.where("requestedByUserId").in(visibleIds),
                    Criteria.where("requestedForUserId").in(visibleIds)));
        }
        if ("mine".equals(workspace) && actor.role() == UserRole.HR) {
            constraints.add(Criteria.where("requestedByUserId").is(actor.userId()));
            constraints.add(Criteria.where("requestDirection").is("TO_MANAGER"));
        } else if ("employees".equals(workspace) && actor.role() == UserRole.HR) {
            constraints.add(new Criteria().orOperator(
                    new Criteria().andOperator(Criteria.where("requestedByUserId").is(actor.userId()), Criteria.where("requestDirection").is("TO_EMPLOYEE")),
                    new Criteria().andOperator(Criteria.where("requestedForUserId").is(actor.userId()), Criteria.where("requestDirection").is("TO_MANAGER"))));
        } else if ("received".equals(workspace) && actor.role() == UserRole.HR) {
            // HR's default list: the requests assigned to this HR to fulfil.
            constraints.add(Criteria.where("requestedForUserId").is(actor.userId()));
        } else if ("requested-employees".equals(workspace) && actor.role() == UserRole.HR) {
            // "My requests > From employees": only the documents this HR asked their employees to provide.
            constraints.add(Criteria.where("requestedByUserId").is(actor.userId()));
            constraints.add(Criteria.where("requestDirection").is("TO_EMPLOYEE"));
        }
        if (status != null && !status.isBlank() && !"ALL".equals(status)) {
            if ("AWAITING_SIGNATURE".equals(status)) {
                constraints.add(new Criteria().andOperator(Criteria.where("documentType").is(RequestedDocumentType.OFFER_LETTER),
                        Criteria.where("status").is(DocumentRequestStatus.FULFILLED)));
            } else {
                try { constraints.add(Criteria.where("status").is(DocumentRequestStatus.valueOf(status))); }
                catch (IllegalArgumentException ignored) { throw new BusinessException("Unsupported document request status"); }
            }
        }
        if (priority != null && !priority.isBlank() && !"ALL".equals(priority)) constraints.add(Criteria.where("priority").is(priority));
        if (category != null && !category.isBlank() && !"ALL".equals(category)) constraints.add(Criteria.where("category").is(category));
        if (search != null && !search.isBlank()) constraints.add(Criteria.where("requestTitle").regex(java.util.regex.Pattern.quote(search.trim()), "i"));
        Criteria criteria = constraints.isEmpty() ? organization
                : new Criteria().andOperator(organization, new Criteria().andOperator(constraints.toArray(Criteria[]::new)));
        // A Company Admin's document worklist is also their approval inbox. Sort it by
        // the latest workflow activity so an older request that HR has just submitted
        // for approval is not hidden on a later page behind newly-created requests.
        Sort ordering = actor.role() == UserRole.COMPANY_ADMIN
                ? Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("requestedAt"))
                : Sort.by(Sort.Direction.DESC, "requestedAt");
        Query pageQuery = new Query(criteria).with(PageRequest.of(boundedPage, boundedSize, ordering));
        long total = mongoTemplate.count(Query.of(pageQuery).limit(-1).skip(-1), DocumentRequest.class);
        List<DocumentRequest> requests = mongoTemplate.find(pageQuery, DocumentRequest.class);
        return new DocumentRequestPageResponse(responses(requests, actor.organizationId()), boundedPage,
                boundedSize, total, (int) Math.ceil((double) total / boundedSize));
    }

    public List<DocumentRequestResponse> list(String actorUserId) {
        long startedAt = System.nanoTime();
        var actor = authenticatedUserService.require(actorUserId, ALL_ROLES, false);
        List<DocumentRequest> requests = requestRepository.findByOrganizationIdOrderByRequestedAtDesc(actor.organizationId());
        if (actor.role() == UserRole.HR) {
            Set<String> reports = directReportIds(actor.organizationId(), actor.userId());
            requests = requests.stream().filter(item -> involves(item, actor.userId())
                    || reports.contains(item.getRequestedByUserId()) || reports.contains(item.getRequestedForUserId())).toList();
        } else if (actor.role() == UserRole.EMPLOYEE) {
            requests = requests.stream().filter(item -> involves(item, actor.userId())).toList();
        }
        List<DocumentRequestResponse> result = responses(requests, actor.organizationId());
        log.info("Document request list [role={}, requests={}, ms={}]", actor.role(), result.size(), (System.nanoTime() - startedAt) / 1_000_000);
        return result;
    }

    public DocumentRequestResponse get(String actorUserId, String requestId) {
        var actor = authenticatedUserService.require(actorUserId, ALL_ROLES, false);
        DocumentRequest request = requireVisible(actor, requestId);
        return responses(List.of(request), actor.organizationId()).getFirst();
    }

    public DocumentRequestResponse createManagerRequest(String actorUserId, DocumentRequestCreateRequest input) {
        var actor = authenticatedUserService.require(actorUserId, MANAGER_ROLES, false);
        validateManagerInput(input);
        User target = requireUserInOrganization(input.requestedForUserId(), actor.organizationId());
        authorizeManagerTarget(actor, target);
        LinkedHashSet<String> documents = input.documents().stream().map(String::trim)
                .filter(value -> !value.isBlank()).collect(Collectors.toCollection(LinkedHashSet::new));
        if (documents.isEmpty()) throw new BusinessException("At least one document is required");
        if (documents.stream().anyMatch(document -> !MANAGER_DOCUMENTS.contains(document)
                && !(document.startsWith("Other document: ") && document.length() > "Other document: ".length())))
            throw new BusinessException("Unsupported document selection");
        LocalDateTime now = LocalDateTime.now();
        DocumentRequest saved = requestRepository.save(DocumentRequest.builder()
                .organizationId(actor.organizationId()).requestedByUserId(actor.userId())
                .requestedForUserId(target.getId()).documentType(RequestedDocumentType.OTHER)
                .requestTitle(documents.getFirst()).requestedDocuments(List.copyOf(documents))
                .requestDescription(input.instructions() == null ? "" : input.instructions().trim()).category(input.category().trim())
                .priority(input.priority().trim()).requestDirection("TO_EMPLOYEE").requiredBy(input.requiredBy())
                .status(DocumentRequestStatus.PENDING).requestedAt(now).createdAt(now).updatedAt(now).build());
        audit(actor, AuditActionType.DOCUMENT_REQUEST_CREATED, saved, "Document request created");
        notifySafely(() -> notifyDocumentRequested(actor, saved, target));
        return responses(List.of(saved), actor.organizationId()).getFirst();
    }

    public DocumentRequestResponse createSelfServiceRequest(
            String actorUserId, EmployeeDocumentRequestCreateRequest input, List<MultipartFile> attachments) {
        var actor = authenticatedUserService.require(actorUserId, Set.of(UserRole.HR, UserRole.EMPLOYEE), false);
        ReportingAssignment assignment = assignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)
                .orElseThrow(() -> new BusinessException("No active primary reporting manager is assigned"));
        User manager = requireUserInOrganization(assignment.getReportingManagerUserId(), actor.organizationId());
        if (!MANAGER_ROLES.contains(manager.getRole()) || manager.getAccountStatus() != AccountStatus.ACTIVE
                || manager.getId().equals(actor.userId())) {
            throw new BusinessException("An active HR or Company Admin reporting manager is required");
        }
        RequestedDocumentType type = parseDocumentType(input.documentType());
        if (attachments != null && attachments.size() > 10) throw new BusinessException("At most 10 attachments are allowed");
        LocalDateTime now = LocalDateTime.now();
        DocumentRequest saved = requestRepository.save(DocumentRequest.builder()
                .organizationId(actor.organizationId()).requestedByUserId(actor.userId())
                .requestedForUserId(manager.getId()).documentType(type).requestTitle(input.documentTitle().trim())
                .requestDescription(input.reason().trim()).category("EMPLOYMENT").priority("NORMAL")
                .requestDirection("TO_MANAGER").requiredBy(input.requiredBy()).status(DocumentRequestStatus.PENDING)
                .requestedAt(now).createdAt(now).updatedAt(now).build());
        List<String> storedIds = new ArrayList<>();
        try {
            for (MultipartFile file : attachments == null ? List.<MultipartFile>of() : attachments) {
                if (file == null || file.isEmpty()) continue;
                StoredDocument stored = documents().store(new DocumentUploadRequest(
                        actor.organizationId(), actor.userId(), DocumentType.REQUESTED_DOCUMENT,
                        RELATED_ENTITY_TYPE, saved.getId(), file.getOriginalFilename(), safeContentType(file.getContentType()),
                        file.getBytes(), 1, actor.userId()));
                storedIds.add(stored.getId());
            }
            if (!storedIds.isEmpty()) {
                saved.setAttachmentDocumentIds(List.copyOf(storedIds));
                saved.setUpdatedAt(LocalDateTime.now());
                saved = requestRepository.save(saved);
            }
        } catch (IOException | RuntimeException failure) {
            cleanup(storedIds, failure);
            try { requestRepository.deleteById(saved.getId()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            log.warn("Supporting attachment could not be stored for document request {}", saved.getId(), failure);
            if (failure instanceof IOException) throw new BusinessException("Supporting attachment could not be read");
            throw new BusinessException("Supporting attachment could not be stored. Please try again or choose a different file.");
        }
        audit(actor, type == RequestedDocumentType.OFFER_LETTER
                ? AuditActionType.OFFER_REQUEST_CREATED : AuditActionType.DOCUMENT_REQUEST_CREATED,
                saved, type == RequestedDocumentType.OFFER_LETTER
                        ? "Offer letter request created" : "Self-service document request created");
        DocumentRequest notificationRequest = saved;
        notifySafely(() -> notifyEmployeeDocumentRequested(actor, notificationRequest, manager));
        return responses(List.of(saved), actor.organizationId()).getFirst();
    }

    public DocumentFulfillmentResponse fulfill(String actorUserId, String requestId,
                                            String fileName, String contentType, byte[] content, DocumentFulfillmentEmailRequest email) {
        var actor = authenticatedUserService.require(actorUserId, ALL_ROLES, false);
        DocumentRequest request = requireOrganizationRequest(actor, requestId);
        requireAssignedRecipient(actor, request);
        requirePending(request);
        // Offer letters reach the employee only through the Admin approval workflow, and so does every other
        // document HR delivers. A Company Admin is the approver, so an Admin delivers directly.
        if (request.getDocumentType() == RequestedDocumentType.OFFER_LETTER)
            throw new BusinessException(OFFER_LETTER_NEEDS_APPROVAL);
        if (actor.role() == UserRole.HR && OfferLetterApprovalService.requiresApproval(request))
            throw new BusinessException(OFFER_LETTER_NEEDS_APPROVAL);
        StoredDocument document = documents().store(new DocumentUploadRequest(
                actor.organizationId(), request.getRequestedByUserId(), DocumentType.REQUESTED_DOCUMENT,
                RELATED_ENTITY_TYPE, request.getId(), fileName, safeContentType(contentType), content, 1, actor.userId()));
        try {
            LocalDateTime now = LocalDateTime.now();
            request.setFulfilledByUserId(actor.userId());
            request.setFulfilledDocumentId(document.getId());
            request.setStatus(DocumentRequestStatus.FULFILLED);
            request.setFulfilledAt(now);
            request.setUpdatedAt(now);
            DocumentRequest saved = requestRepository.save(request);
            audit(actor, AuditActionType.DOCUMENT_REQUEST_FULFILLED, saved, "Document request fulfilled");
            notifySafely(() -> notifyDocumentRequestFulfilled(actor, saved));
            DocumentEmailDeliveryResponse delivery = "TO_MANAGER".equals(saved.getRequestDirection())
                    ? fulfillmentEmailService.sendWithMessage(saved, new EmailAttachment(document.getFileName(), content,
                    safeContentType(contentType)), email)
                    : new DocumentEmailDeliveryResponse("SKIPPED", null, "This request does not send a document to an employee.");
            return new DocumentFulfillmentResponse(responses(List.of(saved), actor.organizationId()).getFirst(), delivery);
        } catch (RuntimeException failure) {
            try { documents().delete(document.getId()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Delivers an Admin-approved document (an offer letter or any other document HR sends) to the employee in
     * WayPoint (for an offer letter this precedes the existing email). The delivered document is a copy of the exact PDF the Admin
     * approved - HR cannot swap in a different file after approval - and anything that is not
     * APPROVED is refused here, on the server, regardless of what the UI shows.
     */
    public DocumentFulfillmentResponse deliverApprovedOfferLetter(String actorUserId, String requestId, String recipientEmail) {
        return deliverApprovedDocument(actorUserId, requestId, null, recipientEmail);
    }

    /** Delivers an approved document with the reviewed recipient, subject, and message. */
    public DocumentFulfillmentResponse deliverApprovedDocument(String actorUserId, String requestId,
                                                               DocumentFulfillmentEmailRequest email) {
        return deliverApprovedDocument(actorUserId, requestId, email, null);
    }

    private DocumentFulfillmentResponse deliverApprovedDocument(String actorUserId, String requestId,
                                                                DocumentFulfillmentEmailRequest email, String recipientEmail) {
        var actor = authenticatedUserService.require(actorUserId, MANAGER_ROLES, false);
        DocumentRequest request = requireOrganizationRequest(actor, requestId);
        requireAssignedRecipient(actor, request);
        if (!OfferLetterApprovalService.requiresApproval(request)) throw new BusinessException("This document does not need Admin approval");
        if (request.getStatus() == DocumentRequestStatus.FULFILLED && OfferLetterApprovalService.isApproved(request))
            return new DocumentFulfillmentResponse(responses(List.of(request), actor.organizationId()).getFirst(),
                    new DocumentEmailDeliveryResponse("SKIPPED", null, "This document was already sent."));
        if (request.getStatus() != DocumentRequestStatus.APPROVED || !OfferLetterApprovalService.isApproved(request)
                || request.getCurrentDocumentId() == null)
            throw new BusinessException(OFFER_LETTER_NEEDS_APPROVAL);
        DocumentService.DocumentContent approved = documents().getStoredContent(request.getCurrentDocumentId());
        validateDocumentLink(request, approved.metadata());
        OfferLetterApprovalDetails details = request.getOfferLetterApproval();
        boolean offer = request.getDocumentType() == RequestedDocumentType.OFFER_LETTER;
        String deliveredName = offer ? "offer-letter.pdf" : (details.getFileName() == null ? "document" : details.getFileName());
        String deliveredType = offer ? "application/pdf" : safeContentType(details.getContentType());
        StoredDocument document = documents().store(new DocumentUploadRequest(
                actor.organizationId(), request.getRequestedByUserId(), DocumentType.REQUESTED_DOCUMENT,
                RELATED_ENTITY_TYPE, request.getId(), deliveredName, deliveredType, approved.content(), 1, actor.userId()));
        LocalDateTime now = LocalDateTime.now();
        if (requestRepository.transitionOfferLetterStatus(request.getId(), actor.organizationId(),
                List.of(DocumentRequestStatus.APPROVED.name()), DocumentRequestStatus.FULFILLED.name(), now) != 1) {
            try { documents().delete(document.getId()); } catch (RuntimeException cleanup) { log.warn("Could not remove an unreferenced offer letter copy"); }
            throw new BusinessException("This offer letter was just changed. Refresh the page and try again.");
        }
        try {
            request.setFulfilledByUserId(actor.userId());
            request.setFulfilledDocumentId(document.getId());
            request.setStatus(DocumentRequestStatus.FULFILLED);
            request.setFulfilledAt(now);
            request.setUpdatedAt(now);
            DocumentRequest saved = requestRepository.save(request);
            audit(actor, AuditActionType.DOCUMENT_REQUEST_FULFILLED, saved, "Approved document delivered to the employee");
            notifySafely(() -> notifyDocumentRequestFulfilled(actor, saved));
            // Like a direct fulfillment, email the document to the employee (best effort - it never undoes the delivery).
            // An offer letter is emailed by its own, separate step.
            DocumentEmailDeliveryResponse delivery = offer
                    ? new DocumentEmailDeliveryResponse("SKIPPED", null, "Offer letters are emailed separately.")
                    : email == null
                    ? fulfillmentEmailService.send(saved, new EmailAttachment(deliveredName, approved.content(), deliveredType), recipientEmail)
                    : fulfillmentEmailService.sendWithMessage(saved, new EmailAttachment(deliveredName, approved.content(), deliveredType), email);
            return new DocumentFulfillmentResponse(responses(List.of(saved), actor.organizationId()).getFirst(), delivery);
        } catch (RuntimeException failure) {
            requestRepository.transitionOfferLetterStatus(request.getId(), actor.organizationId(),
                    List.of(DocumentRequestStatus.FULFILLED.name()), DocumentRequestStatus.APPROVED.name(), LocalDateTime.now());
            try { documents().delete(document.getId()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public DocumentFulfillmentResponse fulfill(String actorUserId, String requestId,
                                                String fileName, String contentType, byte[] content) {
        return fulfill(actorUserId, requestId, fileName, contentType, content, (DocumentFulfillmentEmailRequest) null);
    }

    public DocumentFulfillmentEmailDraftResponse fulfillmentEmailDraft(String actorUserId, String requestId, String recipientEmail) {
        var actor = authenticatedUserService.require(actorUserId, MANAGER_ROLES, false);
        DocumentRequest request = requireOrganizationRequest(actor, requestId);
        requireAssignedRecipient(actor, request);
        return fulfillmentEmailService.draft(request, recipientEmail);
    }

    public DocumentEmailDeliveryResponse resendFulfillmentEmail(String actorUserId, String requestId, DocumentFulfillmentEmailRequest email) {
        var actor = authenticatedUserService.require(actorUserId, MANAGER_ROLES, false);
        DocumentRequest request = requireOrganizationRequest(actor, requestId);
        requireAssignedRecipient(actor, request);
        if (!"TO_MANAGER".equals(request.getRequestDirection()) || request.getFulfilledDocumentId() == null)
            throw new BusinessException("A completed employee document is required before it can be emailed.");
        DocumentService.DocumentContent content = documents().getContent(request.getFulfilledDocumentId());
        return fulfillmentEmailService.sendWithMessage(request, new EmailAttachment(content.metadata().getFileName(), content.content(),
                content.contentType()), email);
    }

    public DocumentRequestResponse reject(String actorUserId, String requestId, String reason) {
        var actor = authenticatedUserService.require(actorUserId, MANAGER_ROLES, false);
        DocumentRequest request = requireOrganizationRequest(actor, requestId);
        requireAssignedRecipient(actor, request);
        requirePending(request);
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isBlank()) throw new BusinessException("Rejection reason is required");
        if (normalized.length() > 1000) throw new BusinessException("Rejection reason must not exceed 1000 characters");
        LocalDateTime now = LocalDateTime.now();
        request.setStatus(DocumentRequestStatus.REJECTED);
        request.setRejectionReason(normalized);
        request.setRejectedByUserId(actor.userId());
        request.setRejectedAt(now);
        request.setUpdatedAt(now);
        DocumentRequest saved = requestRepository.save(request);
        audit(actor, AuditActionType.DOCUMENT_REQUEST_REJECTED, saved, "Document request rejected");
        notifySafely(() -> notifyDocumentRequestRejected(actor, saved));
        return responses(List.of(saved), actor.organizationId()).getFirst();
    }

    /** Persists exactly one employee response: their electronic signature image or an externally signed file. */
    public DocumentRequestResponse submitSignedResponse(String actorUserId, String requestId,
            String signatureFileName, String signatureContentType, byte[] signatureContent,
            String signedFileName, String signedContentType, byte[] signedContent) {
        var actor = authenticatedUserService.require(actorUserId, Set.of(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN), false);
        DocumentRequest request = requireOrganizationRequest(actor, requestId);
        if (!actor.userId().equals(request.getRequestedByUserId())) throw new ForbiddenException("Only the requesting user can submit a signed response");
        boolean hasSignature = signatureContent != null && signatureContent.length > 0;
        boolean hasSignedDocument = signedContent != null && signedContent.length > 0;
        if (hasSignature == hasSignedDocument) throw new BusinessException("Submit either a signature or a signed document");
        if (request.getStatus() != DocumentRequestStatus.FULFILLED || !"TO_MANAGER".equals(request.getRequestDirection())
                || request.getFulfilledDocumentId() == null) throw new BusinessException("A completed document must be available before it can be signed");
        if (request.getDocumentType() == RequestedDocumentType.OFFER_LETTER && !hasSignature)
            throw new BusinessException("Sign the original offer letter in Waypoint so both signatures remain in the same document");
        if (request.getEmployeeSignatureDocumentId() != null || request.getSignedResponseDocumentId() != null) throw new BusinessException("A signed response has already been submitted");
        StoredDocument responseDocument;
        LocalDateTime now = LocalDateTime.now();
        if (hasSignature) {
            if (signatureContent.length > 2 * 1024 * 1024 || !Set.of("image/png", "image/jpeg").contains(signatureContentType))
                throw new BusinessException("Use a PNG or JPEG signature no larger than 2 MB");
            RequestDocumentContent original = getDocumentContent(actorUserId, requestId, request.getFulfilledDocumentId());
            if (!"application/pdf".equalsIgnoreCase(original.contentType())) throw new BusinessException("The completed document must be a PDF to apply an electronic signature");
            EmployeeProfile profile = employeeProfileRepository.findByUserId(actor.userId()).orElse(null);
            User signer = requireUserInOrganization(actor.userId(), actor.organizationId());
            byte[] signedPdf = applySignature(original.content(), signatureContent, displayName(signer, profile), requestId, now);
            responseDocument = documents().store(new DocumentUploadRequest(actor.organizationId(), request.getRequestedByUserId(), DocumentType.REQUESTED_DOCUMENT, RELATED_ENTITY_TYPE, request.getId(), "signed-" + original.fileName().replaceAll("(?i)\\.pdf$", "") + ".pdf", "application/pdf", signedPdf, 1, actor.userId()));
        } else {
            String fileName = signedFileName == null || signedFileName.isBlank() ? "signed-document" : signedFileName;
            responseDocument = documents().store(new DocumentUploadRequest(actor.organizationId(), request.getRequestedByUserId(), DocumentType.REQUESTED_DOCUMENT, RELATED_ENTITY_TYPE, request.getId(), fileName, safeContentType(signedContentType), signedContent, 1, actor.userId()));
        }
        try {
            if (requestRepository.recordSignedResponse(requestId, actor.organizationId(), request.getFulfilledDocumentId(), responseDocument.getId(), now) != 1)
                throw new BusinessException("This request has changed or a signed response has already been submitted. Refresh the request.");
            request.setSignedResponseDocumentId(responseDocument.getId());
            request.setSignatureSubmittedAt(now); request.setUpdatedAt(now);
        } catch (RuntimeException failure) {
            try { documents().delete(responseDocument.getId()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        audit(actor, AuditActionType.OFFER_SIGNED_DOCUMENT_UPLOADED, request, "Signed document returned by requester");
        notifySafely(() -> {
            User recipient = requireUserInOrganization(request.getRequestedForUserId(), actor.organizationId());
notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId()).recipientUserId(recipient.getId())
                .type(NotificationType.DOCUMENT_REQUEST_FULFILLED).title("Signed document returned").message("The requester signed \"" + request.getRequestTitle() + "\". The complete signed document is available.")
                .relatedEntityId(requestId).actionPath("/myhr/" + personaSegment(recipient.getRole()) + "/document-requests/" + requestId).email(true).actionLabel("View Document").build());
        });
        return responses(List.of(request), actor.organizationId()).getFirst();
    }

    /** Stamp current offer letters in their dedicated acceptance area; preserve legacy letters with an appended acceptance page. */
    private byte[] applySignature(byte[] originalPdf, byte[] signatureImage, String signer, String requestId, LocalDateTime signedAt) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PdfReader reader = new PdfReader(originalPdf);
            try {
                PdfStamper stamper = new PdfStamper(reader, output);
                Image image = Image.getInstance(signatureImage); image.scaleToFit(200, 80);
                float[] signaturePosition = stamper.getAcroFields().getFieldPositions(EMPLOYEE_SIGNATURE_FIELD);
                if (signaturePosition != null && signaturePosition.length >= 5) {
                    int page = (int) signaturePosition[0];
                    float left = signaturePosition[1], bottom = signaturePosition[2];
                    float width = signaturePosition[3] - left, height = signaturePosition[4] - bottom;
                    image.scaleToFit(width, height);
                    image.setAbsolutePosition(left + (width - image.getScaledWidth()) / 2, bottom + (height - image.getScaledHeight()) / 2);
                    stamper.getOverContent(page).addImage(image);
                    stamper.getAcroFields().removeField(EMPLOYEE_SIGNATURE_FIELD);
                } else {
                    int page = reader.getNumberOfPages() + 1;
                    stamper.insertPage(page, PageSize.LETTER);
                    var canvas = stamper.getOverContent(page);
                    canvas.beginText();
                    canvas.setFontAndSize(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false), 12);
                    canvas.setTextMatrix(54, 730); canvas.showText("Employee acceptance");
                    canvas.setTextMatrix(54, 700); canvas.showText("I accept the terms of the preceding document.");
                    canvas.setTextMatrix(54, 670); canvas.showText("Employee: " + signer);
                    canvas.setTextMatrix(54, 645); canvas.showText("Request: " + requestId);
                    canvas.setTextMatrix(54, 620); canvas.showText("Signed at (server time): " + (signedAt == null ? "Not recorded in legacy request" : signedAt));
                    canvas.setTextMatrix(54, 580); canvas.showText("Employee signature:");
                    canvas.endText();
                    image.setAbsolutePosition(54, 475);
                    canvas.addImage(image);
                }
                stamper.close();
            } finally { reader.close(); }
            return output.toByteArray();
        } catch (Exception failure) { throw new BusinessException("The employee signature could not be applied to the completed document"); }
    }

    public RequestDocumentContent getDocumentContent(String actorUserId, String requestId, String documentId) {
        var actor = authenticatedUserService.require(actorUserId, ALL_ROLES, false);
        DocumentRequest request = requireVisible(actor, requestId);
        DocumentRequestVersion version = documentId == null ? null : Optional.ofNullable(request.getDocumentVersions())
                .orElse(List.of()).stream().filter(item -> documentId.equals(item.getDocumentId())).findFirst().orElse(null);
        boolean linked = documentId != null && (documentId.equals(request.getFulfilledDocumentId())
                || documentId.equals(request.getEmployeeSignatureDocumentId()) || documentId.equals(request.getSignedResponseDocumentId())
                || Optional.ofNullable(request.getAttachmentDocumentIds()).orElse(List.of()).contains(documentId)
                || version != null);
        if (!linked) throw new ResourceNotFoundException("Document not found");
        // Signed I-983: the employee sees their own submissions, and the signed copy only once it is approved.
        if (version != null && actor.userId().equals(request.getRequestedByUserId()) && !requesterMaySee(request, version))
            throw new ResourceNotFoundException("Document not found");
        // Earlier versions saved a detached signature, sometimes with a badly positioned PDF.
        // Reconstruct that legacy signed copy from the immutable original, never expose the image as the letter.
        if (request.getDocumentType() == RequestedDocumentType.OFFER_LETTER
                && request.getEmployeeSignatureDocumentId() != null
                && (documentId.equals(request.getSignedResponseDocumentId()) || documentId.equals(request.getEmployeeSignatureDocumentId()))) {
            var original = documents().getContent(request.getFulfilledDocumentId());
            var signature = documents().getContent(request.getEmployeeSignatureDocumentId());
            validateDocumentLink(request, original.metadata());
            validateDocumentLink(request, signature.metadata());
            User signer = requireUserInOrganization(request.getRequestedByUserId(), actor.organizationId());
            EmployeeProfile profile = employeeProfileRepository.findByUserId(signer.getId()).orElse(null);
            return new RequestDocumentContent("signed-offer-letter.pdf", "application/pdf",
                    applySignature(original.content(), signature.content(), displayName(signer, profile), requestId, request.getSignatureSubmittedAt()));
        }
        DocumentService.DocumentContent content = documents().getContent(documentId);
        StoredDocument metadata = content.metadata();
        validateDocumentLink(request, metadata);
        return new RequestDocumentContent(metadata.getFileName(), conservativeContentType(metadata.getContentType()), content.content());
    }

    /** Which Signed I-983 versions the requesting employee may open (reviewers may open all of them). */
    static boolean requesterMaySee(DocumentRequest request, DocumentRequestVersion version) {
        if (DocumentRequestVersion.ORIGINAL.equals(version.getKind())
                || DocumentRequestVersion.EMPLOYEE_SIGNED.equals(version.getKind())) return true;
        return DocumentRequestVersion.SIGNED.equals(version.getKind())
                && request.getStatus() == DocumentRequestStatus.APPROVED
                && version.getDocumentId().equals(request.getOrganizationSignedDocumentId());
    }

    private void validateDocumentLink(DocumentRequest request, StoredDocument metadata) {
        if (!request.getOrganizationId().equals(metadata.getOrganizationId())
                || !request.getId().equals(metadata.getRelatedEntityId())
                || !RELATED_ENTITY_TYPE.equals(metadata.getRelatedEntityType())
                || metadata.getDocumentType() != DocumentType.REQUESTED_DOCUMENT) {
            throw new ResourceNotFoundException("Document not found");
        }
    }

    private DocumentRequest requireVisible(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, String requestId) {
        DocumentRequest request = requireOrganizationRequest(actor, requestId);
        if (actor.role() == UserRole.COMPANY_ADMIN || involves(request, actor.userId())) return request;
        if (actor.role() == UserRole.HR) {
            Set<String> reports = directReportIds(actor.organizationId(), actor.userId());
            if (reports.contains(request.getRequestedByUserId()) || reports.contains(request.getRequestedForUserId())) return request;
        }
        throw new ResourceNotFoundException("Document request not found");
    }

    private DocumentRequest requireOrganizationRequest(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, String id) {
        return requestRepository.findByIdAndOrganizationId(id, actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Document request not found"));
    }

    private void authorizeManagerTarget(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, User target) {
        if (target.getAccountStatus() != AccountStatus.ACTIVE) throw new ResourceNotFoundException("Employee not found");
        if (actor.role() == UserRole.COMPANY_ADMIN) {
            if (target.getRole() == UserRole.HR || target.getRole() == UserRole.EMPLOYEE) return;
        } else if (target.getRole() == UserRole.EMPLOYEE
                && directReportIds(actor.organizationId(), actor.userId()).contains(target.getId())) return;
        throw new ForbiddenException("You cannot request documents from this user");
    }

    private void validateManagerInput(DocumentRequestCreateRequest input) {
        if (!CATEGORIES.contains(input.category())) throw new BusinessException("Invalid document category");
        if (!PRIORITIES.contains(input.priority())) throw new BusinessException("Invalid priority");
        if (input.requiredBy() != null && input.requiredBy().isBefore(LocalDate.now()))
            throw new BusinessException("Required-by date cannot be in the past");
    }

    private RequestedDocumentType parseDocumentType(String value) {
        try { return RequestedDocumentType.valueOf(value.trim()); }
        catch (RuntimeException failure) { throw new BusinessException("Invalid document type"); }
    }

    private void requireAssignedRecipient(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, DocumentRequest request) {
        if (actor.role() == UserRole.COMPANY_ADMIN) return;
        if (!actor.userId().equals(request.getRequestedForUserId()))
            throw new ForbiddenException("Only the assigned recipient or a company admin can complete this request");
    }

    private void requirePending(DocumentRequest request) {
        if (request.getStatus() != DocumentRequestStatus.PENDING)
            throw new BusinessException("Only pending document requests can be changed");
    }

    private Set<String> directReportIds(String organizationId, String managerId) {
        return assignmentRepository.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                organizationId, managerId, RelationshipType.PRIMARY_REPORTING_MANAGER).stream()
                .map(ReportingAssignment::getEmployeeUserId).collect(Collectors.toSet());
    }

    private User requireUserInOrganization(String id, String organizationId) {
        return userRepository.findById(id).filter(user -> organizationId.equals(user.getOrganizationId()))
                .orElseThrow(() -> new ResourceNotFoundException("Employee not found"));
    }

    private DocumentService documents() {
        DocumentService service = documentServiceProvider.getIfAvailable();
        if (service == null) throw new BusinessException("Document storage is not configured");
        return service;
    }

    private void cleanup(List<String> ids, Throwable failure) {
        DocumentService service = documentServiceProvider.getIfAvailable();
        if (service == null) return;
        for (String id : ids) try { service.delete(id); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
    }

    private void audit(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, AuditActionType action,
                       DocumentRequest request, String description) {
        Object previousValue = null;
        Map<String, Object> updatedValue = new LinkedHashMap<>();
        if (action == AuditActionType.DOCUMENT_REQUEST_CREATED) {
            updatedValue.put("requestTitle", request.getRequestTitle());
            updatedValue.put("documentType", request.getDocumentType() == null ? null : request.getDocumentType().name());
            updatedValue.put("category", request.getCategory());
            updatedValue.put("priority", request.getPriority());
            updatedValue.put("requiredBy", request.getRequiredBy() == null ? null : request.getRequiredBy().toString());
            updatedValue.put("status", request.getStatus().name());
        } else if (action == AuditActionType.DOCUMENT_REQUEST_FULFILLED) {
            previousValue = Map.of("status", DocumentRequestStatus.PENDING.name());
            updatedValue.put("status", request.getStatus().name());
        } else if (action == AuditActionType.DOCUMENT_REQUEST_REJECTED) {
            previousValue = Map.of("status", DocumentRequestStatus.PENDING.name());
            updatedValue.put("status", request.getStatus().name());
            updatedValue.put("rejectionReason", request.getRejectionReason());
        } else {
            updatedValue.put("status", request.getStatus().name());
        }

        try {
            Map<String, User> participants = userRepository.findByIdIn(Set.of(
                            request.getRequestedByUserId(), request.getRequestedForUserId())).stream()
                    .collect(Collectors.toMap(User::getId, Function.identity()));
            Map<String, EmployeeProfile> participantProfiles = profiles(actor.organizationId());
            putParticipant(updatedValue, "requestedBy", participants.get(request.getRequestedByUserId()), participantProfiles);
            putParticipant(updatedValue, "requestedFor", participants.get(request.getRequestedForUserId()), participantProfiles);
        } catch (RuntimeException enrichmentFailure) {
            log.warn("Could not enrich document-request audit participants for request {}", request.getId());
        }

        auditService.record(AuditEvent.builder().organizationId(actor.organizationId()).actorUserId(actor.userId())
                .actorLoginId(actor.loginId()).actorRole(actor.role()).actionType(action)
                .entityType("DOCUMENT_REQUEST").entityId(request.getId()).entityDisplayName(request.getRequestTitle())
                .description(description).previousValue(previousValue).updatedValue(updatedValue)
                .outcome("SUCCESS").source("MY_HR").build());
    }

    private void putParticipant(Map<String, Object> metadata, String key, User user,
                                Map<String, EmployeeProfile> profiles) {
        if (user == null) return;
        metadata.put(key, participantLabel(user, profiles.get(user.getId())));
    }

    /** "Full Name (EMPLOYEE-ID)", falling back to just the identity when no employee id exists (e.g. Company Admin). */
    private String participantLabel(User user, EmployeeProfile profile) {
        String employeeId = profile == null ? null : profile.getEmployeeId();
        String identity = displayName(user, profile);
        return employeeId == null || employeeId.isBlank() ? identity : identity + " (" + employeeId + ")";
    }

    private String personaSegment(UserRole role) {
        if (role == UserRole.COMPANY_ADMIN) return "admin";
        if (role == UserRole.HR) return "hr";
        return "employee";
    }

    private String requiredBySuffix(LocalDate requiredBy) {
        return requiredBy == null ? "" : " Required by " + REQUIRED_BY_FORMAT.format(requiredBy) + ".";
    }

    private void notifySafely(Runnable notification) {
        try {
            notification.run();
        } catch (RuntimeException failure) {
            log.warn("Document request notification could not be created", failure);
        }
    }

    /** Manager (or Company Admin) requested a document FROM an employee - notify the employee. */
    private void notifyDocumentRequested(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
                                         DocumentRequest saved, User target) {
        if (actor.userId().equals(target.getId())) return;
        EmployeeProfile actorProfile = employeeProfileRepository.findByUserId(actor.userId()).orElse(null);
        User actorUser = User.builder().id(actor.userId()).loginId(actor.loginId()).build();
        String actorLabel = participantLabel(actorUser, actorProfile);
        notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId()).recipientUserId(target.getId())
                .type(NotificationType.DOCUMENT_REQUEST_CREATED).title("New document request").message(actorLabel + " requested \"" + saved.getRequestTitle() + "\" from you." + requiredBySuffix(saved.getRequiredBy()))
                .relatedEntityId(saved.getId()).actionPath("/myhr/" + personaSegment(target.getRole()) + "/document-requests/" + saved.getId()).email(true).actionLabel("Open Request").build());
    }

    /** Employee requested a document from their reporting manager (or assigned recipient) - notify that recipient. */
    private void notifyEmployeeDocumentRequested(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,
                                                 DocumentRequest saved, User recipient) {
        if (actor.userId().equals(recipient.getId())) return;
        EmployeeProfile actorProfile = employeeProfileRepository.findByUserId(actor.userId()).orElse(null);
        User actorUser = User.builder().id(actor.userId()).loginId(actor.loginId()).build();
        String actorLabel = participantLabel(actorUser, actorProfile);
        notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId()).recipientUserId(recipient.getId())
                .type(NotificationType.DOCUMENT_REQUEST_CREATED).title("New employee document request").message(actorLabel + " requested \"" + saved.getRequestTitle() + "\"." + requiredBySuffix(saved.getRequiredBy()))
                .relatedEntityId(saved.getId()).actionPath("/myhr/" + personaSegment(recipient.getRole()) + "/document-requests/" + saved.getId()).email(true).actionLabel("Open Request").build());
    }

    /** Notify whoever originally created the request (either direction) that it has been fulfilled. */
    private void notifyDocumentRequestFulfilled(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, DocumentRequest saved) {
        String recipientId = saved.getRequestedByUserId();
        if (actor.userId().equals(recipientId)) return;
        User recipient = userRepository.findById(recipientId).orElse(null);
        EmployeeProfile actorProfile = employeeProfileRepository.findByUserId(actor.userId()).orElse(null);
        User actorUser = User.builder().id(actor.userId()).loginId(actor.loginId()).build();
        String actorLabel = participantLabel(actorUser, actorProfile);
        String persona = recipient == null ? "employee" : personaSegment(recipient.getRole());
        // The employee-to-manager fulfillment already has its own dedicated document email.
        notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId()).recipientUserId(recipientId)
                .type(NotificationType.DOCUMENT_REQUEST_FULFILLED).title("Document request fulfilled").message("Your \"" + saved.getRequestTitle() + "\" request has been fulfilled by " + actorLabel + ".")
                .relatedEntityId(saved.getId()).actionPath("/myhr/" + persona + "/document-requests/" + saved.getId()).email(!"TO_MANAGER".equals(saved.getRequestDirection())).actionLabel("View Request").build());
    }

    /** Notify whoever originally created the request that it was rejected, including the required reason. */
    private void notifyDocumentRequestRejected(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor, DocumentRequest saved) {
        String recipientId = saved.getRequestedByUserId();
        if (actor.userId().equals(recipientId)) return;
        User recipient = userRepository.findById(recipientId).orElse(null);
        String persona = recipient == null ? "employee" : personaSegment(recipient.getRole());
        notificationOrchestrator.notify(NotificationEvent.builder().organizationId(actor.organizationId()).recipientUserId(recipientId)
                .type(NotificationType.DOCUMENT_REQUEST_REJECTED).title("Document request rejected").message("Your \"" + saved.getRequestTitle() + "\" request was rejected. Reason: " + saved.getRejectionReason())
                .relatedEntityId(saved.getId()).actionPath("/myhr/" + persona + "/document-requests/" + saved.getId()).email(true).actionLabel("View Request").build());
    }

    private List<DocumentRequestResponse> responses(List<DocumentRequest> requests, String organizationId) {
        Set<String> ids = requests.stream().flatMap(item -> java.util.stream.Stream.of(
                item.getRequestedByUserId(), item.getRequestedForUserId())).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<String, User> users = ids.isEmpty() ? Map.of() : userRepository.findByIdIn(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        // Only the profiles of the people on these requests, not every profile in the organization.
        Map<String, EmployeeProfile> profiles = ids.isEmpty() ? Map.of() : employeeProfileRepository.findByUserIdIn(ids).stream()
                .filter(profile -> organizationId.equals(profile.getOrganizationId()))
                .collect(Collectors.toMap(EmployeeProfile::getUserId, Function.identity(), (left, right) -> left));
        Map<String, String> attachmentNames = attachmentFileNameIndex(requests);
        return requests.stream().map(item -> response(item, users, profiles, attachmentNames)).toList();
    }

    private DocumentRequestResponse response(DocumentRequest item, Map<String, User> users,
                                              Map<String, EmployeeProfile> profiles, Map<String, String> attachmentNames) {
        User by = users.get(item.getRequestedByUserId());
        User forUser = users.get(item.getRequestedForUserId());
        EmployeeProfile requestedByProfile = profiles.get(item.getRequestedByUserId());
        return new DocumentRequestResponse(item.getId(), item.getRequestedByUserId(), login(by), by == null ? null : by.getEmail(),
                requestedByProfile == null ? null : requestedByProfile.getStreetAddress(),
                requestedByProfile == null ? null : requestedByProfile.getCity(),
                requestedByProfile == null ? null : requestedByProfile.getState(),
                requestedByProfile == null ? null : requestedByProfile.getZipCode(), name(by, profiles), role(by),
                item.getRequestedForUserId(), login(forUser), name(forUser, profiles), role(forUser), item.getDocumentType(),
                item.getRequestTitle(), item.getRequestedDocuments(), item.getRequestDescription(), item.getCategory(),
                item.getPriority(), item.getRequestDirection(), item.getAttachmentDocumentIds(),
                attachmentFileNames(item.getAttachmentDocumentIds(), attachmentNames), item.getRequiredBy(),
                item.getStatus(), item.getFulfilledDocumentId(), item.getEmployeeSignatureDocumentId(),
                item.getSignedResponseDocumentId() != null ? item.getSignedResponseDocumentId()
                        : item.getDocumentType() == RequestedDocumentType.OFFER_LETTER ? item.getEmployeeSignatureDocumentId() : null,
                item.getSignatureSubmittedAt(), item.getRejectionReason(), item.getRejectedByUserId(),
                item.getRequestedAt(), item.getFulfilledAt(), item.getRejectedAt(), item.getOriginalDocumentId());
    }

    /** Best-effort file name lookup for each attachment, aligned by index with attachmentDocumentIds;
     * never throws (a missing/unavailable document service just means the UI falls back to a generic label). */
    private List<String> attachmentFileNames(List<String> attachmentDocumentIds, Map<String, String> names) {
        if (attachmentDocumentIds == null || attachmentDocumentIds.isEmpty()) return List.of();
        return attachmentDocumentIds.stream().map(names::get).toList();
    }

    /** One batched lookup of every attachment's file name across all requests (was one query per attachment). */
    private Map<String, String> attachmentFileNameIndex(List<DocumentRequest> requests) {
        Set<String> documentIds = requests.stream().map(DocumentRequest::getAttachmentDocumentIds).filter(Objects::nonNull)
                .flatMap(List::stream).filter(Objects::nonNull).collect(Collectors.toSet());
        if (documentIds.isEmpty()) return Map.of();
        DocumentService service = documentServiceProvider.getIfAvailable();
        if (service == null) return Map.of();
        try {
            Map<String, String> names = new HashMap<>();
            service.getMetadataByIds(documentIds).forEach((id, document) -> names.put(id, document.getFileName()));
            return names;
        } catch (RuntimeException failure) {
            return Map.of();
        }
    }

    private Map<String, EmployeeProfile> profiles(String organizationId) {
        return employeeProfileRepository.findByOrganizationId(organizationId).stream()
                .collect(Collectors.toMap(EmployeeProfile::getUserId, Function.identity(), (left, right) -> left));
    }

    private RequestableEmployeeResponse requestable(User user, EmployeeProfile profile) {
        return new RequestableEmployeeResponse(user.getId(), profile == null ? null : profile.getEmployeeId(),
                displayName(user, profile), user.getLoginId(), user.getRole());
    }

    private String name(User user, Map<String, EmployeeProfile> profiles) {
        return user == null ? "" : displayName(user, profiles.get(user.getId()));
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

    private boolean involves(DocumentRequest item, String userId) {
        return userId.equals(item.getRequestedByUserId()) || userId.equals(item.getRequestedForUserId());
    }

    private String login(User user) { return user == null ? null : user.getLoginId(); }
    private UserRole role(User user) { return user == null ? null : user.getRole(); }
    private String safeContentType(String value) { return value == null || value.isBlank() ? "application/octet-stream" : value.trim(); }
    private String conservativeContentType(String value) {
        return Set.of("application/pdf", "image/png", "image/jpeg", "image/gif",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document").contains(value)
                ? value : "application/octet-stream";
    }

    public record RequestDocumentContent(String fileName, String contentType, byte[] content) {}
}
