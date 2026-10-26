package com.waypoint;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.service.EventLocationMigrationService;
import com.waypoint.service.FileStorageMigrationService;
import com.waypoint.service.JobLocationMigrationService;
import com.waypoint.service.ResumeStorageMigrationService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest(properties = {
        "MONGODB_URI=mongodb://localhost:27017/waypoint-test",
        "MAIL_HOST=localhost",
        "MAIL_USERNAME=test",
        "MAIL_PASSWORD=test",
        "R2_ENABLED=false",
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough",
        "waypoint.documents.r2.enabled=false",
        "waypoint.documents.local.root=target/test-object-store"
})
class BackendApplicationTests {

    @MockitoBean
    private DatabaseInitializer databaseInitializer;

    // Package-private startup runner that ensures Mongo indexes; this test has no database.
    @MockitoBean(name = "documentRequestIndexInitializer")
    private ApplicationRunner documentRequestIndexInitializer;

    @MockitoBean
    private ResumeStorageMigrationService resumeStorageMigrationService;

    @MockitoBean
    private EventLocationMigrationService eventLocationMigrationService;

    @MockitoBean
    private JobLocationMigrationService jobLocationMigrationService;

    @MockitoBean
    private FileStorageMigrationService fileStorageMigrationService;

    @Test
    void contextLoads() {
    }

}
