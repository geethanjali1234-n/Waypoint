package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A provider that permanently refuses the organization's own From address at MAIL FROM gets exactly one retry from the
 * global sender. Nothing else is retried: those failures could mean the message was, or may yet be, delivered.
 */
class OrganizationSenderRejectionRetryTest {
    private static final String GLOBAL = "smtp-account@example.test";
    private static final String ORG = "hr@itap.example";

    /** What each attempt looked like at the moment it was handed to the transport. */
    private record Attempt(String from, String displayName, String to, String replyTo, String subject, boolean multipart) {}

    private final List<Attempt> attempts = new ArrayList<>();
    private final List<RuntimeException> failures = new ArrayList<>();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private JavaMailSenderEmailService mail;

    @BeforeEach
    void setUp() {
        JavaMailSender transport = mock(JavaMailSender.class);
        when(transport.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        doAnswer(call -> {
            MimeMessage message = call.getArgument(0);
            InternetAddress from = (InternetAddress) message.getFrom()[0];
            attempts.add(new Attempt(from.getAddress(), from.getPersonal(),
                    ((InternetAddress) message.getAllRecipients()[0]).getAddress(),
                    message.getReplyTo() == null ? null : ((InternetAddress) message.getReplyTo()[0]).getAddress(),
                    message.getSubject(), message.getContent() instanceof MimeMultipart));
            if (!failures.isEmpty()) throw failures.remove(0);
            return null;
        }).when(transport).send(any(MimeMessage.class));
        mail = new JavaMailSenderEmailService(transport);
        ReflectionTestUtils.setField(mail, "mailEnabled", true);
        ReflectionTestUtils.setField(mail, "fromAddress", GLOBAL);
        ReflectionTestUtils.setField(mail, "fromName", "Waypoint");
        logs.start();
        ((Logger) LoggerFactory.getLogger(JavaMailSenderEmailService.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(JavaMailSenderEmailService.class)).detachAppender(logs);
    }

    private static EmailSenderIdentity orgIdentity() {
        return new EmailSenderIdentity("HR ITAP", "hr@itap.example", ORG);
    }

    private static SMTPSenderFailedException senderRejected(int code, String address) throws Exception {
        return new SMTPSenderFailedException(new InternetAddress(address), "MAIL FROM", code, "sender not verified");
    }

    private void send(EmailSenderIdentity identity) {
        mail.sendHtmlEmail("recipient@example.test", "Offer letter", "<p>Hello</p>", null, identity);
    }

    @Test
    void definiteSenderRejection_retriesExactlyOnceFromTheGlobalSender_keepingEverythingElse() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(550, ORG)));

        send(orgIdentity());

        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).from()).isEqualTo(ORG);
        assertThat(attempts.get(1).from()).isEqualTo(GLOBAL);
        assertThat(attempts.get(1).displayName()).isEqualTo("HR ITAP");
        assertThat(attempts.get(1).replyTo()).isEqualTo("hr@itap.example");
        assertThat(attempts.get(1).to()).isEqualTo("recipient@example.test");
        assertThat(attempts.get(1).subject()).isEqualTo("Offer letter");
    }

    @Test
    void rejectionWrappedInSpringsFailedMessagesMap_isRecognisedToo() throws Exception {
        Map<Object, Exception> failed = new LinkedHashMap<>();
        failed.put(new Object(), senderRejected(553, ORG));
        failures.add(new MailSendException("Failed messages", null, failed));

        send(orgIdentity());

        assertThat(attempts).extracting(Attempt::from).containsExactly(ORG, GLOBAL);
    }

    @Test
    void attachmentEmailsRetryOnceToo_andKeepTheirAttachment() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(550, ORG)));

        mail.sendHtmlEmailWithAttachments("jane@example.test", "Offer", "<p>x</p>",
                List.of(new EmailAttachment("offer.pdf", new byte[] {1, 2}, "application/pdf")), orgIdentity());

        assertThat(attempts).extracting(Attempt::from).containsExactly(ORG, GLOBAL);
        assertThat(attempts).allSatisfy(attempt -> assertThat(attempt.multipart()).isTrue());
    }

    @Test
    void secondFailureIsReported_afterExactlyTwoAttempts() throws Exception {
        failures.add(new MailSendException("first", senderRejected(550, ORG)));
        failures.add(new MailSendException("second", new MessagingException("still failing")));

        assertThatThrownBy(() -> send(orgIdentity())).isInstanceOf(MailSendException.class).hasMessage("second");
        assertThat(attempts).hasSize(2);
    }

    @Test
    void ifTheGlobalSenderItselfIsRejected_itIsNotRetried() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(550, GLOBAL)));

        assertThatThrownBy(() -> send(EmailSenderIdentity.none())).isInstanceOf(MailSendException.class);
        assertThat(attempts).hasSize(1);
    }

    @Test
    void anIdentityWhoseFromIsTheGlobalAddressIsNotRetried() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(550, GLOBAL)));

        assertThatThrownBy(() -> send(new EmailSenderIdentity("HR ITAP", null, GLOBAL.toUpperCase()))).isInstanceOf(MailSendException.class);
        assertThat(attempts).hasSize(1);
    }

    @Test
    void rejectionOfSomeOtherAddressIsNotTreatedAsTheOrganizationSenderRejection() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(550, "someone-else@example.test")));

        assertThatThrownBy(() -> send(orgIdentity())).isInstanceOf(MailSendException.class);
        assertThat(attempts).hasSize(1);
    }

    @Test
    void temporarySenderRefusal_isNotRetried() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(451, ORG)));

        assertThatThrownBy(() -> send(orgIdentity())).isInstanceOf(MailSendException.class);
        assertThat(attempts).hasSize(1);
    }

    @Test
    void recipientRejection_isNotRetried() throws Exception {
        failures.add(new MailSendException("failed",
                new SMTPAddressFailedException(new InternetAddress("recipient@example.test"), "RCPT TO", 550, "no such user")));

        assertThatThrownBy(() -> send(orgIdentity())).isInstanceOf(MailSendException.class);
        assertThat(attempts).hasSize(1);
    }

    @Test
    void timeoutsConnectionAndAuthenticationFailuresAndAmbiguousSendFailures_areNotRetried() {
        failures.add(new MailSendException("timeout", new MessagingException("read timed out", new SocketTimeoutException())));
        assertThatThrownBy(() -> send(orgIdentity())).isInstanceOf(MailSendException.class);

        failures.add(new MailAuthenticationException("auth failed"));
        assertThatThrownBy(() -> send(orgIdentity())).isInstanceOf(MailAuthenticationException.class);

        failures.add(new MailSendException("generic", new SendFailedException("unspecified failure")));
        assertThatThrownBy(() -> send(orgIdentity())).isInstanceOf(MailSendException.class);

        assertThat(attempts).hasSize(3);
    }

    @Test
    void noRetryWhenNoOrganizationSenderWasUsed() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(550, ORG)));

        assertThatThrownBy(() -> send(new EmailSenderIdentity("HR ITAP", "hr@itap.example", null)))
                .isInstanceOf(MailSendException.class);
        assertThat(attempts).hasSize(1);
        assertThat(attempts.get(0).from()).isEqualTo(GLOBAL);
    }

    @Test
    void theFallbackLogNeverContainsAddressesOrMessageContent() throws Exception {
        failures.add(new MailSendException("failed", senderRejected(550, ORG)));

        send(orgIdentity());

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("retrying once from the global sender") && message.contains("550"))
                .noneMatch(message -> message.contains("itap.example") || message.contains(GLOBAL)
                        || message.contains("recipient@") || message.contains("Hello") || message.contains("Offer letter"));
    }
}
