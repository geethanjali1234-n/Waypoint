package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DevPasswordResetOtpLoggerTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private boolean originalAdditive;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(DevPasswordResetOtpLogger.class);
        originalAdditive = logger.isAdditive();
        logger.setAdditive(false);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setAdditive(originalAdditive);
    }

    @Test
    void disabledConfigurationDoesNotExposeOtp() {
        new DevPasswordResetOtpLogger(false)
                .output("developer@example.com", "123456", LocalDateTime.of(2026, 9, 10, 12, 10));

        assertThat(appender.list).isEmpty();
    }

    @Test
    void enabledConfigurationOutputsOnlyDevelopmentResetDetails() {
        new DevPasswordResetOtpLogger(true)
                .output("developer@example.com", "123456", LocalDateTime.of(2026, 9, 10, 12, 10));

        assertThat(appender.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
                .contains("MY HR DEV PASSWORD RESET OTP")
                .contains("developer@example.com")
                .contains("123456")
                .contains("2026-09-10T12:10")
                .doesNotContain("passwordHash", "JWT", "MongoDB", "R2"));
    }
}
