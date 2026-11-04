package com.waypoint.service;

import com.waypoint.service.google.GmailApiEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class RoutingEmailServiceTest {

    private final JavaMailSenderEmailService smtp = mock(JavaMailSenderEmailService.class);
    private final GmailApiEmailService gmail = mock(GmailApiEmailService.class);
    private RoutingEmailService router;

    private final EmailSenderIdentity google = new EmailSenderIdentity("HR ITAP", "hr@itap.example", null)
            .withGoogleSender("org-itap", "admin@itap.example");
    private final EmailSenderIdentity smtpOnly = new EmailSenderIdentity("HR ITAP", "hr@itap.example", null);
    private final List<EmailAttachment> attachments = List.of(new EmailAttachment("a.pdf", new byte[]{1}, "application/pdf"));

    @BeforeEach
    void setUp() {
        router = new RoutingEmailService(smtp, gmail);
        ReflectionTestUtils.setField(router, "mailEnabled", true);
    }

    // ------------------------------------------------------------------ organization mail through Google

    @Test
    void anIdentityRoutedToGoogleIsSentThroughTheGmailApiAndNotBySmtp() {
        router.sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, google);

        verify(gmail).send(eq(google), eq("to@x.example"), eq("Subject"), eq("<p>Hi</p>"), isNull(), eq(List.of()));
        verifyNoInteractions(smtp);
    }

    @Test
    void attachmentMailIsAlsoSentThroughTheGmailApi() {
        router.sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, google);

        verify(gmail).send(eq(google), eq("to@x.example"), eq("Subject"), eq("<p>Hi</p>"), isNull(), eq(attachments));
        verifyNoInteractions(smtp);
    }

    // ------------------------------------------------------------------ approved fallback: nothing was sent

    @Test
    void whenGmailDefinitelyDidNotSendTheMessageGoesOnceBySmtpWithTheSameIdentity() {
        doThrow(new GmailApiEmailService.Unavailable("NOT_CONNECTED")).when(gmail)
                .send(any(), any(), any(), any(), any(), anyList());

        router.sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", "Hi", google);

        verify(smtp).sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", "Hi", google);
    }

    @Test
    void attachmentMailFallsBackToSmtpTheSameWay() {
        doThrow(new GmailApiEmailService.Unavailable("REAUTH_REQUIRED")).when(gmail)
                .send(any(), any(), any(), any(), any(), anyList());

        router.sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, google);

        verify(smtp).sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, google);
    }

    // ------------------------------------------------------------------ uncertain delivery is never re-sent

    @Test
    void whenGmailGaveNoAnswerTheMessageIsNotResentBySmtp() {
        doThrow(new GmailApiEmailService.DeliveryUncertain()).when(gmail).send(any(), any(), any(), any(), any(), anyList());

        assertThatThrownBy(() -> router.sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, google))
                .isInstanceOf(GmailApiEmailService.DeliveryUncertain.class);
        assertThatThrownBy(() -> router.sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, google))
                .isInstanceOf(GmailApiEmailService.DeliveryUncertain.class);

        verifyNoInteractions(smtp);
    }

    // ------------------------------------------------------------------ everything else is plain SMTP, unchanged

    @Test
    void organizationMailWithoutAGoogleSenderGoesStraightToSmtp() {
        router.sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, smtpOnly);
        router.sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, null);
        router.sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, EmailSenderIdentity.none());
        router.sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, smtpOnly);

        verify(smtp).sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, smtpOnly);
        verify(smtp).sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, (EmailSenderIdentity) null);
        verify(smtp).sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, smtpOnly);
        verifyNoInteractions(gmail);
    }

    @Test
    void platformEmailsWithNoIdentityAreAlwaysSentBySmtp() {
        router.sendHtmlEmail("user@x.example", "Your code", "<p>123456</p>");
        router.sendHtmlEmail("user@x.example", "Your code", "<p>123456</p>", "123456");
        router.sendHtmlEmailWithAttachment("user@x.example", "Doc", "<p>x</p>", "a.pdf", new byte[]{1}, "application/pdf");
        router.sendHtmlEmailWithAttachments("user@x.example", "Doc", "<p>x</p>", attachments);
        router.sendHtmlEmailWithAttachments("user@x.example", "Doc", "<p>x</p>", attachments, "HR Name");

        verify(smtp).sendHtmlEmail("user@x.example", "Your code", "<p>123456</p>");
        verify(smtp).sendHtmlEmail("user@x.example", "Your code", "<p>123456</p>", "123456");
        verify(smtp).sendHtmlEmailWithAttachment("user@x.example", "Doc", "<p>x</p>", "a.pdf", new byte[]{1}, "application/pdf");
        verify(smtp).sendHtmlEmailWithAttachments("user@x.example", "Doc", "<p>x</p>", attachments);
        verify(smtp).sendHtmlEmailWithAttachments("user@x.example", "Doc", "<p>x</p>", attachments, "HR Name");
        verifyNoInteractions(gmail);
    }

    @Test
    void whileEmailDeliveryIsSwitchedOffNothingIsOfferedToGoogle() {
        ReflectionTestUtils.setField(router, "mailEnabled", false);

        router.sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, google);
        router.sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, google);

        verify(smtp).sendHtmlEmail("to@x.example", "Subject", "<p>Hi</p>", null, google);
        verify(smtp).sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", attachments, google);
        verifyNoInteractions(gmail);
    }

    @Test
    void anEmptyAttachmentListKeepsTheExistingSmtpBehaviour() {
        router.sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", List.of(), google);

        verify(smtp).sendHtmlEmailWithAttachments("to@x.example", "Subject", "<p>Hi</p>", List.of(), google);
        verify(gmail, never()).send(any(), any(), any(), any(), any(), anyList());
    }

    @Test
    void theRouterIsTheEmailServiceCallersReceive() {
        assertThat(RoutingEmailService.class.isAnnotationPresent(org.springframework.context.annotation.Primary.class)).isTrue();
        assertThat(EmailService.class.isAssignableFrom(RoutingEmailService.class)).isTrue();
    }
}
