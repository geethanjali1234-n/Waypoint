package com.waypoint.service;

import com.waypoint.dto.myhr.TimesheetEntryRequest;
import com.waypoint.dto.myhr.TimesheetReviewRequest;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.dto.myhr.TimesheetUpsertRequest;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.Timesheet;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.TimesheetEntryType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.entity.AuditLog;
import com.waypoint.entity.TimesheetEntry;
import com.waypoint.repository.AuditLogRepository;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.StoredDocumentRepository;
import com.waypoint.repository.TimesheetRepository;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.enums.PayrollPeriod;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.waypoint.enums.NotificationType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimesheetServiceTest {

    @Mock
    private TimesheetRepository timesheetRepository;

    @Mock
    private ReportingAssignmentRepository reportingAssignmentRepository;

    @Mock
    private EmployeeProfileRepository employeeProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private StoredDocumentRepository storedDocumentRepository;

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private OrganizationSettingsRepository organizationSettingsRepository;

    @Mock
    private InAppNotificationService inAppNotificationService;

    @Mock
    private MyHrAuthenticatedUserService authenticatedUserService;

    @InjectMocks
    private TimesheetService service;

    @Test
    void rejectsForeignOrWrongTypeAttachmentsBeforeSaving() {
        var actor = actor("employee-1", UserRole.EMPLOYEE);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        var base = request(TimesheetStatus.DRAFT, new BigDecimal("8"));
        var payload = new TimesheetUpsertRequest(base.periodStartDate(), base.periodEndDate(),
                base.entries(), List.of("foreign-doc"), base.status(), null, null);
        for (var document : List.of(
                com.waypoint.entity.StoredDocument.builder().organizationId("other-org").ownerUserId(actor.userId())
                        .documentType(com.waypoint.enums.DocumentType.TIMESHEET_SUPPORTING_DOCUMENT).build(),
                com.waypoint.entity.StoredDocument.builder().organizationId(actor.organizationId()).ownerUserId("another-user")
                        .documentType(com.waypoint.enums.DocumentType.TIMESHEET_SUPPORTING_DOCUMENT).build(),
                com.waypoint.entity.StoredDocument.builder().organizationId(actor.organizationId()).ownerUserId(actor.userId())
                        .documentType(com.waypoint.enums.DocumentType.EMPLOYEE_PROFILE_DOCUMENT).build())) {
            when(storedDocumentRepository.findById("foreign-doc")).thenReturn(Optional.of(document));
            assertThatThrownBy(() -> service.create(actor.userId(), payload))
                    .isInstanceOf(com.waypoint.exception.ForbiddenException.class);
        }
        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void employeeCanSaveDraftWithCalculatedEntries() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        ReportingAssignment assignment = assignment(actor.userId(), "hr-1");
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment));
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of());
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> {
            Timesheet saved = invocation.getArgument(0);
            saved.setId("timesheet-1");
            return saved;
        });
        when(employeeProfileRepository.findByUserId(actor.userId())).thenReturn(Optional.empty());
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(user(actor.userId(), UserRole.EMPLOYEE)));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(user("hr-1", UserRole.HR)));

        TimesheetResponse response = service.create(actor.userId(), request(TimesheetStatus.DRAFT, new BigDecimal("8")));

        assertThat(response.id()).isEqualTo("timesheet-1");
        assertThat(response.status()).isEqualTo(TimesheetStatus.DRAFT);
        assertThat(response.totalHours()).isEqualByComparingTo("8.00");
        assertThat(response.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.dayOfWeek()).isEqualTo("MONDAY");
            assertThat(entry.hoursWorked()).isEqualByComparingTo("8.00");
        });
    }

    @Test
    void listResolvesTheSupportingDocumentFileNameForDownload() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        Timesheet withDocument = timesheet("ts-doc", actor.organizationId(), actor.userId(), TimesheetStatus.SUBMITTED);
        withDocument.setSupportingDocumentIds(List.of("doc-1"));

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of(withDocument));
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());
        when(storedDocumentRepository.findAllById(anyCollection())).thenReturn(List.of(
                com.waypoint.entity.StoredDocument.builder().id("doc-1").fileName("august_hours.pdf").build()));

        List<TimesheetResponse> visible = service.listForUser(actor.userId());

        assertThat(visible).singleElement().satisfies(response -> {
            assertThat(response.supportingDocuments()).singleElement().satisfies(document -> {
                assertThat(document.id()).isEqualTo("doc-1");
                assertThat(document.fileName()).isEqualTo("august_hours.pdf");
            });
        });
    }

    @Test
    void hydratesTimesheetListViaBatchLookups() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("hr-1", UserRole.HR);
        Timesheet ts1 = timesheet("ts-1", actor.organizationId(), "emp-1", TimesheetStatus.SUBMITTED);
        ts1.setReportingManagerUserId("hr-1");
        ts1.setSupportingDocumentIds(List.of("doc-batch-1"));

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of());
        when(timesheetRepository.findByOrganizationIdAndReportingManagerUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of(ts1));

        when(userRepository.findAllById(any())).thenReturn(List.of(
                User.builder().id("emp-1").loginId("TD002").role(UserRole.EMPLOYEE).build(),
                User.builder().id("hr-1").loginId("TD001").role(UserRole.HR).build()));
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of(
                EmployeeProfile.builder().userId("emp-1").employeeId("EMP-002").firstName("John").lastName("Doe").build(),
                EmployeeProfile.builder().userId("hr-1").employeeId("HR-001").firstName("Jane").lastName("Smith").build()));
        when(storedDocumentRepository.findAllById(any())).thenReturn(List.of(
                com.waypoint.entity.StoredDocument.builder().id("doc-batch-1").fileName("invoice.pdf").build()));

        List<TimesheetResponse> visible = service.listForUser(actor.userId());

        assertThat(visible).singleElement().satisfies(response -> {
            assertThat(response.employeeId()).isEqualTo("EMP-002");
            assertThat(response.employeeName()).isEqualTo("John Doe");
            assertThat(response.employeeRole()).isEqualTo(UserRole.EMPLOYEE);
            assertThat(response.reportingManagerLoginId()).isEqualTo("TD001");
            assertThat(response.reportingManagerName()).isEqualTo("Jane Smith");
            assertThat(response.supportingDocuments()).singleElement().satisfies(document ->
                    assertThat(document.fileName()).isEqualTo("invoice.pdf"));
        });
    }

    @Test
    void rejectsDailyHoursAboveV1LimitBeforeSaving() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);

        assertThatThrownBy(() -> service.create(
                actor.userId(), request(TimesheetStatus.DRAFT, new BigDecimal("24.01"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("between 0 and 24");

        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void allowsSubmittedZeroHourTimesheetWhenEntriesAreAccountedNonWorkDays() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        ReportingAssignment assignment = assignment(actor.userId(), "hr-1");
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment));
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of());
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> {
            Timesheet saved = invocation.getArgument(0);
            saved.setId("leave-timesheet-1");
            return saved;
        });
        when(employeeProfileRepository.findByUserId(actor.userId())).thenReturn(Optional.empty());
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(user(actor.userId(), UserRole.EMPLOYEE)));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(user("hr-1", UserRole.HR)));

        TimesheetUpsertRequest leaveRequest = new TimesheetUpsertRequest(
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 1),
                List.of(new TimesheetEntryRequest(
                        LocalDate.of(2026, 9, 1), BigDecimal.ZERO, TimesheetEntryType.SICK_LEAVE, "Sick leave")),
                null,
                TimesheetStatus.SUBMITTED,
                null,
                null);

        TimesheetResponse response = service.create(actor.userId(), leaveRequest);

        assertThat(response.status()).isEqualTo(TimesheetStatus.SUBMITTED);
        assertThat(response.totalHours()).isEqualByComparingTo("0.00");
        assertThat(response.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.entryType()).isEqualTo(TimesheetEntryType.SICK_LEAVE);
            assertThat(entry.hoursWorked()).isEqualByComparingTo("0.00");
        });
    }

    @Test
    void rejectsEntriesForFutureDates() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);

        LocalDate future = LocalDate.now().plusDays(3);
        TimesheetUpsertRequest futureRequest = new TimesheetUpsertRequest(
                LocalDate.now().minusDays(1),
                future,
                List.of(new TimesheetEntryRequest(future, new BigDecimal("8.00"), "Future work")),
                null,
                TimesheetStatus.SUBMITTED,
                null,
                null);

        assertThatThrownBy(() -> service.create(actor.userId(), futureRequest))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("future dates");

        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void hrCanSeeOwnAndDirectReportTimesheetsButNotUnassignedOrganizationRecords() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("hr-1", UserRole.HR);
        Timesheet own = timesheet("own", actor.organizationId(), actor.userId(), TimesheetStatus.DRAFT);
        Timesheet directReport = timesheet("direct", actor.organizationId(), "employee-1", TimesheetStatus.SUBMITTED);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of(own));
        when(timesheetRepository.findByOrganizationIdAndReportingManagerUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of(directReport));
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        List<TimesheetResponse> visible = service.listForUser(actor.userId());

        assertThat(visible).extracting(TimesheetResponse::id)
                .containsExactlyInAnyOrder("own", "direct")
                .doesNotContain("unrelated");
    }

    @Test
    void hrDoesNotSeeDirectReportDraftsInTimesheetList() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("hr-1", UserRole.HR);
        Timesheet ownDraft = timesheet("own-draft", actor.organizationId(), actor.userId(), TimesheetStatus.DRAFT);
        Timesheet directReportDraft = timesheet("emp-draft", actor.organizationId(), "employee-1", TimesheetStatus.DRAFT);
        Timesheet directReportSubmitted = timesheet("emp-submitted", actor.organizationId(), "employee-1", TimesheetStatus.SUBMITTED);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of(ownDraft));
        when(timesheetRepository.findByOrganizationIdAndReportingManagerUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of(directReportDraft, directReportSubmitted));
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        List<TimesheetResponse> visible = service.listForUser(actor.userId());

        assertThat(visible).extracting(TimesheetResponse::id)
                .containsExactlyInAnyOrder("own-draft", "emp-submitted")
                .doesNotContain("emp-draft");
    }

    @Test
    void companyAdminDoesNotSeeDraftTimesheets() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("admin-1", UserRole.COMPANY_ADMIN);
        Timesheet empDraft = timesheet("emp-draft", actor.organizationId(), "employee-1", TimesheetStatus.DRAFT);
        Timesheet empSubmitted = timesheet("emp-submitted", actor.organizationId(), "employee-1", TimesheetStatus.SUBMITTED);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findByOrganizationIdOrderByPeriodStartDateDesc(actor.organizationId()))
                .thenReturn(List.of(empDraft, empSubmitted));
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        List<TimesheetResponse> visible = service.listForUser(actor.userId());

        assertThat(visible).extracting(TimesheetResponse::id)
                .containsExactly("emp-submitted")
                .doesNotContain("emp-draft");
    }

    @Test
    void cannotAccessAnotherEmployeesDraft() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("hr-1", UserRole.HR);
        Timesheet empDraft = timesheet("emp-draft", actor.organizationId(), "employee-1", TimesheetStatus.DRAFT);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findById("emp-draft")).thenReturn(Optional.of(empDraft));

        assertThatThrownBy(() -> service.getForUser(actor.userId(), "emp-draft"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void submittedTimesheetCannotBeEditedWithoutConfirmationAndReason() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        Timesheet submitted = timesheet("submitted", actor.organizationId(), actor.userId(), TimesheetStatus.SUBMITTED);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(assignment(actor.userId(), "hr-1")));
        when(timesheetRepository.findById(submitted.getId())).thenReturn(Optional.of(submitted));

        assertThatThrownBy(() -> service.update(actor.userId(), submitted.getId(), request(
                TimesheetStatus.DRAFT, new BigDecimal("7"))))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Confirm re-editing");

        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void submittedTimesheetCanBeReeditedWhenConfirmedWithChangeReason() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        ReportingAssignment assignment = assignment(actor.userId(), "hr-1");
        Timesheet submitted = timesheet("submitted-1", actor.organizationId(), actor.userId(), TimesheetStatus.SUBMITTED);
        submitted.setEntries(List.of(com.waypoint.entity.TimesheetEntry.builder()
                .workDate(LocalDate.of(2026, 8, 31))
                .dayOfWeek("MONDAY")
                .hoursWorked(new BigDecimal("8.00"))
                .build()));
        submitted.setTotalHours(new BigDecimal("8.00"));

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment));
        when(timesheetRepository.findById("submitted-1")).thenReturn(Optional.of(submitted));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeProfileRepository.findByUserId(actor.userId())).thenReturn(Optional.empty());
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(user(actor.userId(), UserRole.EMPLOYEE)));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(user("hr-1", UserRole.HR)));

        TimesheetUpsertRequest reeditRequest = new TimesheetUpsertRequest(
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 6),
                List.of(new TimesheetEntryRequest(
                        LocalDate.of(2026, 8, 31), new BigDecimal("7.50"), "Corrected hours")),
                null,
                TimesheetStatus.DRAFT,
                "Manager approved correction of 30 min",
                true);

        TimesheetResponse response = service.update(actor.userId(), "submitted-1", reeditRequest);

        assertThat(response.status()).isEqualTo(TimesheetStatus.DRAFT);
        assertThat(response.totalHours()).isEqualByComparingTo("7.50");
        assertThat(response.changeReason()).isEqualTo("Manager approved correction of 30 min");
        assertThat(response.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.hoursWorked()).isEqualByComparingTo("7.50");
        });
        verify(timesheetRepository).save(submitted);
    }

    @Test
    void companyAdminCanReviewOnlySubmittedTimesheetsInTheirOrganization() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("admin-1", UserRole.COMPANY_ADMIN);
        Timesheet submitted = timesheet("submitted", actor.organizationId(), "employee-1", TimesheetStatus.SUBMITTED);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findByOrganizationIdOrderByPeriodStartDateDesc(actor.organizationId()))
                .thenReturn(List.of(submitted));
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenReturn(List.of());
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        assertThat(service.listForUser(actor.userId())).extracting(TimesheetResponse::id)
                .containsExactly("submitted");

        when(timesheetRepository.findById("submitted")).thenReturn(Optional.of(submitted));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TimesheetResponse response = service.review(actor.userId(), "submitted",
                new TimesheetReviewRequest(TimesheetStatus.APPROVED, "Hours verified"));

        assertThat(response.status()).isEqualTo(TimesheetStatus.APPROVED);
        assertThat(response.reviewedByUserId()).isEqualTo(actor.userId());
        assertThat(response.reviewComment()).isEqualTo("Hours verified");
        verify(timesheetRepository).save(submitted);
        verify(inAppNotificationService).createNotification(
                eq(actor.organizationId()),
                eq("employee-1"),
                eq(com.waypoint.enums.NotificationType.TIMESHEET_APPROVED),
                eq("Timesheet approved"),
                anyString(),
                eq("submitted"),
                contains("/myhr/employee/timesheets"));
    }

    @Test
    void companyAdminCanRejectTimesheetAndNotifyEmployee() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("admin-1", UserRole.COMPANY_ADMIN);
        Timesheet submitted = timesheet("submitted", actor.organizationId(), "employee-1", TimesheetStatus.SUBMITTED);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findById("submitted")).thenReturn(Optional.of(submitted));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> invocation.getArgument(0));

        TimesheetResponse response = service.review(actor.userId(), "submitted",
                new TimesheetReviewRequest(TimesheetStatus.REJECTED, "Invalid hours on Sep 1"));

        assertThat(response.status()).isEqualTo(TimesheetStatus.REJECTED);
        verify(inAppNotificationService).createNotification(
                eq(actor.organizationId()),
                eq("employee-1"),
                eq(com.waypoint.enums.NotificationType.TIMESHEET_REJECTED),
                eq("Timesheet rejected"),
                eq("Your timesheet for 2026-08-31 to 2026-09-06 was rejected by ADMIN-1. Reason: Invalid hours on Sep 1. Please revise and resubmit."),
                eq("submitted"),
                contains("/myhr/employee/timesheets"));
    }

    @Test
    void companyAdminCannotReviewDraftTimesheet() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("admin-1", UserRole.COMPANY_ADMIN);
        Timesheet draft = timesheet("draft", actor.organizationId(), "employee-1", TimesheetStatus.DRAFT);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findById("draft")).thenReturn(Optional.of(draft));

        assertThatThrownBy(() -> service.review(actor.userId(), "draft",
                new TimesheetReviewRequest(TimesheetStatus.APPROVED, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only submitted");

        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void updatingDraftReplacesEntriesAndRecalculatesTotalHoursRatherThanSumming() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        ReportingAssignment assignment = assignment(actor.userId(), "hr-1");
        Timesheet existingDraft = timesheet("draft-1", actor.organizationId(), actor.userId(), TimesheetStatus.DRAFT);
        existingDraft.setEntries(List.of(com.waypoint.entity.TimesheetEntry.builder()
                .workDate(LocalDate.of(2026, 8, 31))
                .dayOfWeek("MONDAY")
                .hoursWorked(new BigDecimal("8.00"))
                .build()));
        existingDraft.setTotalHours(new BigDecimal("8.00"));

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment));
        when(timesheetRepository.findById("draft-1")).thenReturn(Optional.of(existingDraft));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeProfileRepository.findByUserId(actor.userId())).thenReturn(Optional.empty());
        when(userRepository.findById(actor.userId())).thenReturn(Optional.of(user(actor.userId(), UserRole.EMPLOYEE)));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(user("hr-1", UserRole.HR)));

        // Update with 6.00 hours instead of 8.00
        TimesheetResponse response = service.update(actor.userId(), "draft-1",
                request(TimesheetStatus.DRAFT, new BigDecimal("6")));

        // Verify totalHours is 6.00, NOT 14.00 (8 + 6)
        assertThat(response.totalHours()).isEqualByComparingTo("6.00");
        assertThat(response.entries()).singleElement().satisfies(entry -> {
            assertThat(entry.hoursWorked()).isEqualByComparingTo("6.00");
        });
    }

    @Test
    void newSubmissionNeverOverwritesAnExistingFiledTimesheet() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        ReportingAssignment assignment = assignment(actor.userId(), "hr-1");
        Timesheet submittedWeekly = timesheet("submitted-week", actor.organizationId(), actor.userId(), TimesheetStatus.SUBMITTED);
        submittedWeekly.setPeriodStartDate(LocalDate.of(2026, 9, 7));
        submittedWeekly.setPeriodEndDate(LocalDate.of(2026, 9, 13));
        submittedWeekly.setEntries(List.of(TimesheetEntry.builder()
                .workDate(LocalDate.of(2026, 9, 7)).hoursWorked(new BigDecimal("8.00")).build()));

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment));
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of(submittedWeekly));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> {
            Timesheet saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId("timesheet-new");
            return saved;
        });
        when(employeeProfileRepository.findByUserId(anyString())).thenReturn(Optional.empty());
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        TimesheetUpsertRequest newSubmission = new TimesheetUpsertRequest(
                LocalDate.of(2026, 9, 7),
                LocalDate.of(2026, 9, 10),
                List.of(new TimesheetEntryRequest(
                        LocalDate.of(2026, 9, 7), new BigDecimal("8.00"), "Dev")),
                null,
                TimesheetStatus.SUBMITTED,
                null,
                null);

        TimesheetResponse response = service.create(actor.userId(), newSubmission);

        // A fresh submission is its own record; the already-filed week is left untouched.
        assertThat(response.id()).isEqualTo("timesheet-new");
        assertThat(response.status()).isEqualTo(TimesheetStatus.SUBMITTED);
    }

    @Test
    void employeeSubmitCreatesAuditLog() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        ReportingAssignment assignment = assignment(actor.userId(), "hr-1");
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment));
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of());
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> {
            Timesheet ts = invocation.getArgument(0);
            ts.setId("ts-100");
            return ts;
        });

        TimesheetUpsertRequest req = request(TimesheetStatus.SUBMITTED, new BigDecimal("7.50"));
        TimesheetResponse res = service.create(actor.userId(), req);

        assertThat(res).isNotNull();
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    void consolidatedPeriodDeduplicatesAndOverwritesByWorkDate() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);

        // Older timesheet covering Sep 1 - Sep 30 with Sep 1 = 8h, Sep 2 = 8h
        Timesheet older = Timesheet.builder()
                .id("ts-1")
                .organizationId(actor.organizationId())
                .userId(actor.userId())
                .periodStartDate(LocalDate.of(2026, 9, 1))
                .periodEndDate(LocalDate.of(2026, 9, 30))
                .status(TimesheetStatus.SUBMITTED)
                .submittedAt(LocalDateTime.of(2026, 9, 1, 10, 0))
                .entries(List.of(
                        TimesheetEntry.builder().workDate(LocalDate.of(2026, 9, 1)).hoursWorked(new BigDecimal("8.00")).description("Old").build(),
                        TimesheetEntry.builder().workDate(LocalDate.of(2026, 9, 2)).hoursWorked(new BigDecimal("8.00")).description("Old").build()
                ))
                .build();

        // Newer re-edited timesheet covering Sep 1 - Sep 7 with Sep 1 = 6h, Sep 3 = 4h
        Timesheet newer = Timesheet.builder()
                .id("ts-2")
                .organizationId(actor.organizationId())
                .userId(actor.userId())
                .periodStartDate(LocalDate.of(2026, 9, 1))
                .periodEndDate(LocalDate.of(2026, 9, 7))
                .status(TimesheetStatus.SUBMITTED)
                .submittedAt(LocalDateTime.of(2026, 9, 5, 12, 0))
                .updatedAt(LocalDateTime.of(2026, 9, 5, 12, 0))
                .entries(List.of(
                        TimesheetEntry.builder().workDate(LocalDate.of(2026, 9, 1)).hoursWorked(new BigDecimal("6.00")).description("Updated").build(),
                        TimesheetEntry.builder().workDate(LocalDate.of(2026, 9, 3)).hoursWorked(new BigDecimal("4.00")).description("New").build()
                ))
                .build();

        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(actor.organizationId(), actor.userId()))
                .thenReturn(List.of(newer, older));

        TimesheetResponse response = service.getConsolidatedPeriodForUser(
                actor.userId(),
                LocalDate.of(2026, 9, 1),
                LocalDate.of(2026, 9, 30)
        );

        assertThat(response).isNotNull();
        // Sep 1 was overwritten to 6.00, Sep 2 is 8.00, Sep 3 is 4.00 -> Total = 18.00
        assertThat(response.totalHours()).isEqualByComparingTo("18.00");
        assertThat(response.entries()).hasSize(3);
        assertThat(response.entries().get(0).hoursWorked()).isEqualByComparingTo("6.00");
        assertThat(response.entries().get(0).description()).isEqualTo("Updated");
        assertThat(response.entries().get(1).hoursWorked()).isEqualByComparingTo("8.00");
        assertThat(response.entries().get(2).hoursWorked()).isEqualByComparingTo("4.00");
    }

    @Test
    void getPayrollPeriodReturnsConfiguredPeriodFromOrganizationSettings() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(organizationSettingsRepository.findByOrganizationId(actor.organizationId()))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .organizationId(actor.organizationId())
                        .payrollPeriod(PayrollPeriod.WEEKLY)
                        .build()));

        PayrollPeriod period = service.getPayrollPeriodForOrganization(actor.userId());
        assertThat(period).isEqualTo(PayrollPeriod.WEEKLY);
    }

    @Test
    void getPayrollPeriodDefaultsToMonthlyWhenNoSettingsFound() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(organizationSettingsRepository.findByOrganizationId(actor.organizationId()))
                .thenReturn(Optional.empty());

        PayrollPeriod period = service.getPayrollPeriodForOrganization(actor.userId());
        assertThat(period).isEqualTo(PayrollPeriod.MONTHLY);
    }

    @Test
    void rejectionWithoutReasonThrowsBusinessException() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("hr-1", UserRole.HR);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);

        TimesheetReviewRequest emptyCommentReq = new TimesheetReviewRequest(TimesheetStatus.REJECTED, "   ");

        assertThatThrownBy(() -> service.review(actor.userId(), "submitted-1", emptyCommentReq))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("A reason for rejection is required");
    }

    @Test
    void approvalNotificationForAnHrOwnerLinksToTheHrTimesheetPage() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("admin-1", UserRole.COMPANY_ADMIN);
        Timesheet submitted = timesheet("hr-ts", actor.organizationId(), "hr-owner", TimesheetStatus.SUBMITTED);

        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(timesheetRepository.findById("hr-ts")).thenReturn(Optional.of(submitted));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(userRepository.findById("hr-owner")).thenReturn(Optional.of(user("hr-owner", UserRole.HR)));

        service.review(actor.userId(), "hr-ts", new TimesheetReviewRequest(TimesheetStatus.APPROVED, "ok"));

        verify(inAppNotificationService).createNotification(
                eq(actor.organizationId()),
                eq("hr-owner"),
                eq(NotificationType.TIMESHEET_APPROVED),
                eq("Timesheet approved"),
                anyString(),
                eq("hr-ts"),
                contains("/myhr/hr/timesheets?view=mine&mode=history"));
    }

    @Test
    void rejectionWithReasonSucceedsAndCreatesNotification() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("hr-1", UserRole.HR);
        ReportingAssignment assignment = assignment("employee-1", actor.userId());
        Timesheet submitted = timesheet("submitted-1", actor.organizationId(), "employee-1", TimesheetStatus.SUBMITTED);
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByReportingManagerUserId(actor.userId())).thenReturn(List.of(assignment));
        when(timesheetRepository.findById("submitted-1")).thenReturn(Optional.of(submitted));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(employeeProfileRepository.findByUserId(anyString())).thenReturn(Optional.empty());
        when(userRepository.findById(anyString())).thenReturn(Optional.empty());

        TimesheetReviewRequest rejectReq = new TimesheetReviewRequest(TimesheetStatus.REJECTED, "Hours mismatch on Friday");
        TimesheetResponse response = service.review(actor.userId(), "submitted-1", rejectReq);

        assertThat(response.status()).isEqualTo(TimesheetStatus.REJECTED);
        assertThat(response.reviewComment()).isEqualTo("Hours mismatch on Friday");
        verify(inAppNotificationService).createNotification(
                eq(actor.organizationId()),
                eq("employee-1"),
                eq(NotificationType.TIMESHEET_REJECTED),
                eq("Timesheet rejected"),
                contains("Hours mismatch on Friday"),
                eq("submitted-1"),
                contains("/myhr/employee/timesheets?mode=history&rejected=true&timesheetId=submitted-1"));
    }

    @Test
    void submittingTimesheetNotifiesBothReportingManagerAndCompanyAdmins() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor = actor("employee-1", UserRole.EMPLOYEE);
        ReportingAssignment assignment = assignment(actor.userId(), "hr-1");
        when(authenticatedUserService.require(eq(actor.userId()), anySet(), eq(false))).thenReturn(actor);
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                actor.userId(), RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.of(assignment));
        when(timesheetRepository.findByOrganizationIdAndUserIdOrderBySubmittedAtDesc(
                actor.organizationId(), actor.userId())).thenReturn(List.of());
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(invocation -> {
            Timesheet saved = invocation.getArgument(0);
            saved.setId("ts-new");
            return saved;
        });

        User adminUser = User.builder()
                .id("admin-1")
                .loginId("TDADMIN")
                .role(UserRole.COMPANY_ADMIN)
                .accountStatus(AccountStatus.ACTIVE)
                .build();
        when(userRepository.findByOrganizationIdAndRoleIn(eq(actor.organizationId()), eq(List.of(UserRole.COMPANY_ADMIN))))
                .thenReturn(List.of(adminUser));

        TimesheetUpsertRequest submitRequest = request(TimesheetStatus.SUBMITTED, new BigDecimal("8.00"));
        service.create(actor.userId(), submitRequest);

        // Verify reporting manager notification
        verify(inAppNotificationService).createNotification(
                eq(actor.organizationId()),
                eq("hr-1"),
                eq(NotificationType.TIMESHEET_SUBMITTED),
                anyString(),
                anyString(),
                eq("ts-new"),
                contains("/myhr/hr/timesheets?view=team&timesheetId=ts-new"));

        // Verify company admin notification
        verify(inAppNotificationService).createNotification(
                eq(actor.organizationId()),
                eq("admin-1"),
                eq(NotificationType.TIMESHEET_SUBMITTED),
                anyString(),
                anyString(),
                eq("ts-new"),
                contains("/myhr/admin/timesheets?timesheetId=ts-new"));
    }

    @Test
    void adminReviewingTimesheetNotifiesEmployeeAndReportingManager() {
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser adminActor = actor("admin-1", UserRole.COMPANY_ADMIN);
        Timesheet timesheet = timesheet("ts-sub-1", adminActor.organizationId(), "employee-1", TimesheetStatus.SUBMITTED);
        timesheet.setReportingManagerUserId("hr-1");

        when(authenticatedUserService.require(eq(adminActor.userId()), anySet(), eq(false))).thenReturn(adminActor);
        when(timesheetRepository.findById("ts-sub-1")).thenReturn(Optional.of(timesheet));
        when(timesheetRepository.save(any(Timesheet.class))).thenAnswer(inv -> inv.getArgument(0));

        TimesheetReviewRequest approveReq = new TimesheetReviewRequest(TimesheetStatus.APPROVED, "Looks good");
        service.review(adminActor.userId(), "ts-sub-1", approveReq);

        // Verify employee notification
        verify(inAppNotificationService).createNotification(
                eq(adminActor.organizationId()),
                eq("employee-1"),
                eq(NotificationType.TIMESHEET_APPROVED),
                eq("Timesheet approved"),
                contains("has been approved by ADMIN-1"),
                eq("ts-sub-1"),
                anyString());

        // Verify reporting manager notification
        verify(inAppNotificationService).createNotification(
                eq(adminActor.organizationId()),
                eq("hr-1"),
                eq(NotificationType.TIMESHEET_APPROVED),
                eq("Timesheet approved by ADMIN-1"),
                anyString(),
                eq("ts-sub-1"),
                contains("/myhr/hr/timesheets?view=team&timesheetId=ts-sub-1"));
    }

    private TimesheetUpsertRequest request(TimesheetStatus status, BigDecimal hours) {
        return new TimesheetUpsertRequest(
                LocalDate.of(2026, 8, 31),
                LocalDate.of(2026, 9, 6),
                List.of(new TimesheetEntryRequest(
                        LocalDate.of(2026, 8, 31), hours, "Foundation work")),
                null,
                status,
                null,
                null);
    }

    private Timesheet timesheet(String id, String organizationId, String userId, TimesheetStatus status) {
        return Timesheet.builder()
                .id(id)
                .organizationId(organizationId)
                .userId(userId)
                .periodStartDate(LocalDate.of(2026, 8, 31))
                .periodEndDate(LocalDate.of(2026, 9, 6))
                .totalHours(new BigDecimal("8.00"))
                .status(status)
                .submittedAt(status == TimesheetStatus.SUBMITTED ? LocalDateTime.now() : null)
                .entries(List.of())
                .build();
    }

    private ReportingAssignment assignment(String employeeUserId, String managerUserId) {
        return ReportingAssignment.builder()
                .organizationId("org-1")
                .employeeUserId(employeeUserId)
                .reportingManagerUserId(managerUserId)
                .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                .isActive(true)
                .build();
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor(String id, UserRole role) {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                id, id.toUpperCase(), id + "@example.com", role, "org-1", AccountStatus.ACTIVE, false);
    }

    private User user(String id, UserRole role) {
        return User.builder()
                .id(id)
                .loginId(id.toUpperCase())
                .role(role)
                .organizationId("org-1")
                .accountStatus(AccountStatus.ACTIVE)
                .build();
    }
}
