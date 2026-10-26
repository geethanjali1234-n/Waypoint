package com.waypoint.service;

import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;
import com.waypoint.service.AuditActionCatalog.Descriptor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditActionCatalogTest {

    @Test
    void describesEveryEnumValueSoTheWritePathNeverProducesAnUncategorizedRow() {
        for (AuditActionType type : AuditActionType.values()) {
            Descriptor descriptor = AuditActionCatalog.describe(type);
            assertThat(descriptor.actionType()).isEqualTo(type);
            assertThat(descriptor.category()).isNotNull();
            assertThat(descriptor.label()).isNotBlank();
        }
    }

    @Test
    void categoryOfAndLabelOfAreConsistentWithDescribe() {
        Descriptor descriptor = AuditActionCatalog.describe(AuditActionType.ACCOUNT_STATUS_CHANGED);

        assertThat(AuditActionCatalog.categoryOf(AuditActionType.ACCOUNT_STATUS_CHANGED))
                .isEqualTo(descriptor.category());
        assertThat(AuditActionCatalog.labelOf(AuditActionType.ACCOUNT_STATUS_CHANGED))
                .isEqualTo(descriptor.label());
    }

    @Test
    void groupsReportingManagerActionsUnderReportingStructure() {
        assertThat(AuditActionCatalog.categoryOf(AuditActionType.REPORTING_MANAGER_ASSIGNED))
                .isEqualTo(AuditEventCategory.REPORTING_STRUCTURE);
        assertThat(AuditActionCatalog.categoryOf(AuditActionType.REPORTING_MANAGER_CHANGED))
                .isEqualTo(AuditEventCategory.REPORTING_STRUCTURE);
    }

    @Test
    void groupsProfileChangeActionsUnderProfile() {
        assertThat(AuditActionCatalog.categoryOf(AuditActionType.PROFILE_CHANGE_REQUESTED))
                .isEqualTo(AuditEventCategory.PROFILE);
        assertThat(AuditActionCatalog.categoryOf(AuditActionType.PROFILE_CHANGE_APPROVED))
                .isEqualTo(AuditEventCategory.PROFILE);
        assertThat(AuditActionCatalog.categoryOf(AuditActionType.PROFILE_CHANGE_REJECTED))
                .isEqualTo(AuditEventCategory.PROFILE);
        assertThat(AuditActionCatalog.categoryOf(AuditActionType.PROFILE_CHANGED))
                .isEqualTo(AuditEventCategory.PROFILE);
    }

    @Test
    void allReturnsTheSameCatalogInstanceEveryCall() {
        assertThat(AuditActionCatalog.all()).hasSize(AuditActionType.values().length);
        assertThat(AuditActionCatalog.all()).isSameAs(AuditActionCatalog.all());
    }
}
