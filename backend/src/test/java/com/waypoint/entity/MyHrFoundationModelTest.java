package com.waypoint.entity;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.PayrollPeriod;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class MyHrFoundationModelTest {

    @Test
    void foundationModelsUseFinalCollectionNames() {
        assertThat(collectionName(Organization.class)).isEqualTo("organizations");
        assertThat(collectionName(OrganizationSettings.class)).isEqualTo("organization_settings");
        assertThat(collectionName(EmployeeProfile.class)).isEqualTo("employee_profiles");
        assertThat(collectionName(ReportingAssignment.class)).isEqualTo("reporting_assignments");
    }

    @Test
    void organizationNaturalKeysAreUnique() throws Exception {
        assertThat(index(Organization.class, "organizationCode").unique()).isTrue();
        assertThat(index(Organization.class, "organizationEmail").unique()).isTrue();
    }

    @Test
    void organizationSettingsAndEmployeeProfileCardinalityKeysAreUnique() throws Exception {
        assertThat(index(OrganizationSettings.class, "organizationId").unique()).isTrue();
        assertThat(index(EmployeeProfile.class, "userId").unique()).isTrue();
        CompoundIndex employeeIdIndex = EmployeeProfile.class.getAnnotation(CompoundIndex.class);
        assertThat(employeeIdIndex).isNotNull();
        assertThat(employeeIdIndex.name()).isEqualTo("organization_employee_unique");
        assertThat(employeeIdIndex.unique()).isTrue();
        assertThat(employeeIdIndex.def()).contains("organizationId", "employeeId");
        assertThat(EmployeeProfile.class.getDeclaredField("employeeId").getAnnotation(Indexed.class)).isNull();
    }

    @Test
    void reportingAssignmentsHaveRaceSafeActivePrimaryConstraint() {
        CompoundIndexes indexes = ReportingAssignment.class.getAnnotation(CompoundIndexes.class);

        assertThat(indexes).isNotNull();
        assertThat(indexes.value()).anySatisfy(index -> {
            assertThat(index.name()).isEqualTo("one_active_relationship_per_employee");
            assertThat(index.unique()).isTrue();
            assertThat(index.def()).contains("organizationId", "employeeUserId", "relationshipType");
            assertThat(index.partialFilter()).contains("isActive", "true");
        });
    }

    @Test
    void legacyAndNewUserRolesRemainSupported() {
        assertThat(UserRole.values()).containsExactlyInAnyOrder(
                UserRole.JOB_SEEKER,
                UserRole.EMPLOYER,
                UserRole.COMPANY_ADMIN,
                UserRole.HR,
                UserRole.EMPLOYEE,
                UserRole.WAYPOINT_ADMIN);
    }

    @Test
    void legacyUserWithoutOrganizationStillBuildsNormally() {
        User legacyUser = User.builder()
                .email("legacy@example.com")
                .passwordHash("hash")
                .role(UserRole.EMPLOYER)
                .accountStatus(AccountStatus.ACTIVE)
                .build();

        assertThat(legacyUser.getOrganizationId()).isNull();
        assertThat(legacyUser.getMustChangePassword()).isNull();
        assertThat(legacyUser.getRole()).isEqualTo(UserRole.EMPLOYER);
    }

    @Test
    void phaseOneEnumsContainOnlyRequiredFoundationValues() {
        assertThat(RelationshipType.values())
                .containsExactly(RelationshipType.PRIMARY_REPORTING_MANAGER);
        assertThat(PayrollPeriod.values())
                .containsExactly(PayrollPeriod.WEEKLY, PayrollPeriod.BIWEEKLY, PayrollPeriod.MONTHLY);
    }

    private String collectionName(Class<?> type) {
        return type.getAnnotation(Document.class).collection();
    }

    private Indexed index(Class<?> type, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        return field.getAnnotation(Indexed.class);
    }
}
