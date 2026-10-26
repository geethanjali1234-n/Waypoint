package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class OrganizationRegistrationCredentialDeliveryTest {

    private final EmailService emailService = mock(EmailService.class);
    private final EmailTemplateService templates = new EmailTemplateService();
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private boolean originalAdditive;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(OrganizationRegistrationCredentialDelivery.class);
        originalAdditive = logger.isAdditive();
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        logger.setAdditive(originalAdditive);
    }

    private OrganizationRegistrationCredentialDelivery delivery(boolean mailEnabled) {
        return new OrganizationRegistrationCredentialDelivery(
                emailService, templates, mailEnabled, "https://app.example/");
    }

    @Test
    void sendsLoginIdPasswordAndOrganizationOnlyToTheGivenVerifiedAddress() {
        delivery(true).deliver("admin@acme.example", "Acme Health", "Jane Admin", "ACMEADMIN", "TempPassword9!");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("admin@acme.example"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("Acme Health", "ACMEADMIN", "TempPassword9!", "https://app.example/myhr/login")
                .contains("new password");
        assertThat(appender.list).isEmpty();
    }

    @Test
    void smtpFailureIsReportedAsRecoverableAndNeverLogsThePassword() {
        doThrow(new IllegalStateException("smtp down TempPassword9!"))
                .when(emailService).sendHtmlEmail(anyString(), anyString(), anyString());

        assertThatThrownBy(() ->
                delivery(true).deliver("admin@acme.example", "Acme Health", "Jane Admin", "ACMEADMIN", "TempPassword9!"))
                .isInstanceOf(BusinessException.class)
                .hasMessage(OrganizationRegistrationCredentialDelivery.FAILURE_MESSAGE);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage()).contains("ACMEADMIN").doesNotContain("TempPassword9!");
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void disabledMailBlocksRegistrationInsteadOfPretendingToSend() {
        OrganizationRegistrationCredentialDelivery disabled = delivery(false);

        assertThatThrownBy(disabled::requireAvailable)
                .isInstanceOf(BusinessException.class)
                .hasMessage(OrganizationRegistrationCredentialDelivery.UNAVAILABLE_MESSAGE);
        assertThatThrownBy(() -> disabled.deliver("admin@acme.example", "Acme", "Jane", "ACMEADMIN", "TempPassword9!"))
                .isInstanceOf(BusinessException.class);
        verifyNoInteractions(emailService);
    }
}
