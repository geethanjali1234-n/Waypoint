package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.enums.UserRole;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MyHrCredentialDeliveryTest {

    private final EmailService emailService = mock(EmailService.class);
    private MyHrCredentialDelivery delivery;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        delivery = new MyHrCredentialDelivery(emailService,
                new MyHrCredentialEmailTemplateService(new EmailTemplateService()));
        ReflectionTestUtils.setField(delivery, "mailEnabled", true);
        ReflectionTestUtils.setField(delivery, "frontendBaseUrl", "https://app.example/");
        logger = (Logger) LoggerFactory.getLogger(MyHrCredentialDelivery.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void hrEmailGoesToTheGivenAddressWithHrWordingLoginIdLinkAndInstructions() {
        assertThat(delivery.deliver("hr@tardus.test", UserRole.HR, "Tardus Health", "TD001", "Temporary9!")).isTrue();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("hr@tardus.test"), eq(MyHrCredentialEmailTemplateService.SUBJECT),
                html.capture(), text.capture());
        assertThat(html.getValue()).contains("Tardus Health", "TD001", "Temporary9!", "HR account",
                "https://app.example/myhr/login", "new password").doesNotContain("Employee account");
        assertThat(text.getValue()).contains("Tardus Health", "Login ID: TD001", "Role: HR",
                "Temporary password: Temporary9!", "https://app.example/myhr/login", "new password");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void employeeEmailUsesEmployeeWording() {
        assertThat(delivery.deliver("emp@tardus.test", UserRole.EMPLOYEE, "Tardus Health", "TD002", "Temporary9!")).isTrue();

        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("emp@tardus.test"), anyString(), html.capture(), text.capture());
        assertThat(html.getValue()).contains("Employee account", "TD002").doesNotContain("HR account");
        assertThat(text.getValue()).contains("Role: Employee", "Login ID: TD002");
    }

    @Test
    void smtpFailureReturnsFalseAndLogsNeitherPasswordNorStackTrace() {
        doThrow(new IllegalStateException("smtp down Temporary9!"))
                .when(emailService).sendHtmlEmail(anyString(), anyString(), anyString(), anyString());

        assertThat(delivery.deliver("hr@tardus.test", UserRole.HR, "Tardus Health", "TD001", "Temporary9!")).isFalse();

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("TD001").doesNotContain("Temporary9!");
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void disabledMailIsReportedAsNotDeliveredInsteadOfSilentlySkipped() {
        ReflectionTestUtils.setField(delivery, "mailEnabled", false);

        assertThat(delivery.deliver("hr@tardus.test", UserRole.HR, "Tardus Health", "TD001", "Temporary9!")).isFalse();

        verifyNoInteractions(emailService);
    }

    @Test
    void realSmtpMessageCarriesPlainAndHtmlAlternatives() throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        JavaMailSenderEmailService real = new JavaMailSenderEmailService(sender);
        ReflectionTestUtils.setField(real, "mailEnabled", true);
        ReflectionTestUtils.setField(real, "fromAddress", "no-reply@waypoint.local");
        ReflectionTestUtils.setField(real, "fromName", "Waypoint");
        MyHrCredentialDelivery wired = new MyHrCredentialDelivery(real,
                new MyHrCredentialEmailTemplateService(new EmailTemplateService()));
        ReflectionTestUtils.setField(wired, "mailEnabled", true);
        ReflectionTestUtils.setField(wired, "frontendBaseUrl", "http://localhost:5173");

        assertThat(wired.deliver("emp@tardus.test", UserRole.EMPLOYEE, "Tardus Health", "TD002", "Temporary9!")).isTrue();

        message.saveChanges();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        message.writeTo(out);
        String raw = out.toString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("emp@tardus.test");
        assertThat(raw).contains("multipart/alternative", "text/plain", "text/html");
        verify(sender).send(message);
    }
}
