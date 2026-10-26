package com.waypoint.entity;

import com.waypoint.enums.AuditActionType;
import com.waypoint.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.Indexed;

import java.lang.reflect.Field;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MyHrV1SafetyModelTest {

    @Test
    void notificationRetainsIndexedOrganizationScope() throws Exception {
        Field organizationId = Notification.class.getDeclaredField("organizationId");
        assertThat(organizationId.getAnnotation(Indexed.class)).isNotNull();
        assertThat(NotificationRepository.class.getDeclaredMethod(
                "findByOrganizationIdAndRecipientUserIdOrderByCreatedAtDesc",
                String.class, String.class, org.springframework.data.domain.Pageable.class)).isNotNull();
    }

    @Test
    void auditActionsCoverActiveV1Workflows() {
        assertThat(AuditActionType.values()).contains(
                AuditActionType.HR_ACCOUNT_CREATED,
                AuditActionType.EMPLOYEE_ACCOUNT_CREATED,
                AuditActionType.REPORTING_MANAGER_CHANGED,
                AuditActionType.TIMESHEET_CREATED,
                AuditActionType.TIMESHEET_SUBMITTED,
                AuditActionType.TIMESHEET_DOCUMENT_UPLOADED,
                AuditActionType.DOCUMENT_REQUEST_CREATED,
                AuditActionType.DOCUMENT_REQUEST_FULFILLED,
                AuditActionType.DOCUMENT_REQUEST_CANCELLED,
                AuditActionType.PROFILE_CHANGE_REQUESTED,
                AuditActionType.PROFILE_CHANGE_APPROVED,
                AuditActionType.PROFILE_CHANGE_REJECTED,
                AuditActionType.REPORT_GENERATED,
                AuditActionType.PASSWORD_CHANGED);
    }

    @Test
    void deferredPayrollAndPaystubModelsRemainPresent() {
        assertThat(List.of(PayrollRecord.class.getDeclaredFields())).isNotEmpty();
        assertThat(List.of(Paystub.class.getDeclaredFields())).isNotEmpty();
    }
}
