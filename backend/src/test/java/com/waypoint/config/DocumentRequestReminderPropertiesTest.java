package com.waypoint.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentRequestReminderPropertiesTest {
    @Configuration
    @EnableConfigurationProperties(DocumentRequestReminderProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Config.class);

    @Test
    void defaultsToDisabledInChicagoTime() {
        runner.run(context -> {
            var properties = context.getBean(DocumentRequestReminderProperties.class);
            assertThat(properties.isEnabled()).isFalse();
            assertThat(properties.zone()).isEqualTo(ZoneId.of("America/Chicago"));
        });
    }

    @Test
    void bindsEnabledFlagAndZone() {
        runner.withPropertyValues("waypoint.myhr.document-request-reminders.enabled=true",
                        "waypoint.myhr.document-request-reminders.zone-id=America/New_York")
                .run(context -> {
                    var properties = context.getBean(DocumentRequestReminderProperties.class);
                    assertThat(properties.isEnabled()).isTrue();
                    assertThat(properties.zone()).isEqualTo(ZoneId.of("America/New_York"));
                });
    }

    @Test
    void invalidZoneFailsAtStartup() {
        runner.withPropertyValues("waypoint.myhr.document-request-reminders.zone-id=Not/AZone")
                .run(context -> assertThat(context).hasFailed());
    }
}
