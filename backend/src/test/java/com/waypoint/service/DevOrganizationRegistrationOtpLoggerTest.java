package com.waypoint.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.enums.OtpPurpose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DevOrganizationRegistrationOtpLoggerTest {

    private final Logger logger = (Logger) LoggerFactory.getLogger(DevOrganizationRegistrationOtpLogger.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    void disabledProductionDefaultDoesNotExposeOtp() {
        appender.start();
        logger.addAppender(appender);

        new DevOrganizationRegistrationOtpLogger(false)
                .output(OtpPurpose.ORGANIZATION_REGISTRATION, "123456", LocalDateTime.now().plusMinutes(10));

        assertThat(appender.list).isEmpty();
    }

    @Test
    void explicitlyEnabledDevelopmentModePrintsOnlyPurposeOtpAndExpiry() {
        appender.start();
        logger.addAppender(appender);
        LocalDateTime expiresAt = LocalDateTime.of(2026, 9, 23, 16, 30);

        new DevOrganizationRegistrationOtpLogger(true)
                .output(OtpPurpose.ORGANIZATION_REGISTRATION, "123456", expiresAt);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("WAYPOINT DEVELOPMENT OTP")
                    .contains("Purpose: ORGANIZATION_REGISTRATION")
                    .contains("OTP: 123456")
                    .contains("Expires: 2026-09-23T16:30")
                    .doesNotContain("password", "mongodb", "token");
        });
    }

    @Test
    void enabledModeIgnoresOtherOtpPurposes() {
        appender.start();
        logger.addAppender(appender);

        new DevOrganizationRegistrationOtpLogger(true)
                .output(OtpPurpose.PASSWORD_RESET, "123456", LocalDateTime.now().plusMinutes(10));

        assertThat(appender.list).isEmpty();
    }
}
