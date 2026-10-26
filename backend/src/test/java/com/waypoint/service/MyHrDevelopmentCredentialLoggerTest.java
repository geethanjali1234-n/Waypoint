package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class MyHrDevelopmentCredentialLoggerTest {

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private boolean originalAdditive;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(MyHrDevelopmentCredentialLogger.class);
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
    void disabledConfigurationDoesNotOutputCredentials() {
        new MyHrDevelopmentCredentialLogger(false)
                .output("DEV-LOGIN", "dummy-temporary-password", UserRole.EMPLOYEE);

        assertThat(appender.list).isEmpty();
    }

    @Test
    void enabledConfigurationOutputsOnlyRequiredDevelopmentCredentialFields() {
        new MyHrDevelopmentCredentialLogger(true)
                .output("DEV-LOGIN", "dummy-temporary-password", UserRole.HR);

        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getFormattedMessage())
                    .contains("My HR development credential")
                    .contains("Login ID: DEV-LOGIN")
                    .contains("Role: HR")
                    .contains("Temporary Password: dummy-temporary-password")
                    .doesNotContain("passwordHash", "JWT", "MongoDB");
        });
    }
}
