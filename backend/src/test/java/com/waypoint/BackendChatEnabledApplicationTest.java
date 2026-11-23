package com.waypoint;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.controller.MyHrChatController;
import com.waypoint.service.EventLocationMigrationService;
import com.waypoint.service.FileStorageMigrationService;
import com.waypoint.service.JobLocationMigrationService;
import com.waypoint.service.ResumeStorageMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "MONGODB_URI=mongodb://127.0.0.1:1/waypoint-test",
        "spring.data.mongodb.auto-index-creation=false",
        "waypoint.myhr.chat.enabled=true",
        "waypoint.myhr.chat.shared-dialogue.enabled=false",
        "waypoint.myhr.agent.enabled=false",
        "waypoint.documents.r2.enabled=false",
        "waypoint.documents.local.root=target/test-object-store",
        "MAIL_ENABLED=false",
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough"
})
class BackendChatEnabledApplicationTest {
    @MockitoBean private DatabaseInitializer databaseInitializer;
    @MockitoBean(name = "documentRequestIndexInitializer") private ApplicationRunner indexInitializer;
    @MockitoBean private ResumeStorageMigrationService resumeStorageMigrationService;
    @MockitoBean private EventLocationMigrationService eventLocationMigrationService;
    @MockitoBean private JobLocationMigrationService jobLocationMigrationService;
    @MockitoBean private FileStorageMigrationService fileStorageMigrationService;
    @Autowired private MyHrChatController controller;

    @Test void startsWithTheSingleChatControllerAndNoDatabaseInitializer() {
        assertThat(controller).isNotNull();
    }
}
