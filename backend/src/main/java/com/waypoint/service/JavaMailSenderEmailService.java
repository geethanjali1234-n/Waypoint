package com.waypoint.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
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
            helper.setFrom(fromAddress, fromName);
            helper.setSubject(subject);
            if (StringUtils.hasText(plainTextBody)) {
                helper.setText(plainTextBody, htmlBody);
            } else {
                helper.setText(htmlBody, true);
            }
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("Failed to build email message", exception);
        }

        javaMailSender.send(message);
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
        sendHtmlEmailWithAttachments(to, subject, htmlBody, attachments, null);
    }

    @Override
    public void sendHtmlEmailWithAttachments(String to, String subject, String htmlBody, List<EmailAttachment> attachments,
                                             String fromDisplayName) {
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
            helper.setFrom(fromAddress, StringUtils.hasText(fromDisplayName) ? fromDisplayName : fromName);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            for (EmailAttachment attachment : attachments) {
                helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()), attachment.contentType());
            }
        } catch (MessagingException | UnsupportedEncodingException exception) {
            throw new IllegalStateException("Failed to build email message", exception);
        }

        sender.send(message);
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
