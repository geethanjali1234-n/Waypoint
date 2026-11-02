package com.waypoint.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class MailSenderAuthorizationTest {
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(MailSenderAuthorization.class);

    @BeforeEach
    void attach() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    @Test
    void missingOrBlankConfigurationAuthorizesNothing_andIsNotReportedAsMalformed() {
        for (String configured : new String[] {null, "", "   ", " , ,"}) {
            MailSenderAuthorization authorization = new MailSenderAuthorization(configured);
            assertThat(authorization.isConfigured()).isFalse();
            assertThat(authorization.authorizes("hr@itap.example")).isFalse();
        }
        assertThat(logs.list).noneMatch(event -> event.getLevel() == Level.WARN);
    }

    @Test
    void malformedEntriesAreIgnoredWithAWarning_andNeverAuthorizeAnything() {
        MailSenderAuthorization authorization = new MailSenderAuthorization(
                "*, *.itap.example, @itap.example, localhost, itap example, itap.example., hr@@itap.example, ok.example");

        assertThat(authorization.authorizes("hr@ok.example")).isTrue();
        assertThat(authorization.authorizes("hr@itap.example")).isFalse();
        assertThat(authorization.authorizes("hr@sub.itap.example")).isFalse();
        assertThat(authorization.authorizes("anyone@localhost")).isFalse();
        assertThat(authorization.authorizes("anyone@anywhere.example")).isFalse();

        assertThat(logs.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(7)
                .allSatisfy(event -> assertThat(event.getFormattedMessage()).contains("is not a valid domain or email address"));
    }

    @Test
    void warningsIdentifyTheEntryByPositionAndNeverEchoTheConfiguredValue() {
        new MailSenderAuthorization("good.example, *.secret-looking.example");

        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("#2"))
                .noneMatch(message -> message.contains("secret-looking") || message.contains("good.example"));
    }

    @Test
    void matchingIsExact_caseInsensitive_andCoversExactAddressesAsWellAsDomains() {
        MailSenderAuthorization authorization = new MailSenderAuthorization(" ITAP.example , ceo@solo.example ");

        assertThat(authorization.authorizes("HR@ITAP.EXAMPLE")).isTrue();
        assertThat(authorization.authorizes("ceo@solo.example")).isTrue();
        assertThat(authorization.authorizes("other@solo.example")).isFalse();
        assertThat(authorization.authorizes("hr@mail.itap.example")).isFalse();
        assertThat(authorization.authorizes("hr@evilitap.example")).isFalse();
        assertThat(authorization.authorizes("hr@itap.example.evil.test")).isFalse();
    }
}
