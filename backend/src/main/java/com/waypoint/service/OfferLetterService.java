package com.waypoint.service;

import com.waypoint.dto.myhr.*;
import com.waypoint.entity.*;
import com.waypoint.enums.*;
import com.waypoint.exception.*;
import com.waypoint.repository.*;
import com.waypoint.security.MyHrAuthenticatedUserService;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j @Service @RequiredArgsConstructor
public class OfferLetterService {
    private final OfferLetterRecordRepository records; private final DocumentRequestRepository requests;
    private final EmployeeProfileRepository profiles; private final OrganizationRepository organizations;
    private final UserRepository users; private final ReportingAssignmentRepository assignments;
    private final MyHrAuthenticatedUserService auth; private final DocumentService documents;
    private final OfferLetterPdfService pdf; private final AuditService audit;
    private final EmailService emailService; private final OfferLetterEmailTemplateService emailTemplates;
    private final OfferLetterStaticAttachmentService staticAttachments;
    private static final Set<UserRole> HR = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);
    private static final Pattern EMAIL_ADDRESS = Pattern.compile("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$");
    private static final int MAX_DRAFT_TEXT_LENGTH = 160;
    static final String RECIPIENT_REJECTED = "Email could not be delivered to this recipient. Please check the email address and try again.";
    static final String DELIVERY_UNCONFIRMED = "The offer letter email was handed to the mail server, but delivery could not be confirmed. Check the recipient's inbox before sending it again.";
    static final String OFFER_PDF_UNAVAILABLE = "Offer Letter PDF is not available. Please generate the Offer Letter again before sending.";

    public OfferLetterResponse get(String actorId, String requestId) { var actor=auth.require(actorId, Set.of(UserRole.EMPLOYEE,UserRole.HR,UserRole.COMPANY_ADMIN), false); DocumentRequest r=requireOffer(actor,requestId); return response(records.findByDocumentRequestIdAndOrganizationId(requestId, actor.organizationId()).orElseGet(() -> prefill(actor,r))); }
    public OfferLetterResponse save(String actorId,String requestId,OfferLetterInput input) { var a=auth.require(actorId,HR,false); DocumentRequest r=requireOffer(a,requestId); authorizeProcessor(a,r); requirePending(r); validate(input); OfferLetterRecord o=records.findByDocumentRequestIdAndOrganizationId(requestId,a.organizationId()).orElseGet(()->prefill(a,r)); apply(o,input); o.setStatus("DRAFT"); o.setUpdatedAt(LocalDateTime.now()); o=records.save(o); record(a,AuditActionType.OFFER_DRAFT_SAVED,r,"Offer letter draft saved"); return response(o); }
    public OfferLetterResponse generate(String actorId,String requestId,OfferLetterInput input) { var a=auth.require(actorId,HR,false); DocumentRequest r=requireOffer(a,requestId); authorizeProcessor(a,r); requirePending(r); validate(input); OfferLetterRecord o=records.findByDocumentRequestIdAndOrganizationId(requestId,a.organizationId()).orElseGet(()->prefill(a,r)); apply(o,input); int version=Optional.ofNullable(o.getVersions()).orElse(List.of()).size()+1; byte[] bytes=pdf.render(o); StoredDocument document=documents.store(new DocumentUploadRequest(a.organizationId(),r.getRequestedByUserId(),DocumentType.REQUESTED_DOCUMENT,"DOCUMENT_REQUEST",r.getId(),"offer-letter-v"+version+".pdf","application/pdf",bytes,version,a.userId())); List<OfferLetterVersion> versions=new ArrayList<>(Optional.ofNullable(o.getVersions()).orElse(List.of())); versions.forEach(v->{if("CURRENT".equals(v.getStatus()))v.setStatus("SUPERSEDED");}); versions.add(OfferLetterVersion.builder().version(version).documentId(document.getId()).status("CURRENT").generatedByUserId(a.userId()).generatedAt(LocalDateTime.now()).build()); o.setVersions(versions); o.setStatus("GENERATED"); o.setUpdatedAt(LocalDateTime.now()); o=records.save(o); record(a,AuditActionType.OFFER_GENERATED,r,"Offer letter generated (version "+version+")"); return response(o); }
    /**
     * The record-based send predates the Admin approval workflow and would deliver a letter without it,
     * so it is closed: offer letters are delivered through {@link DocumentRequestService#deliverApprovedOfferLetter}.
     */
    public OfferLetterResponse send(String actorId, String requestId) {
        auth.require(actorId, HR, false);
        throw new BusinessException(DocumentRequestService.OFFER_LETTER_NEEDS_APPROVAL);
    }
    /**
     * Pre-populated, editable email for the Offer Letter prepare page. The
     * recipient comes from the employee's saved My HR account email; the
     * designation / name are the values HR entered for this letter and are
     * only used as display text.
     */
    public OfferLetterEmailDraftResponse emailDraft(String actorId, String requestId, String designation, String employeeName) {
        var a = auth.require(actorId, HR, false);
        DocumentRequest r = requireOffer(a, requestId);
        requireEmailSender(a, r);
        if (length(designation) > MAX_DRAFT_TEXT_LENGTH || length(employeeName) > MAX_DRAFT_TEXT_LENGTH)
            throw new BusinessException("Designation and employee name must not exceed " + MAX_DRAFT_TEXT_LENGTH + " characters");
        // One name for this letter: the name HR entered, else the profile name. It drives both the greeting and the attachment name.
        String greetingName = blank(employeeName) ? employeeName(r) : employeeName.trim();
        OrganizationEmailBranding branding = emailBranding(a.organizationId());
        return new OfferLetterEmailDraftResponse(recipientEmail(r), emailTemplates.defaultSubject(branding),
                emailTemplates.defaultMessage(greetingName, trim(designation), branding), emailTemplates.attachmentFileName(greetingName));
    }

    /**
     * Emails the offer letter PDF already delivered to the employee (the
     * request's fulfilled document) with an HR-edited subject and message.
     * Nothing about the request, its document, or the employee profile
     * changes here; a successful send is audited and a failed one is not.
     */
    public void email(String actorId, String requestId, OfferLetterEmailRequest input) {
        var a = auth.require(actorId, HR, false);
        DocumentRequest r = requireOffer(a, requestId);
        requireEmailSender(a, r);
        if (r.getStatus() != DocumentRequestStatus.FULFILLED)
            throw new BusinessException("Send the offer letter to the employee before emailing it");
        if (!OfferLetterApprovalService.isApproved(r))
            throw new BusinessException("This offer letter has not been approved by the Company Admin, so it cannot be emailed.");
        if (blank(r.getFulfilledDocumentId())) throw new BusinessException(OFFER_PDF_UNAVAILABLE);
        if (!blank(r.getSignedResponseDocumentId()))
            throw new BusinessException("The employee has already signed and returned this offer letter");
        if (input == null) throw new BusinessException("Recipient email is required");
        String recipient = validRecipient(input.recipientEmail());
        String subject = validSubject(input.subject());
        if (blank(input.message())) throw new BusinessException("Email message is required");
        byte[] pdfBytes = deliveredOfferLetterPdf(a, r);
        String html = emailTemplates.renderHtml(input.message());
        List<EmailAttachment> attachments = new ArrayList<>();
        attachments.add(new EmailAttachment(emailTemplates.emailAttachmentName(blank(input.employeeName()) ? employeeName(r) : input.employeeName().trim()), pdfBytes, "application/pdf"));
        attachments.addAll(staticAttachments.load());
        try {
            emailService.sendHtmlEmailWithAttachments(recipient, subject, html, attachments,
                    emailTemplates.senderDisplayName(emailBranding(a.organizationId())));
        } catch (RuntimeException failure) {
            Throwable root = rootCause(failure);
            log.warn("Offer letter email failed [requestId={}, exceptionType={}, rootCauseType={}, reason={}]",
                    r.getId(), failure.getClass().getSimpleName(), root.getClass().getSimpleName(), failure.getMessage());
            if (failure instanceof IllegalStateException && EmailService.DELIVERY_DISABLED_MESSAGE.equals(failure.getMessage()))
                throw new EmailDeliveryException("Email delivery is not enabled on this server, so the offer letter email was not sent.");
            if (recipientRejected(failure))
                throw new EmailDeliveryException(RECIPIENT_REJECTED);
            if (confirmationLost(failure)) {
                // The server may already hold the message: audit it, and tell the user so they do not resend blindly.
                recordEmailed(a, r, recipient, false);
                throw new EmailDeliveryUnconfirmedException(DELIVERY_UNCONFIRMED);
            }
            throw new EmailDeliveryException("The offer letter email could not be sent. Please try again later.");
        }
        recordEmailed(a, r, recipient, true);
    }

    /**
     * The email has already been sent by now, so a failure to write the audit
     * entry must not turn a delivered email into a reported failure (which
     * would invite a duplicate send on retry).
     */
    private void recordEmailed(MyHrAuthenticatedUserService.AuthenticatedMyHrUser a, DocumentRequest r, String recipient, boolean confirmed) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("documentType", "OFFER_LETTER");
        details.put("recipientEmail", recipient);
        if (!confirmed) details.put("deliveryConfirmed", "false");
        try {
            audit.record(a, AuditActionType.OFFER_EMAILED, "DOCUMENT_REQUEST", r.getId(), r.getRequestTitle(),
                    "Offer letter emailed to employee", null, details);
        } catch (RuntimeException auditFailure) {
            log.error("Offer letter email was sent but its audit entry failed [requestId={}, reason={}]", r.getId(), auditFailure.getMessage());
        }
    }

    /** The mail server explicitly refused the recipient address (synchronous SMTP rejection). */
    private static boolean recipientRejected(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof jakarta.mail.SendFailedException sent && sent.getInvalidAddresses() != null && sent.getInvalidAddresses().length > 0) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** A read timeout means the message may already have been accepted; the outcome is unknown, not "failed". */
    private static boolean confirmationLost(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof java.net.SocketTimeoutException && t.getMessage() != null
                    && t.getMessage().toLowerCase(Locale.ROOT).contains("read timed out")) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    private static Throwable rootCause(Throwable failure) {
        Throwable t = failure;
        while (t.getCause() != null && t.getCause() != t) t = t.getCause();
        return t;
    }

    /** Same rule as fulfilling the request: a Company Admin, or the HR the request is assigned to. */
    private void requireEmailSender(MyHrAuthenticatedUserService.AuthenticatedMyHrUser a, DocumentRequest r) {
        if (a.role() == UserRole.COMPANY_ADMIN) return;
        if (!a.userId().equals(r.getRequestedForUserId()))
            throw new ForbiddenException("Only the assigned HR or a company admin can email this offer letter");
    }

    /**
     * The real stored bytes of the delivered offer letter - never a regenerated,
     * placeholder, or substitute document. Any gap fails the send with one
     * clear message instead.
     */
    private byte[] deliveredOfferLetterPdf(MyHrAuthenticatedUserService.AuthenticatedMyHrUser a, DocumentRequest r) {
        DocumentService.DocumentContent content;
        try {
            content = documents.getStoredContent(r.getFulfilledDocumentId());
        } catch (ObjectStorageException | ResourceNotFoundException | BusinessException unavailable) {
            log.warn("Offer letter PDF unavailable for email [requestId={}, reason={}]", r.getId(), unavailable.getMessage());
            throw new BusinessException(OFFER_PDF_UNAVAILABLE);
        }
        StoredDocument metadata = content.metadata();
        if (!a.organizationId().equals(metadata.getOrganizationId()) || !r.getId().equals(metadata.getRelatedEntityId()))
            throw new ResourceNotFoundException("Offer letter document not found");
        byte[] bytes = content.content();
        if (bytes.length < 5 || !"%PDF-".equals(new String(bytes, 0, 5, StandardCharsets.US_ASCII)))
            throw new BusinessException(OFFER_PDF_UNAVAILABLE);
        return bytes;
    }

    /**
     * The employee's current contact email, as Employee Management shows it:
     * the profile's personal email (kept current by approved profile change
     * requests), else the account email it was created from, else the work email.
     */
    private String recipientEmail(DocumentRequest r) {
        EmployeeProfile profile = profiles.findByUserId(r.getRequestedByUserId()).orElse(null);
        if (profile != null && !blank(profile.getPersonalEmail())) return profile.getPersonalEmail().trim();
        String accountEmail = users.findById(r.getRequestedByUserId()).map(User::getEmail).orElse(null);
        if (!blank(accountEmail)) return accountEmail.trim();
        return profile != null && !blank(profile.getWorkEmail()) ? profile.getWorkEmail().trim() : null;
    }

    private String employeeName(DocumentRequest r) {
        return profiles.findByUserId(r.getRequestedByUserId())
                .map(p -> !blank(p.getName()) ? p.getName().trim()
                        : String.join(" ", Optional.ofNullable(p.getFirstName()).orElse(""), Optional.ofNullable(p.getLastName()).orElse("")).trim())
                .filter(this::blankNot).orElse(null);
    }

    private String validRecipient(String value) {
        if (blank(value)) throw new BusinessException("Recipient email is required");
        String recipient = value.trim();
        if (recipient.length() > 254 || !EMAIL_ADDRESS.matcher(recipient).matches())
            throw new BusinessException("Enter a valid recipient email address");
        try {
            new InternetAddress(recipient, true).validate();
        } catch (AddressException invalid) {
            throw new BusinessException("Enter a valid recipient email address");
        }
        return recipient;
    }

    private String validSubject(String value) {
        if (blank(value)) throw new BusinessException("Email subject is required");
        String subject = value.trim();
        if (subject.length() > 200) throw new BusinessException("Email subject must not exceed 200 characters");
        if (subject.contains("\n") || subject.contains("\r")) throw new BusinessException("Email subject must be a single line");
        return subject;
    }

    private int length(String value) { return value == null ? 0 : value.trim().length(); }

    private OrganizationEmailBranding emailBranding(String organizationId) {
        Organization organization = organizations.findById(organizationId)
                .orElseThrow(() -> new BusinessException("Organization is required"));
        return OrganizationEmailBranding.from(organization);
    }

    private DocumentRequest requireOffer(MyHrAuthenticatedUserService.AuthenticatedMyHrUser a,String id){ DocumentRequest r=requests.findByIdAndOrganizationId(id,a.organizationId()).orElseThrow(()->new ResourceNotFoundException("Document request not found")); if(r.getDocumentType()!=RequestedDocumentType.OFFER_LETTER)throw new BusinessException("This is not an offer letter request"); if(a.role()==UserRole.EMPLOYEE&&!a.userId().equals(r.getRequestedByUserId()))throw new ResourceNotFoundException("Document request not found"); return r; }
    private void authorizeProcessor(MyHrAuthenticatedUserService.AuthenticatedMyHrUser a,DocumentRequest r){ if(a.role()==UserRole.COMPANY_ADMIN)return; boolean report=assignments.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(a.organizationId(),a.userId(),RelationshipType.PRIMARY_REPORTING_MANAGER).stream().anyMatch(x->x.getEmployeeUserId().equals(r.getRequestedByUserId())); if(!report)throw new ForbiddenException("You cannot process this offer letter request"); }
    private OfferLetterRecord prefill(MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor,DocumentRequest r){ EmployeeProfile p=profiles.findByUserId(r.getRequestedByUserId()).orElseThrow(()->new BusinessException("Employee profile is required")); Organization org=organizations.findById(actor.organizationId()).orElseThrow(()->new BusinessException("Organization is required")); User issuer=users.findById(actor.userId()).orElse(null); String name=p.getName()!=null?p.getName():String.join(" ",Optional.ofNullable(p.getFirstName()).orElse(""),Optional.ofNullable(p.getLastName()).orElse("")).trim(); OfferLetterRecord o=OfferLetterRecord.builder().organizationId(actor.organizationId()).documentRequestId(r.getId()).employeeUserId(r.getRequestedByUserId()).employeeName(name).employeeId(p.getEmployeeId()).employeeEmail(p.getWorkEmail()!=null?p.getWorkEmail():p.getPersonalEmail()).employeeAddress(address(p.getStreetAddress(),p.getCity(),p.getState(),p.getZipCode())).organizationName(org.getOrganizationName()).organizationAddress(address(org.getStreetAddress(),org.getCity(),org.getState(),org.getZipCode())).designation(p.getDesignation()).startDate(p.getStartDate()).offerDate(java.time.LocalDate.now()).issuerName(issuer==null?actor.loginId():issuer.getLoginId()).issuerDesignation(actor.role().name().replace('_',' ')).status("DRAFT").versions(new ArrayList<>()).createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build(); return records.save(o); }
    private void apply(OfferLetterRecord o,OfferLetterInput i){ o.setOfferType(i.offerType());o.setEmploymentCategory(i.employmentCategory());o.setDesignation(i.designation().trim());o.setOfferDate(i.offerDate());o.setStartDate(i.startDate());o.setEndDate(i.endDate());o.setWorkLocation(i.workLocation().trim());o.setReportingManager(trim(i.reportingManager()));o.setInternshipSchedule(trim(i.internshipSchedule()));o.setCompensationType(trim(i.compensationType()));o.setCompensationAmount(trim(i.compensationAmount()));o.setPayFrequency(trim(i.payFrequency()));o.setTimesheetRequirement(trim(i.timesheetRequirement()));o.setBenefitsEligibility(trim(i.benefitsEligibility()));o.setIssuerName(i.issuerName().trim());o.setIssuerDesignation(i.issuerDesignation().trim());o.setPreviewText(trim(i.previewText())); }
    private void validate(OfferLetterInput i){ String key=i.offerType()+":"+i.employmentCategory(); if(!Set.of("PAID:INTERN","PAID:FULL_TIME","PAID:PART_TIME","PAID:CONTRACT","UNPAID:INTERN").contains(key))throw new BusinessException("Unsupported offer template"); if(i.startDate().isBefore(i.offerDate()))throw new BusinessException("Start date cannot be before offer date"); if("PAID".equals(i.offerType())) { if(!Set.of("HOURLY","SALARY").contains(i.compensationType()))throw new BusinessException("Compensation type is required for paid offers"); try {if(new BigDecimal(i.compensationAmount()).signum()<=0)throw new Exception();}catch(Exception e){throw new BusinessException("Compensation amount must be greater than zero");} if(blank(i.payFrequency())||blank(i.timesheetRequirement())||blank(i.benefitsEligibility()))throw new BusinessException("Pay frequency, timesheet requirement, and benefits eligibility are required"); } else if(i.endDate()!=null&&i.endDate().isBefore(i.startDate()))throw new BusinessException("Internship end date cannot be before start date"); }
    private OfferLetterVersion current(OfferLetterRecord o){return Optional.ofNullable(o.getVersions()).orElse(List.of()).stream().filter(v->"CURRENT".equals(v.getStatus())||"SENT_TO_EMPLOYEE".equals(v.getStatus())).reduce((a,b)->b).orElseThrow(()->new BusinessException("Generate an offer letter before sending it"));}
    private void requirePending(DocumentRequest r){if(r.getStatus()!=DocumentRequestStatus.PENDING)throw new BusinessException("Only pending offer letter requests can be completed");}
    private OfferLetterResponse response(OfferLetterRecord o){ List<OfferLetterResponse.OfferLetterVersionResponse> v=Optional.ofNullable(o.getVersions()).orElse(List.of()).stream().map(x->new OfferLetterResponse.OfferLetterVersionResponse(x.getVersion(),x.getDocumentId(),x.getStatus(),x.getGeneratedByUserId(),x.getGeneratedAt(),x.getSentAt())).toList(); return new OfferLetterResponse(o.getDocumentRequestId(),o.getEmployeeUserId(),o.getEmployeeName(),o.getEmployeeId(),o.getEmployeeAddress(),o.getEmployeeEmail(),o.getOrganizationName(),o.getOrganizationAddress(),o.getOfferType(),o.getEmploymentCategory(),o.getDesignation(),o.getOfferDate(),o.getStartDate(),o.getEndDate(),o.getWorkLocation(),o.getReportingManager(),o.getInternshipSchedule(),o.getCompensationType(),o.getCompensationAmount(),o.getPayFrequency(),o.getTimesheetRequirement(),o.getBenefitsEligibility(),o.getIssuerName(),o.getIssuerDesignation(),o.getStatus(),v,null); }
    private void record(MyHrAuthenticatedUserService.AuthenticatedMyHrUser a,AuditActionType t,DocumentRequest r,String d){audit.record(a,t,"DOCUMENT_REQUEST",r.getId(),r.getRequestTitle(),d,null,Map.of("documentType","OFFER_LETTER"));} private String trim(String x){return blank(x)?null:x.trim();}private boolean blank(String x){return x==null||x.isBlank();}private String address(String...x){return Arrays.stream(x).filter(this::blankNot).reduce((a,b)->a+", "+b).orElse("");}private boolean blankNot(String x){return !blank(x);}
}
