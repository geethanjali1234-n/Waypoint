package com.waypoint.service;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough",
        "MAIL_ENABLED=false",
        "DATABASE_INITIALIZER_ENABLED=false",
        "RESUME_STORAGE_MIGRATION_ENABLED=false",
        "FILE_STORAGE_MIGRATION_ENABLED=false",
        "EVENT_LOCATION_MIGRATION_ENABLED=false",
        "JOB_LOCATION_MIGRATION_ENABLED=false",
        "MYHR_DEV_PROVISIONING_ENABLED=false",
        "MONGODB_AUTO_INDEX_CREATION=false",
        "R2_ENABLED=false"
})
@EnabledIfEnvironmentVariable(named = "WAYPOINT_ADMIN_PROVISIONING_ENABLED", matches = "(?i:true)")
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MONGODB_DATABASE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "WAYPOINT_ADMIN_LOGIN_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "WAYPOINT_ADMIN_EMAIL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "WAYPOINT_ADMIN_TEMP_PASSWORD", matches = ".{8,}")
class WaypointAdminAtlasProvisioningTest {

    @MockitoBean DatabaseInitializer databaseInitializer;
    @MockitoBean ResumeStorageMigrationService resumeStorageMigrationService;
    @MockitoBean EventLocationMigrationService eventLocationMigrationService;
    @MockitoBean JobLocationMigrationService jobLocationMigrationService;
    @MockitoBean FileStorageMigrationService fileStorageMigrationService;

    @Autowired WaypointAdminProvisioningService provisioningService;
    @Autowired UserRepository userRepository;
    @Autowired MongoTemplate mongoTemplate;

    @Test
    void provisionsExactlyOneWaypointAdminInExplicitlyConfiguredAtlasDatabase() {
        assertThat(mongoTemplate.getDb().getName()).isEqualTo(System.getenv("MONGODB_DATABASE"));

        WaypointAdminProvisioningService.ProvisioningResult result = provisioningService.provision(
                System.getenv("WAYPOINT_ADMIN_LOGIN_ID"),
                System.getenv("WAYPOINT_ADMIN_EMAIL"),
                System.getenv("WAYPOINT_ADMIN_TEMP_PASSWORD"));

        assertThat(userRepository.findByRole(UserRole.WAYPOINT_ADMIN)).singleElement().satisfies(user -> {
            assertThat(user.getId()).isEqualTo(result.userId());
            assertThat(user.getRole()).isEqualTo(UserRole.WAYPOINT_ADMIN);
            assertThat(user.getOrganizationId()).isNull();
            assertThat(user.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
            assertThat(user.getIsEmailVerified()).isTrue();
            assertThat(user.getMustChangePassword()).isTrue();
            assertThat(user.getPasswordHash()).isNotBlank();
        });

        System.out.println(result.created()
                ? "WAYPOINT_ADMIN_PROVISIONING_STATUS=CREATED"
                : "WAYPOINT_ADMIN_PROVISIONING_STATUS=ALREADY_EXISTS");
    }
}
