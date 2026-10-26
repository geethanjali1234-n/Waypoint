package com.waypoint.entity;

import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.PaystubStatus;
import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.repository.AuditLogRepository;
import com.waypoint.repository.PayrollRecordRepository;
import com.waypoint.repository.PaystubRepository;
import com.waypoint.repository.ProfileChangeRequestRepository;
import com.waypoint.repository.TimesheetRepository;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MyHrSharedDataModelTest {

    @Test
    void modelsUseFinalizedCollectionNames() {
        assertThat(collectionName(Timesheet.class)).isEqualTo("timesheets");
        assertThat(collectionName(PayrollRecord.class)).isEqualTo("payroll_records");
        assertThat(collectionName(Paystub.class)).isEqualTo("paystubs");
        assertThat(collectionName(ProfileChangeRequest.class)).isEqualTo("profile_change_requests");
        assertThat(collectionName(AuditLog.class)).isEqualTo("audit_logs");
    }

    @Test
    void timesheetContainsV1PeriodEntriesSupportingDocumentAndStatusWithoutPayrollState() throws Exception {
        Timesheet timesheet = Timesheet.builder()
                .organizationId("organization-1")
                .userId("employee-1")
                .profileId("profile-1")
                .reportingManagerUserId("manager-1")
                .periodStartDate(LocalDate.of(2026, 8, 31))
                .periodEndDate(LocalDate.of(2026, 9, 6))
                .entries(List.of(
                        TimesheetEntry.builder()
                                .workDate(LocalDate.of(2026, 8, 31))
                                .dayOfWeek("MONDAY")
                                .hoursWorked(new BigDecimal("8.00"))
                                .description("Foundation work")
                                .build(),
                        TimesheetEntry.builder()
                                .workDate(LocalDate.of(2026, 9, 1))
                                .dayOfWeek("TUESDAY")
                                .hoursWorked(new BigDecimal("8.00"))
                                .description("Testing")
                                .build()))
                .totalHours(new BigDecimal("16.00"))
                .supportingDocumentIds(List.of("document-1"))
                .status(TimesheetStatus.SUBMITTED)
                .submittedAt(LocalDateTime.now())
                .build();

        assertThat(timesheet.getOrganizationId()).isEqualTo("organization-1");
        assertThat(timesheet.getUserId()).isEqualTo("employee-1");
        assertThat(timesheet.getReportingManagerUserId()).isEqualTo("manager-1");
        assertThat(timesheet.getEntries()).satisfiesExactly(
                augustEntry -> {
                    assertThat(augustEntry.getWorkDate()).isEqualTo(LocalDate.of(2026, 8, 31));
                    assertThat(augustEntry.getHoursWorked()).isEqualTo(new BigDecimal("8.00"));
                    assertThat(augustEntry.getDescription()).isEqualTo("Foundation work");
                },
                septemberEntry -> {
                    assertThat(septemberEntry.getWorkDate()).isEqualTo(LocalDate.of(2026, 9, 1));
                    assertThat(septemberEntry.getHoursWorked()).isEqualTo(new BigDecimal("8.00"));
                    assertThat(septemberEntry.getDescription()).isEqualTo("Testing");
                });
        assertThat(fieldNames(TimesheetEntry.class)).contains(
                "workDate", "dayOfWeek", "hoursWorked", "description");
        assertThat(fieldNames(TimesheetEntry.class)).doesNotContain("payrollProcessed", "payrollRecordId");
        assertThat(fieldNames(Timesheet.class)).contains(
                "periodStartDate", "periodEndDate", "entries", "supportingDocumentIds", "status");
        assertThat(fieldNames(Timesheet.class)).doesNotContain(
                "payrollProcessed", "payrollRecordId", "payrollPeriod", "grossPay", "netPay",
                "hourlyRate", "taxes", "deductions");
        assertThat(TimesheetStatus.values()).containsExactly(
                TimesheetStatus.DRAFT,
                TimesheetStatus.SUBMITTED,
                TimesheetStatus.APPROVED,
                TimesheetStatus.REJECTED);
        assertThat(index(Timesheet.class, "submittedAt")).isNotNull();
        assertThat(compoundIndexNames(Timesheet.class)).contains(
                "organization_user_submittedAt",
                "organization_manager_submittedAt",
                "organization_submittedAt");
    }

    @Test
    void payrollRecordUsesDecimalMonetaryFieldsAndTimesheetIdReferences() throws Exception {
        PayrollRecord record = PayrollRecord.builder()
                .organizationId("organization-1")
                .userId("employee-1")
                .reportingManagerUserId("manager-1")
                .payrollPeriodStart(LocalDate.of(2026, 8, 1))
                .payrollPeriodEnd(LocalDate.of(2026, 8, 15))
                .timesheetIds(List.of("timesheet-1", "timesheet-2"))
                .totalEligibleHours(new BigDecimal("80.00"))
                .hourlyRate(new BigDecimal("25.00"))
                .grossPay(new BigDecimal("2000.00"))
                .totalDeductions(new BigDecimal("400.00"))
                .netPay(new BigDecimal("1600.00"))
                .build();

        assertThat(record.getOrganizationId()).isEqualTo("organization-1");
        assertThat(record.getUserId()).isEqualTo("employee-1");
        assertThat(record.getTimesheetIds()).containsExactly("timesheet-1", "timesheet-2");
        assertThat(fieldType(PayrollRecord.class, "grossPay")).isEqualTo(BigDecimal.class);
        assertThat(fieldType(PayrollRecord.class, "netPay")).isEqualTo(BigDecimal.class);
        assertThat(compoundIndexNames(PayrollRecord.class)).contains("user_payroll_period");
    }

    @Test
    void paystubReferencesPayrollAndStoredDocumentWithoutBinaryContent() throws Exception {
        Paystub paystub = Paystub.builder()
                .organizationId("organization-1")
                .userId("employee-1")
                .payrollRecordId("payroll-1")
                .paystubNumber("TD-PAY-0001")
                .version(1)
                .status(PaystubStatus.CURRENT)
                .documentId("document-1")
                .build();

        assertThat(paystub.getPayrollRecordId()).isEqualTo("payroll-1");
        assertThat(paystub.getUserId()).isEqualTo("employee-1");
        assertThat(paystub.getDocumentId()).isEqualTo("document-1");
        assertThat(index(Paystub.class, "paystubNumber").unique()).isTrue();
        assertThat(compoundIndex(Paystub.class, "payrollRecord_version_unique").unique()).isTrue();
        assertThat(List.of(Paystub.class.getDeclaredFields()))
                .extracting(Field::getName)
                .doesNotContain("file", "fileData", "binaryData", "pdf", "pdfBytes", "base64");
    }

    @Test
    void profileChangeRequestRetainsValuesRoutingAndReviewMetadata() throws Exception {
        ProfileChangeRequest request = ProfileChangeRequest.builder()
                .organizationId("organization-1")
                .requesterUserId("employee-1")
                .profileId("profile-1")
                .assignedToUserId("manager-1")
                .fieldName(ProfileChangeField.PHONE_NUMBER)
                .existingValue("555-0100")
                .requestedValue("555-0101")
                .requestReason("My phone number changed")
                .status(ProfileChangeStatus.PENDING)
                .processingComment("Identity verified")
                .requestedAt(LocalDateTime.now())
                .build();

        assertThat(request.getOrganizationId()).isEqualTo("organization-1");
        assertThat(request.getRequesterUserId()).isEqualTo("employee-1");
        assertThat(request.getExistingValue()).isEqualTo("555-0100");
        assertThat(request.getRequestedValue()).isEqualTo("555-0101");
        assertThat(request.getFieldName()).isEqualTo(ProfileChangeField.PHONE_NUMBER);
        assertThat(request.getRequestReason()).isEqualTo("My phone number changed");
        assertThat(request.getProcessingComment()).isEqualTo("Identity verified");
        assertThat(request.getAssignedToUserId()).isEqualTo("manager-1");
        assertThat(request.getStatus()).isEqualTo(ProfileChangeStatus.PENDING);

        ProfileChangeRequest requestWithoutReason = ProfileChangeRequest.builder()
                .fieldName(ProfileChangeField.NAME)
                .build();
        assertThat(requestWithoutReason.getRequestReason()).isNull();
        assertThat(fieldType(ProfileChangeRequest.class, "fieldName")).isEqualTo(ProfileChangeField.class);
        assertThat(fieldNames(ProfileChangeRequest.class)).contains("requestReason", "processingComment");
    }

    @Test
    void auditLogRetainsActorTargetBeforeAfterAndTimestamp() {
        AuditLog log = AuditLog.builder()
                .organizationId("organization-1")
                .performedByUserId("admin-1")
                .actionType(AuditActionType.ACCOUNT_STATUS_CHANGED)
                .entityType("USER")
                .entityId("employee-1")
                .previousValue(Map.of("accountStatus", "ACTIVE"))
                .updatedValue(Map.of("accountStatus", "INACTIVE"))
                .performedAt(LocalDateTime.now())
                .build();

        assertThat(log.getOrganizationId()).isEqualTo("organization-1");
        assertThat(log.getPerformedByUserId()).isEqualTo("admin-1");
        assertThat(log.getEntityType()).isEqualTo("USER");
        assertThat(log.getEntityId()).isEqualTo("employee-1");
        assertThat(log.getPreviousValue()).isEqualTo(Map.of("accountStatus", "ACTIVE"));
        assertThat(log.getUpdatedValue()).isEqualTo(Map.of("accountStatus", "INACTIVE"));
        assertThat(log.getPerformedAt()).isNotNull();
        assertThat(compoundIndexNames(AuditLog.class)).contains(
                "audit_org_time", "audit_org_actor_time", "organization_entity_performedAt");
    }

    @Test
    void controlledEnumsMatchFinalizedStates() {
        assertThat(PaystubStatus.values()).containsExactly(PaystubStatus.CURRENT, PaystubStatus.SUPERSEDED);
        assertThat(ProfileChangeStatus.values())
                .containsExactly(
                        ProfileChangeStatus.PENDING,
                        ProfileChangeStatus.APPROVED,
                        ProfileChangeStatus.REJECTED);
        assertThat(ProfileChangeField.values())
                .containsExactly(
                        ProfileChangeField.NAME,
                        ProfileChangeField.PERSONAL_EMAIL,
                        ProfileChangeField.PHONE_NUMBER,
                        ProfileChangeField.ADDRESS,
                        ProfileChangeField.DESIGNATION);
        assertThat(AuditActionType.values()).contains(
                AuditActionType.REPORTING_MANAGER_CHANGED,
                AuditActionType.PROFILE_CHANGED,
                AuditActionType.TIMESHEET_SUBMITTED,
                AuditActionType.PAYROLL_PROCESSED,
                AuditActionType.PAYSTUB_GENERATED);
    }

    @Test
    void repositoriesUseMongoRepositoryAndExposeOnlyFoundationQueries() {
        assertThat(MongoRepository.class).isAssignableFrom(TimesheetRepository.class);
        assertThat(MongoRepository.class).isAssignableFrom(PayrollRecordRepository.class);
        assertThat(MongoRepository.class).isAssignableFrom(PaystubRepository.class);
        assertThat(MongoRepository.class).isAssignableFrom(ProfileChangeRequestRepository.class);
        assertThat(MongoRepository.class).isAssignableFrom(AuditLogRepository.class);

        assertThat(TimesheetRepository.class.getDeclaredMethods()).hasSize(7);
        assertThat(PayrollRecordRepository.class.getDeclaredMethods()).hasSize(3);
        assertThat(PaystubRepository.class.getDeclaredMethods()).hasSize(4);
        assertThat(ProfileChangeRequestRepository.class.getDeclaredMethods()).hasSize(5);
        assertThat(AuditLogRepository.class.getDeclaredMethods()).hasSize(3);
    }

    private String collectionName(Class<?> type) {
        return type.getAnnotation(Document.class).collection();
    }

    private Indexed index(Class<?> type, String fieldName) throws Exception {
        return type.getDeclaredField(fieldName).getAnnotation(Indexed.class);
    }

    private Class<?> fieldType(Class<?> type, String fieldName) throws Exception {
        return type.getDeclaredField(fieldName).getType();
    }

    private List<String> fieldNames(Class<?> type) {
        return List.of(type.getDeclaredFields()).stream()
                .map(Field::getName)
                .toList();
    }

    private List<String> compoundIndexNames(Class<?> type) {
        return List.of(type.getAnnotation(CompoundIndexes.class).value()).stream()
                .map(CompoundIndex::name)
                .toList();
    }

    private CompoundIndex compoundIndex(Class<?> type, String name) {
        return List.of(type.getAnnotation(CompoundIndexes.class).value()).stream()
                .filter(index -> index.name().equals(name))
                .findFirst()
                .orElseThrow();
    }
}
