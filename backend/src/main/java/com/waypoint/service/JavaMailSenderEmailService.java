package com.waypoint.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

@Service
@RequiredArgsConstructor
@Slf4j
public class JavaMailSenderEmailService implements EmailService {

    private static final int ATTACHMENT_SEND_TIMEOUT_MS = 30_000;

    private final JavaMailSender javaMailSender;

    @Value("${waypoint.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${waypoint.mail.from-address}")
    private String fromAddress;

    @Value("${waypoint.mail.from-name}")
    private String fromName;

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlBody) {
        sendHtmlEmail(to, subject, htmlBody, null);
    }

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlBody, String plainTextBody) {
        sendHtmlEmail(to, subject, htmlBody, plainTextBody, EmailSenderIdentity.none());
    }

    @Override
    public void sendHtmlEmail(String to, String subject, String htmlBody, String plainTextBody, EmailSenderIdentity identity) {
        validate(to, subject, htmlBody);

        if (!mailEnabled) {
            log.warn("Email not sent [reason=MAIL_DISABLED]");
            return;
        }

        MimeMessage message = javaMailSender.createMimeMessage();

        try {
            // Setting both a plain and an HTML body requires multipart mode.
            MimeMessageHelper helper = new MimeMessageHelper(message, StringUtils.hasText(plainTextBody), "UTF-8");
            helper.setTo(to);
            applySender(helper, identity);
            helper.setSubject(subject);
            if (StringUtils.hasText(plainTextBody)) {
                helper.setText(plainTextBody, htmlBody);
            } else {
                helper.setText(htmlBody, true);
            }
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("Failed to build email message", exception);
        }

        send(javaMailSender, message, identity);
    }

    @Override
    public void sendHtmlEmailWithAttachment(String to, String subject, String htmlBody,
                                            String attachmentFileName, byte[] attachmentContent,
                                            String attachmentContentType) {
        sendHtmlEmailWithAttachments(to, subject, htmlBody,
                List.of(new EmailAttachment(attachmentFileName, attachmentContent, attachmentContentType)));
    }

    @Override
    public void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, List<EmailAttachment> attachments) {
        sendHtmlEmailWithAttachments(to, subject, htmlBody, attachments, (String) null);
    }

    @Override
    public void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, List<EmailAttachment> attachments,
                                             String fromDisplayName) {
        sendHtmlEmailWithAttachments(to, subject, htmlBody, attachments, EmailSenderIdentity.ofDisplayName(fromDisplayName));
    }

    @Override
    public void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, List<EmailAttachment> attachments,
                                             EmailSenderIdentity identity) {
        validate(to, subject, htmlBody);
        if (attachments == null || attachments.isEmpty()) {
            throw new IllegalArgumentException("Attachment content is required");
        }
        for (EmailAttachment attachment : attachments) {
            if (attachment == null || !StringUtils.hasText(attachment.fileName())) {
                throw new IllegalArgumentException("Attachment file name is required");
            }
            if (attachment.content() == null || attachment.content().length == 0) {
                throw new IllegalArgumentException("Attachment content is required");
            }
            if (!StringUtils.hasText(attachment.contentType())) {
                throw new IllegalArgumentException("Attachment content type is required");
            }
        }

        if (!mailEnabled) {
            throw new IllegalStateException(DELIVERY_DISABLED_MESSAGE);
        }

        JavaMailSender sender = senderForLargeMessages();
        MimeMessage message = sender.createMimeMessage();

        try {
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            applySender(helper, identity);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            for (EmailAttachment attachment : attachments) {
                helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()), attachment.contentType());
            }
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("Failed to build email message", exception);
        }

        send(sender, message, identity);
    }

    /**
     * From address is the global SMTP account unless the identity carries an organization address that
     * {@link OrganizationEmailIdentityResolver} has already checked against the provider-authorized senders.
     */
    private void applySender(MimeMessageHelper helper, EmailSenderIdentity identity)
            throws MessagingException, UnsupportedEncodingException {
        EmailSenderIdentity sender = identity == null ? EmailSenderIdentity.none() : identity;
        String from = StringUtils.hasText(sender.fromAddress()) ? sender.fromAddress() : fromAddress;
        helper.setFrom(from, StringUtils.hasText(sender.displayName()) ? sender.displayName() : fromName);
        if (StringUtils.hasText(sender.replyTo())) helper.setReplyTo(sender.replyTo());
    }

    /**
     * Sends the message; if the provider permanently rejects the organization's own From address at {@code MAIL FROM}
     * (before any message data is accepted), sends the same message once more from the global sender.
     *
     * <p>Only that case is retried: a rejection of the sender comes before the server accepts the message, so a retry
     * cannot duplicate it. Timeouts, connection and authentication failures, recipient rejections, temporary (4xx)
     * refusals and any failure after the message was handed over are rethrown untouched, because the outcome may be
     * ambiguous. A message already using the global sender is never retried.</p>
     */
    private void send(JavaMailSender transport, MimeMessage message, EmailSenderIdentity identity) {
        try {
            transport.send(message);
        } catch (MailException failure) {
            Integer returnCode = organizationSenderRejection(failure, identity);
            if (returnCode == null) throw failure;
            log.warn("Organization sender rejected by the SMTP provider; retrying once from the global sender [returnCode={}]",
                    returnCode);
            try {
                String displayName = StringUtils.hasText(identity.displayName()) ? identity.displayName() : fromName;
                message.setFrom(new InternetAddress(fromAddress, displayName, "UTF-8"));
            } catch (MessagingException | UnsupportedEncodingException rebuildFailure) {
                throw failure;
            }
            transport.send(message);
        }
    }

    /** The SMTP return code if the failure is a permanent rejection of this message's organization From address. */
    private Integer organizationSenderRejection(Throwable failure, EmailSenderIdentity identity) {
        if (identity == null || !StringUtils.hasText(identity.fromAddress())
                || identity.fromAddress().equalsIgnoreCase(fromAddress)) {
            return null;
        }
        java.util.List<Throwable> candidates = new java.util.ArrayList<>();
        for (Throwable current = failure; current != null && candidates.size() < 10; current = current.getCause()) {
            candidates.add(current);
            if (current instanceof MailSendException sendFailure && sendFailure.getMessageExceptions() != null) {
                candidates.addAll(Arrays.asList(sendFailure.getMessageExceptions()));
            }
        }
        for (Throwable candidate : candidates) {
            if (candidate instanceof SMTPSenderFailedException rejected && rejected.getReturnCode() >= 500
                    && rejected.getReturnCode() < 600 && rejected.getAddress() != null
                    && identity.fromAddress().equalsIgnoreCase(rejected.getAddress().getAddress())) {
                return rejected.getReturnCode();
            }
        }
        return null;
    }

    /**
     * The global SMTP timeouts (3s read / 5s write) suit small OTP and
     * notification mails. A message carrying several PDFs can need longer for
     * the server's final reply, and a read timeout after the body was sent
     * leaves a delivered mail looking failed. Attachment sends therefore use a
     * copy of the configured sender (same host, credentials and properties)
     * with longer timeouts; the shared sender and its settings are unchanged.
     */
    private JavaMailSender senderForLargeMessages() {
        if (!(javaMailSender instanceof JavaMailSenderImpl base)) return javaMailSender;
        JavaMailSenderImpl copy = new JavaMailSenderImpl();
        copy.setHost(base.getHost());
        copy.setPort(base.getPort());
        copy.setProtocol(base.getProtocol());
        copy.setUsername(base.getUsername());
        copy.setPassword(base.getPassword());
        copy.setDefaultEncoding(base.getDefaultEncoding());
        Properties properties = new Properties();
        properties.putAll(base.getJavaMailProperties());
        properties.put("mail.smtp.timeout", String.valueOf(ATTACHMENT_SEND_TIMEOUT_MS));
        properties.put("mail.smtp.writetimeout", String.valueOf(ATTACHMENT_SEND_TIMEOUT_MS));
        copy.setJavaMailProperties(properties);
        return copy;
    }

    private void validate(String recipient, String subject, String htmlBody) {
        if (!StringUtils.hasText(recipient)) {
            throw new IllegalArgumentException("Recipient is required");
        }
        if (!StringUtils.hasText(subject)) {
            throw new IllegalArgumentException("Email subject is required");
        }
        if (!StringUtils.hasText(htmlBody)) {
            throw new IllegalArgumentException("Email body is required");
        }
    }
}
