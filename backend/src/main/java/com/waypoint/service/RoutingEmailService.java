package com.waypoint.service;

import com.waypoint.service.google.GmailApiEmailService;
import com.waypoint.service.google.PlatformGmailApiEmailService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * The {@link EmailService} every caller receives. It adds exactly one decision in front of the existing SMTP sender:
 * a message that carries an organization identity routed to the Gmail API (see
 * {@link EmailSenderIdentity#usesGoogleSender()}) is offered to that organization's connected Google account first.
 *
 * <p>Everything else goes straight to {@link JavaMailSenderEmailService}, unchanged: platform emails (registration OTP,
 * credentials, job portal), organizations with no usable Google connection, and every message while
 * {@code waypoint.mail.enabled} is false.</p>
 *
 * <p>Fallback rules:</p>
 * <ul>
 *   <li>The Gmail path reports the message definitely was not sent (no/disconnected/revoked connection, domain or sender
 *       mismatch, no access token, Google rejected it, message too large): it is sent once through SMTP exactly as it
 *       always was (platform sender, organization display name and Reply-To). It is never sent through another
 *       organization's account.</li>
 *   <li>Google gave no answer, so the message may already be delivered: it is not re-sent. The failure is rethrown and
 *       handled by each caller as before (notification emails are best effort).</li>
 * </ul>
 */
@Slf4j
@Primary
@Service
public class RoutingEmailService implements EmailService {

    private final JavaMailSenderEmailService smtp;
    private final GmailApiEmailService gmail;
    private final PlatformGmailApiEmailService platformGmail;

    @Value("${waypoint.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${waypoint.google.platform-sender-enabled:false}")
    private boolean platformSenderEnabled;

    public RoutingEmailService(JavaMailSenderEmailService smtp, GmailApiEmailService gmail, PlatformGmailApiEmailService platformGmail) {
        this.smtp = smtp;
        this.gmail = gmail;
        this.platformGmail = platformGmail;
    }

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        if (viaPlatform(EmailSenderIdentity.none())) { platformGmail.send(to, subject, htmlBody, null, List.of()); return; }
        smtp.sendHtmlEmail(to, subject, htmlBody);
    }

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlBody, String plainTextBody) {
        if (viaPlatform(EmailSenderIdentity.none())) { platformGmail.send(to, subject, htmlBody, plainTextBody, List.of()); return; }
        smtp.sendHtmlEmail(to, subject, htmlBody, plainTextBody);
    }

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlBody, String plainTextBody, EmailSenderIdentity identity) {
        if (viaGoogle(identity) && trySend(identity, to, subject, htmlBody, plainTextBody, List.of())) return;
        if (platformSenderEnabled && organizationBound(identity)) {
            throw new OrganizationEmailAuthorizationRequired();
        }
        if (viaPlatform(identity)) { platformGmail.send(to, subject, htmlBody, plainTextBody, List.of()); return; }
        smtp.sendHtmlEmail(to, subject, htmlBody, plainTextBody, identity);
    }

    @Override
    public void sendHtmlEmailWithAttachment(String to, String subject, String htmlBody,
                                            String attachmentFileName, byte[] attachmentContent, String attachmentContentType) {
        if (viaPlatform(EmailSenderIdentity.none())) { platformGmail.send(to, subject, htmlBody, null,
                List.of(new EmailAttachment(attachmentFileName, attachmentContent, attachmentContentType))); return; }
        smtp.sendHtmlEmailWithAttachment(to, subject, htmlBody, attachmentFileName, attachmentContent, attachmentContentType);
    }

    @Override
    public void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, List<EmailAttachment> attachments) {
        if (viaPlatform(EmailSenderIdentity.none())) { platformGmail.send(to, subject, htmlBody, null, attachments); return; }
        smtp.sendHtmlEmailWithAttachments(to, subject, htmlBody, attachments);
    }

    @Override
    public void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, List<EmailAttachment> attachments,
                                             String fromDisplayName) {
        if (viaPlatform(EmailSenderIdentity.none())) { platformGmail.send(to, subject, htmlBody, null, attachments); return; }
        smtp.sendHtmlEmailWithAttachments(to, subject, htmlBody, attachments, fromDisplayName);
    }

    @Override
    public void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, List<EmailAttachment> attachments,
                                             EmailSenderIdentity identity) {
        if (viaGoogle(identity) && attachments != null && !attachments.isEmpty()
                && trySend(identity, to, subject, htmlBody, null, attachments)) {
            return;
        }
        if (platformSenderEnabled && organizationBound(identity)) {
            throw new OrganizationEmailAuthorizationRequired();
        }
        if (viaPlatform(identity)) { platformGmail.send(to, subject, htmlBody, null, attachments); return; }
        smtp.sendHtmlEmailWithAttachments(to, subject, htmlBody, attachments, identity);
    }

    /** Gmail is only tried for an identity that asks for it, and never while email delivery is switched off. */
    private boolean viaGoogle(EmailSenderIdentity identity) {
        return mailEnabled && identity != null && identity.usesGoogleSender();
    }

    /** Only global messages use the platform mailbox; organization-bound messages never do. */
    private boolean viaPlatform(EmailSenderIdentity identity) {
        return mailEnabled && platformSenderEnabled && !organizationBound(identity);
    }

    private boolean organizationBound(EmailSenderIdentity identity) {
        return identity != null && (identity.usesGoogleSender() || identity.displayName() != null || identity.replyTo() != null || identity.fromAddress() != null);
    }

    /** Safe outcome for production: organization notification email requires that organization's authorization. */
    public static class OrganizationEmailAuthorizationRequired extends RuntimeException {
        public OrganizationEmailAuthorizationRequired() { super("Organization Gmail authorization is required for notification email delivery"); }
    }

    /**
     * @return true when the Gmail API accepted the message; false when it definitely did not send and SMTP should
     * take over. Throws when delivery is uncertain.
     */
    private boolean trySend(EmailSenderIdentity identity, String to, String subject, String htmlBody, String plainTextBody,
                            List<EmailAttachment> attachments) {
        try {
            gmail.send(identity, to, subject, htmlBody, plainTextBody, attachments);
            return true;
        } catch (GmailApiEmailService.Unavailable notSent) {
            if (platformSenderEnabled) throw new OrganizationEmailAuthorizationRequired();
            log.warn("Email sent through SMTP instead of the organization's Google account [organizationId={}, reason={}]",
                    identity.gmailOrganizationId(), notSent.code());
            return false;
        }
        // GmailApiEmailService.DeliveryUncertain is deliberately not caught: the message may have been delivered.
    }
}
