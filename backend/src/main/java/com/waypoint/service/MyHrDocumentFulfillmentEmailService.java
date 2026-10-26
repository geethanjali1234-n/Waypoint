package com.waypoint.service;

import com.waypoint.dto.myhr.DocumentEmailDeliveryResponse;
import com.waypoint.dto.myhr.DocumentFulfillmentEmailDraftResponse;
import com.waypoint.dto.myhr.DocumentFulfillmentEmailRequest;
import com.waypoint.entity.DocumentRequest;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.User;
import com.waypoint.enums.RequestedDocumentType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Best-effort delivery of a completed My HR document to the employee who
 * requested it. A delivery failure must never undo an already stored and
 * fulfilled document request.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MyHrDocumentFulfillmentEmailService {
    private final UserRepository userRepository;
    private final EmployeeProfileRepository employeeProfileRepository;
    private final OrganizationRepository organizationRepository;
    private final EmailService emailService;
    private final OfferLetterEmailTemplateService emailTemplates;

    public DocumentFulfillmentEmailDraftResponse draft(DocumentRequest request, String recipientOverride) {
        User employee = employeeFor(request);
        String title = documentLabelFor(request);
        String recipient = StringUtils.hasText(recipientOverride) ? recipientOverride.trim()
                : employee == null ? "" : employee.getEmail();
        String recipientName = greetingName(request);
        OrganizationEmailBranding branding = emailBranding(request);
        return new DocumentFulfillmentEmailDraftResponse(recipient, "Your " + title + " from " + branding.organizationName(),
                defaultMessage(request, recipientName, title, branding),
                null);
    }

    public DocumentEmailDeliveryResponse send(DocumentRequest request, EmailAttachment attachment, String recipientOverride) {
        DocumentFulfillmentEmailDraftResponse draft = draft(request, recipientOverride);
        return sendWithMessage(request, attachment,
                new DocumentFulfillmentEmailRequest(draft.recipientEmail(), draft.subject(), draft.message()));
    }

    public DocumentEmailDeliveryResponse sendWithMessage(DocumentRequest request, EmailAttachment attachment,
                                                         DocumentFulfillmentEmailRequest input) {
        try {
            if (request == null || !StringUtils.hasText(request.getRequestedByUserId()) || attachment == null) {
                return failed(null, "Email could not be prepared.");
            }
            User employee = employeeFor(request);
            if (employee == null || employee.getRole() != UserRole.EMPLOYEE) {
                log.warn("Completed document email skipped [requestId={}, reason=employee-email-unavailable]", request.getId());
                return failed(null, "The employee does not have a deliverable email address.");
            }
            DocumentFulfillmentEmailDraftResponse draft = draft(request, null);
            String recipient = input == null ? draft.recipientEmail() : input.recipientEmail();
            String subject = input == null ? draft.subject() : subjectText(input.subject());
            String message = input == null ? draft.message() : input.message().trim();
            if (!StringUtils.hasText(recipient) || !recipient.matches("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$"))
                return failed(recipient, "Enter a valid employee email address before sending.");
            if (!StringUtils.hasText(subject) || !StringUtils.hasText(message))
                return failed(recipient, "Enter an email subject and message before sending.");
            if (!StringUtils.hasText(attachment.fileName()) || attachment.content() == null || attachment.content().length == 0
                    || !StringUtils.hasText(attachment.contentType())) {
                log.warn("Completed document email skipped [requestId={}, reason=attachment-unavailable]", request.getId());
                return failed(recipient, "The completed document attachment is unavailable.");
            }

            String html = emailTemplates.renderHtml(message);
            emailService.sendHtmlEmailWithAttachments(recipient, subject, html, List.of(attachment),
                    emailTemplates.senderDisplayName(emailBranding(request)));
            return new DocumentEmailDeliveryResponse("DELIVERED", recipient, "Email delivered to " + recipient + ".");
        } catch (RuntimeException failure) {
            log.warn("Completed document email failed [requestId={}, exceptionType={}]", request == null ? null : request.getId(),
                    failure.getClass().getSimpleName());
            String message = failure instanceof IllegalStateException && EmailService.DELIVERY_DISABLED_MESSAGE.equals(failure.getMessage())
                    ? "Email delivery is disabled on this server." : "The document was completed, but its email could not be delivered.";
            return failed(null, message);
        }
    }

    private DocumentEmailDeliveryResponse failed(String recipient, String message) {
        return new DocumentEmailDeliveryResponse("FAILED", recipient, message);
    }

    private User employeeFor(DocumentRequest request) {
        if (request == null || !StringUtils.hasText(request.getRequestedByUserId())) return null;
        return userRepository.findById(request.getRequestedByUserId()).orElse(null);
    }

    private String titleFor(DocumentRequest request) {
        return request != null && StringUtils.hasText(request.getRequestTitle())
                ? request.getRequestTitle().trim() : "requested document";
    }

    private String greetingName(DocumentRequest request) {
        if (request == null || !StringUtils.hasText(request.getRequestedByUserId())) return "there";
        EmployeeProfile profile = employeeProfileRepository.findByUserId(request.getRequestedByUserId()).orElse(null);
        if (profile == null) return "there";
        if (StringUtils.hasText(profile.getFirstName())) return profile.getFirstName().trim();
        return StringUtils.hasText(profile.getName()) ? profile.getName().trim() : "there";
    }

    private String documentLabelFor(DocumentRequest request) {
        if (request == null || request.getDocumentType() == null) return titleFor(request);
        return switch (request.getDocumentType()) {
            case EXPERIENCE_LETTER -> "Experience and Relieving Letter";
            case EMPLOYMENT_VERIFICATION -> "Employment Verification Letter";
            case EMPLOYMENT_LETTER -> "Employment Letter";
            case I983 -> "Form I-983";
            case OTHER, OFFER_LETTER -> titleFor(request);
        };
    }

    private String defaultMessage(DocumentRequest request, String recipientName, String documentLabel,
                                  OrganizationEmailBranding branding) {
        if (request == null || request.getDocumentType() == null) {
            return message(recipientName, documentLabel, "Please review the attached document for your records.", branding);
        }
        String documentDescription = switch (request.getDocumentType()) {
            case EXPERIENCE_LETTER -> "This letter confirms your employment experience and relieving details with " + branding.organizationName() + ".";
            case EMPLOYMENT_VERIFICATION -> "This letter is provided for employment verification purposes.";
            case EMPLOYMENT_LETTER -> "This letter confirms your employment details with " + branding.organizationName() + ".";
            case I983 -> "Please review the attached Form I-983 for your records and any required next steps.";
            case OTHER, OFFER_LETTER -> "Please review the attached document for your records.";
        };
        return message(recipientName, documentLabel, documentDescription, branding);
    }

    private String message(String recipientName, String documentLabel, String documentDescription,
                           OrganizationEmailBranding branding) {
        return """
                Hi %s,

                Hope this email finds you well.

                Please find your %s attached. %s

                %s

                Please let us know if you need any further assistance.

                Thanks & Regards
                %s
                """.formatted(recipientName, documentLabel, documentDescription,
                emailTemplates.contactSentence(branding), emailTemplates.signatureBlock(branding));
    }

    private OrganizationEmailBranding emailBranding(DocumentRequest request) {
        if (request == null || !StringUtils.hasText(request.getOrganizationId())) return OrganizationEmailBranding.generic();
        return organizationRepository.findById(request.getOrganizationId())
                .map(OrganizationEmailBranding::from)
                .orElseGet(OrganizationEmailBranding::generic);
    }

    private String subjectText(String value) {
        return value.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").trim();
    }
}
