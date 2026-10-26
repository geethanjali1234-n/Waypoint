package com.waypoint.service;

import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JavaMailSenderEmailServiceTest {

    @Mock
    private JavaMailSender javaMailSender;

    private JavaMailSenderEmailService emailService;

    @BeforeEach
    void setUp() {
        emailService = new JavaMailSenderEmailService(javaMailSender);
        ReflectionTestUtils.setField(emailService, "mailEnabled", true);
        ReflectionTestUtils.setField(emailService, "fromAddress", "no-reply@waypoint.local");
        ReflectionTestUtils.setField(emailService, "fromName", "Waypoint");
    }

    @Test
    void sendHtmlEmail_mailDisabled_doesNotContactMailServer() {
        ReflectionTestUtils.setField(emailService, "mailEnabled", false);

        emailService.sendHtmlEmail("jobSeeker@example.com", "Subject", "<p>Body</p>");

        verifyNoInteractions(javaMailSender);
    }

    private MimeMessage newMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    @Test
    void sendHtmlEmail_validMessage_setsAllFieldsAndSendsOnce() throws Exception {
        MimeMessage message = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(message);

        emailService.sendHtmlEmail("jobSeeker@example.com", "Application received", "<p>Thanks!</p>");

        message.saveChanges();

        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("jobSeeker@example.com");
        assertThat(message.getSubject()).isEqualTo("Application received");

        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@waypoint.local");
        assertThat(from.getPersonal()).isEqualTo("Waypoint");

        assertThat(message.getContentType()).contains("text/html");

        verify(javaMailSender, times(1)).send(message);
    }

    @Test
    void sendHtmlEmail_javaMailSenderThrows_propagatesException() {
        MimeMessage message = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("SMTP unavailable")).when(javaMailSender).send(message);

        assertThatThrownBy(() ->
                emailService.sendHtmlEmail("jobSeeker@example.com", "Subject", "<p>Body</p>"))
                .isInstanceOf(MailSendException.class);
    }

    @Test
    void sendHtmlEmailWithAttachment_validMessage_attachesPdfWithFileNameAndContentType() throws Exception {
        MimeMessage message = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        byte[] pdf = "%PDF-1.7 offer".getBytes(StandardCharsets.US_ASCII);

        emailService.sendHtmlEmailWithAttachment("employee@example.com", "Offer Letter", "<p>Hi</p>",
                "Jane_Doe_Offer_Letter.pdf", pdf, "application/pdf");

        message.saveChanges();
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("employee@example.com");
        assertThat(message.getSubject()).isEqualTo("Offer Letter");
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@waypoint.local");
        assertThat(from.getPersonal()).isEqualTo("Waypoint");

        MimeMultipart multipart = (MimeMultipart) message.getContent();
        BodyPart attachment = null;
        boolean htmlBodyFound = false;
        for (int index = 0; index < multipart.getCount(); index++) {
            BodyPart part = multipart.getBodyPart(index);
            if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) attachment = part;
            else htmlBodyFound |= containsHtml(part, "<p>Hi</p>");
        }
        assertThat(htmlBodyFound).isTrue();
        assertThat(attachment).isNotNull();
        assertThat(attachment.getFileName()).isEqualTo("Jane_Doe_Offer_Letter.pdf");
        assertThat(attachment.getContentType()).startsWith("application/pdf");
        assertThat(attachment.getInputStream().readAllBytes()).isEqualTo(pdf);

        verify(javaMailSender, times(1)).send(message);
    }

    @Test
    void sendHtmlEmailWithAttachments_attachesEveryFileWithCleanNames() throws Exception {
        MimeMessage message = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(message);

        emailService.sendHtmlEmailWithAttachments("employee@example.com", "Offer Letter", "<p>Hi</p>", java.util.List.of(
                new EmailAttachment("Offer Letter - Jane Doe.pdf", "%PDF-1".getBytes(StandardCharsets.US_ASCII), "application/pdf"),
                new EmailAttachment("Direct Deposit Authorization.pdf", "%PDF-2".getBytes(StandardCharsets.US_ASCII), "application/pdf"),
                new EmailAttachment("I-9.pdf", "%PDF-3".getBytes(StandardCharsets.US_ASCII), "application/pdf"),
                new EmailAttachment("W-4 Form.pdf", "%PDF-4".getBytes(StandardCharsets.US_ASCII), "application/pdf")));

        message.saveChanges();
        java.util.List<String> names = new java.util.ArrayList<>();
        collectAttachmentNames((Part) message, names);
        assertThat(names).containsExactlyInAnyOrder("Offer Letter - Jane Doe.pdf", "Direct Deposit Authorization.pdf",
                "I-9.pdf", "W-4 Form.pdf");
        verify(javaMailSender, times(1)).send(message);
    }

    @Test
    void sendHtmlEmailWithAttachments_displayNameOverridesOnlyTheNameNotTheConfiguredAddress() throws Exception {
        MimeMessage message = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(message);

        emailService.sendHtmlEmailWithAttachments("employee@example.com", "Congratulations! Offer with Tardus Inc", "<p>Hi</p>",
                java.util.List.of(new EmailAttachment("a.pdf", new byte[]{1}, "application/pdf")), "HR Tardus Inc.");

        message.saveChanges();
        InternetAddress from = (InternetAddress) message.getFrom()[0];
        assertThat(from.getPersonal()).isEqualTo("HR Tardus Inc.");
        assertThat(from.getAddress()).isEqualTo("no-reply@waypoint.local");
        assertThat(message.getSubject()).isEqualTo("Congratulations! Offer with Tardus Inc");
    }

    private static void collectAttachmentNames(Part part, java.util.List<String> names) throws Exception {
        if (part.getContent() instanceof Multipart nested) {
            for (int i = 0; i < nested.getCount(); i++) collectAttachmentNames(nested.getBodyPart(i), names);
        } else if (Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            names.add(part.getFileName());
        }
    }

    @Test
    void sendHtmlEmailWithAttachment_mailDisabled_failsInsteadOfPretendingToSend() {
        ReflectionTestUtils.setField(emailService, "mailEnabled", false);

        assertThatThrownBy(() -> emailService.sendHtmlEmailWithAttachment("employee@example.com", "Subject",
                "<p>Body</p>", "offer.pdf", new byte[]{1}, "application/pdf"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(EmailService.DELIVERY_DISABLED_MESSAGE);

        verifyNoInteractions(javaMailSender);
    }

    @Test
    void sendHtmlEmailWithAttachment_missingAttachmentParts_areRejectedWithoutSending() {
        assertThatThrownBy(() -> emailService.sendHtmlEmailWithAttachment("employee@example.com", "Subject",
                "<p>Body</p>", " ", new byte[]{1}, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Attachment file name is required");
        assertThatThrownBy(() -> emailService.sendHtmlEmailWithAttachment("employee@example.com", "Subject",
                "<p>Body</p>", "offer.pdf", new byte[0], "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Attachment content is required");
        assertThatThrownBy(() -> emailService.sendHtmlEmailWithAttachment("employee@example.com", "Subject",
                "<p>Body</p>", "offer.pdf", new byte[]{1}, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Attachment content type is required");
        assertThatThrownBy(() -> emailService.sendHtmlEmailWithAttachment(" ", "Subject",
                "<p>Body</p>", "offer.pdf", new byte[]{1}, "application/pdf"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Recipient is required");

        verifyNoInteractions(javaMailSender);
    }

    @Test
    void sendHtmlEmailWithAttachment_javaMailSenderThrows_propagatesException() {
        MimeMessage message = newMimeMessage();
        when(javaMailSender.createMimeMessage()).thenReturn(message);
        doThrow(new MailSendException("SMTP unavailable")).when(javaMailSender).send(message);

        assertThatThrownBy(() -> emailService.sendHtmlEmailWithAttachment("employee@example.com", "Subject",
                "<p>Body</p>", "offer.pdf", new byte[]{1}, "application/pdf"))
                .isInstanceOf(MailSendException.class);
    }

    private static boolean containsHtml(Part part, String html) throws Exception {
        if (part.isMimeType("text/html")) return String.valueOf(part.getContent()).contains(html);
        if (part.getContent() instanceof Multipart nested) {
            for (int index = 0; index < nested.getCount(); index++) {
                if (containsHtml(nested.getBodyPart(index), html)) return true;
            }
        }
        return false;
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void sendHtmlEmail_blankRecipient_isRejectedWithoutSending(String recipient) {
        assertThatThrownBy(() -> emailService.sendHtmlEmail(recipient, "Subject", "<p>Body</p>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Recipient is required");

        verifyNoInteractions(javaMailSender);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void sendHtmlEmail_blankSubject_isRejectedWithoutSending(String subject) {
        assertThatThrownBy(() -> emailService.sendHtmlEmail("jobSeeker@example.com", subject, "<p>Body</p>"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email subject is required");

        verifyNoInteractions(javaMailSender);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void sendHtmlEmail_blankHtmlBody_isRejectedWithoutSending(String htmlBody) {
        assertThatThrownBy(() -> emailService.sendHtmlEmail("jobSeeker@example.com", "Subject", htmlBody))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Email body is required");

        verifyNoInteractions(javaMailSender);
    }
}
