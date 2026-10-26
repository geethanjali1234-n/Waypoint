package com.waypoint.service;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.entity.Organization;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough",
        "MAIL_ENABLED=false"
})
@EnabledIfEnvironmentVariable(named = "TARDUS_ORGANIZATION_PROVISIONING_ENABLED", matches = "(?i:true)")
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MONGODB_DATABASE", matches = ".+")
class TardusOrganizationAtlasProvisioningTest {

    @MockitoBean private DatabaseInitializer databaseInitializer;
    @MockitoBean private ResumeStorageMigrationService resumeStorageMigrationService;
    @MockitoBean private EventLocationMigrationService eventLocationMigrationService;
    @MockitoBean private JobLocationMigrationService jobLocationMigrationService;
    @MockitoBean private FileStorageMigrationService fileStorageMigrationService;

    @Autowired private TardusOrganizationProvisioningService provisioningService;
    @Autowired private MongoTemplate mongoTemplate;

    @Test
    void createsOrFindsOnePersistentTardusOrganizationInConfiguredAtlasDatabase() {
        String expectedDatabase = System.getenv("MONGODB_DATABASE");
        String effectiveDatabase = mongoTemplate.getDb().getName();
        assertThat(effectiveDatabase).isEqualTo(expectedDatabase);

        TardusOrganizationProvisioningService.ProvisioningResult result = provisioningService.provision();
        Organization organization = result.organization();

        assertThat(organization.getId()).isNotBlank();
        assertThat(organization.getOrganizationCode()).isEqualTo("TD");

        System.out.println(result.created()
                ? "TARDUS_ORGANIZATION_PROVISIONING_STATUS=CREATED"
                : "TARDUS_ORGANIZATION_PROVISIONING_STATUS=ALREADY_EXISTS");
        System.out.println("TARDUS_ORGANIZATION_ID=" + organization.getId());
        System.out.println("TARDUS_ORGANIZATION_CODE=" + organization.getOrganizationCode());
        System.out.println("TARDUS_ORGANIZATION_NAME=" + organization.getOrganizationName());
        System.out.println("TARDUS_MONGODB_DATABASE=" + effectiveDatabase);
        System.out.println("TARDUS_NEXT_EMPLOYEE_SEQUENCE=" + organization.getNextEmployeeSequence());
    }
}
