package com.waypoint.service;

import com.waypoint.config.PayrollReadinessProperties;
import com.waypoint.dto.myhr.PayrollReadinessBulkReminderRequest;
import com.waypoint.dto.myhr.PayrollReadinessResponse;
import com.waypoint.dto.myhr.PayrollReadinessRowResponse;
import com.waypoint.dto.myhr.PayrollReadinessReminderResult;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Notification;
import com.waypoint.entity.PayrollReadinessReminder;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.Timesheet;
import com.waypoint.entity.TimesheetEntry;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.PayrollReadinessReminderType;
import com.waypoint.enums.PayrollReadinessResponsibility;
import com.waypoint.enums.PayrollReadinessState;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.TimesheetEntryType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.PayrollReadinessReminderRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.TimesheetRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PayrollReadinessServiceTest {

    private static final String ORG_ID = "org-1";
    private static final String ADMIN_ID = "admin-1";

    @Mock
    private MyHrAuthenticatedUserService authenticatedUserService;
    @Mock
    private EmployeeProfileRepository employeeProfileRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ReportingAssignmentRepository reportingAssignmentRepository;
    @Mock
    private TimesheetRepository timesheetRepository;
    @Mock
    private PayrollReadinessReminderRepository reminderRepository;
    @Mock
    private InAppNotificationService notificationService;
    @Mock
    private AuditService auditService;

    private PayrollReadinessProperties payrollReadinessProperties;
    private PayrollReadinessPeriodResolver periodResolver;
    private PayrollReadinessService service;

    @BeforeEach
    void setUp() {
        payrollReadinessProperties = new PayrollReadinessProperties();
        periodResolver = new PayrollReadinessPeriodResolver(payrollReadinessProperties);
        service = new PayrollReadinessService(
                authenticatedUserService,
                employeeProfileRepository,
                userRepository,
                reportingAssignmentRepository,
                timesheetRepository,
                reminderRepository,
                notificationService,
                auditService,
                periodResolver,
                payrollReadinessProperties);
    }

    @Test
    void derivesRequiredTimesheetMonthFromPayrollMonth() {
        PayrollReadinessService.Period july = service.resolvePeriod(LocalDate.of(2026, 7, 1));
        assertThat(july.requiredStart()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(july.requiredEnd()).isEqualTo(LocalDate.of(2026, 6, 30));
        assertThat(july.cutoffDate()).isEqualTo(LocalDate.of(2026, 7, 10));

        PayrollReadinessService.Period january = service.resolvePeriod(LocalDate.of(2027, 1, 1));
        assertThat(january.requiredStart()).isEqualTo(LocalDate.of(2026, 12, 1));
        assertThat(january.requiredEnd()).isEqualTo(LocalDate.of(2026, 12, 31));

        PayrollReadinessService.Period marchLeapYear = service.resolvePeriod(LocalDate.of(2024, 3, 1));
        assertThat(marchLeapYear.requiredStart()).isEqualTo(LocalDate.of(2024, 2, 1));
        assertThat(marchLeapYear.requiredEnd()).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void mapsExistingWorkflowStatusesToCalculatedReadinessWithoutChangingTimesheetStatus() {
        EmployeeProfile ready = profile("ready", "TD001", "Ready Employee");
        EmployeeProfile missing = profile("missing", "TD002", "Missing Employee");
        EmployeeProfile draft = profile("draft", "TD003", "Draft Employee");
        EmployeeProfile submitted = profile("submitted", "TD004", "Submitted Employee");
        EmployeeProfile rejected = profile("rejected", "TD005", "Rejected Employee");
        seedAdminScope(List.of(ready, missing, draft, submitted, rejected));

        Timesheet approvedJuneOne = timesheet("ready", TimesheetStatus.APPROVED, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 15));
        Timesheet approvedJuneTwo = timesheet("ready", TimesheetStatus.APPROVED, LocalDate.of(2026, 6, 16), LocalDate.of(2026, 6, 30));
        Timesheet draftSheet = timesheet("draft", TimesheetStatus.DRAFT, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));
        Timesheet submittedSheet = timesheet("submitted", TimesheetStatus.SUBMITTED, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));
        Timesheet rejectedSheet = timesheet("rejected", TimesheetStatus.REJECTED, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 6, 30)), eq(LocalDate.of(2026, 6, 1))))
                .thenReturn(List.of(approvedJuneOne, approvedJuneTwo, draftSheet, submittedSheet, rejectedSheet));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 7, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessResponse response = service.getReadiness(ADMIN_ID, LocalDate.of(2026, 7, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25);

        assertThat(row(response, "ready").readinessState()).isEqualTo(PayrollReadinessState.READY);
        assertThat(row(response, "missing").readinessState()).isEqualTo(PayrollReadinessState.MISSING_TIMESHEET);
        assertThat(row(response, "draft").readinessState()).isEqualTo(PayrollReadinessState.INCOMPLETE_TIMESHEET);
        assertThat(row(response, "submitted").readinessState()).isEqualTo(PayrollReadinessState.AWAITING_APPROVAL);
        assertThat(row(response, "submitted").responsibility()).isEqualTo(PayrollReadinessResponsibility.HR_ADMIN_FOLLOW_UP);
        assertThat(row(response, "rejected").readinessState()).isEqualTo(PayrollReadinessState.CORRECTION_REQUIRED);
        assertThat(row(response, "ready").coveragePercentage()).isEqualTo(100.0);
        assertThat(row(response, "ready").approvedHours()).isEqualByComparingTo("240.00");
        assertThat(response.summary().readyForPayroll()).isEqualTo(1);
        assertThat(response.summary().actionRequired()).isEqualTo(3);
        assertThat(approvedJuneOne.getStatus()).isEqualTo(TimesheetStatus.APPROVED);
        assertThat(submittedSheet.getStatus()).isEqualTo(TimesheetStatus.SUBMITTED);
        verify(timesheetRepository, never()).save(any());
    }


    @Test
    void returnsPartiallyApprovedWhenOnlyPartOfRequiredMonthHasApprovedEntries() {
        EmployeeProfile employee = profile("partial", "TD020", "Partial Employee");
        seedAdminScope(List.of(employee));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 8, 31)), eq(LocalDate.of(2026, 8, 1))))
                .thenReturn(List.of(timesheet("partial", TimesheetStatus.APPROVED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 28))));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "partial");

        assertThat(row.readinessState()).isEqualTo(PayrollReadinessState.PARTIALLY_APPROVED);
        // August 2026 has 21 required (Mon-Fri) dates; entries cover Aug 1-28,
        // which includes 20 of them - only Aug 31 (a Monday) is missing.
        assertThat(row.requiredDays()).isEqualTo(21);
        assertThat(row.coveredDays()).isEqualTo(20);
        assertThat(row.missingDays()).isEqualTo(1);
        assertThat(row.approvedCoveredDays()).isEqualTo(20);
        assertThat(row.responsibility()).isEqualTo(PayrollReadinessResponsibility.EMPLOYEE_ACTION_REQUIRED);
    }

    @Test
    void clipsCrossMonthTimesheetEntriesToRequiredPayrollMonth() {
        EmployeeProfile employee = profile("cross", "TD021", "Cross Month Employee");
        seedAdminScope(List.of(employee));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 8, 31)), eq(LocalDate.of(2026, 8, 1))))
                .thenReturn(List.of(timesheet("cross", TimesheetStatus.APPROVED, LocalDate.of(2026, 8, 29), LocalDate.of(2026, 9, 4))));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "cross");

        assertThat(row.readinessState()).isEqualTo(PayrollReadinessState.PARTIALLY_APPROVED);
        // Clipped to Aug 29-31: only Aug 31 (Monday) is a required date, and it's
        // covered - but 20 of August's 21 required dates still have no entry.
        assertThat(row.coveredDays()).isEqualTo(1);
        assertThat(row.missingDays()).isEqualTo(20);
        // Hours totals are not filtered by required-date status: all 3 clipped
        // days (including the weekend ones) still count toward approved hours.
        assertThat(row.approvedHours()).isEqualByComparingTo("24.00");
    }

    @Test
    void zeroHourEntriesCountAsAccountedDatesAndAreNotBlindlyMissing() {
        EmployeeProfile employee = profile("zero", "TD022", "Zero Hour Employee");
        seedAdminScope(List.of(employee));
        Timesheet sheet = timesheet("zero", TimesheetStatus.APPROVED, LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 29), BigDecimal.ZERO);
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2024, 2, 29)), eq(LocalDate.of(2024, 2, 1))))
                .thenReturn(List.of(sheet));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2024, 3, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2024, 3, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "zero");

        assertThat(row.readinessState()).isEqualTo(PayrollReadinessState.READY);
        // February 2024 has 21 required (Mon-Fri) dates; entries cover every
        // calendar day of the month, including weekends, so all 21 are covered.
        assertThat(row.coveredDays()).isEqualTo(21);
        assertThat(row.missingDays()).isZero();
        assertThat(row.approvedHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void fallsBackToManagerLoginIdWhenManagerHasNoEmployeeProfile() {
        // A manager who is e.g. a Company Admin may have a User account but no
        // EmployeeProfile document. Regression coverage: this must resolve to
        // their login ID, not leak the raw Mongo userId into the UI.
        EmployeeProfile employee = profile("with-admin-manager", "TD030", "Employee With Admin Manager");
        seedAdminScope(List.of(employee));

        String managerId = "6a8df03a155fb754b49c1b93";
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(assignment("with-admin-manager", managerId)));
        User managerUser = User.builder()
                .id(managerId)
                .organizationId(ORG_ID)
                .role(UserRole.COMPANY_ADMIN)
                .accountStatus(AccountStatus.ACTIVE)
                .loginId("TDADMIN")
                .email("admin@example.test")
                .build();
        when(userRepository.findByIdIn(argThat(ids -> ids.contains(managerId))))
                .thenReturn(List.of(managerUser));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 8, 31)), eq(LocalDate.of(2026, 8, 1))))
                .thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "with-admin-manager");

        assertThat(row.reportingManagerName()).isEqualTo("TDADMIN");
    }

    @Test
    void approvedZeroHourDayWithANoteCountsAsCoveredNotMissing() {
        // Regression coverage for the "sick leave shows as Partially Approved" bug.
        // There is no structured leave/PTO/holiday model in this codebase - a
        // zero-hour day is only ever represented by an ordinary, approved
        // TimesheetEntry whose hoursWorked happens to be 0 (with a free-text
        // note). This proves the backend coverage/readiness calculation already
        // treats such a day as covered once the entry actually exists - the real
        // bug was the frontend silently never submitting a 0-hour entry at all
        // (fixed separately in TimesheetsPage.jsx's reviewEntry()).
        EmployeeProfile employee = profile("leave-day", "TD040", "Employee With Leave Day");
        seedAdminScope(List.of(employee));

        LocalDate requiredStart = LocalDate.of(2026, 8, 1);
        LocalDate requiredEnd = LocalDate.of(2026, 8, 31);
        LocalDate leaveDate = LocalDate.of(2026, 8, 20);
        List<TimesheetEntry> entries = new ArrayList<>();
        for (LocalDate date = requiredStart; !date.isAfter(requiredEnd); date = date.plusDays(1)) {
            boolean isLeaveDay = date.equals(leaveDate);
            entries.add(TimesheetEntry.builder()
                    .workDate(date)
                    .dayOfWeek(date.getDayOfWeek().name())
                    .hoursWorked(isLeaveDay ? BigDecimal.ZERO : new BigDecimal("8.00"))
                    .description(isLeaveDay ? "Sick leave" : null)
                    .build());
        }
        Timesheet monthTimesheet = Timesheet.builder()
                .id("leave-day-timesheet")
                .organizationId(ORG_ID)
                .userId("leave-day")
                .periodStartDate(requiredStart)
                .periodEndDate(requiredEnd)
                .entries(entries)
                .status(TimesheetStatus.APPROVED)
                .build();

        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(requiredEnd), eq(requiredStart)))
                .thenReturn(List.of(monthTimesheet));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "leave-day");

        assertThat(row.readinessState()).isEqualTo(PayrollReadinessState.READY);
        assertThat(row.missingDays()).isZero();
        // August 2026 has 21 required (Mon-Fri) dates; every one is covered, since
        // entries exist for every calendar day of the month (weekends included).
        assertThat(row.requiredDays()).isEqualTo(21);
        assertThat(row.coveredDays()).isEqualTo(21);
        assertThat(row.approvedCoveredDays()).isEqualTo(21);
        // Reminder eligibility is derived from readiness state, so it falls out
        // correctly too, with no separate change needed.
        assertThat(row.reminderEligible()).isFalse();
        // The single described zero-hour day is valued as one standard (8h)
        // workday of leave; every other day is exactly 8h, so no overtime.
        assertThat(row.leaveHours()).isEqualByComparingTo("8.00");
        assertThat(row.overtimeHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void structuredLeaveHolidayAndCompOffEntriesCoverRequiredDatesWithoutInflatingWorkedHours() {
        EmployeeProfile employee = profile("typed-leave", "TD043", "Employee With Typed Leave");
        seedAdminScope(List.of(employee));

        LocalDate requiredStart = LocalDate.of(2026, 8, 1);
        LocalDate requiredEnd = LocalDate.of(2026, 8, 31);
        List<TimesheetEntry> entries = new ArrayList<>();
        for (LocalDate date = requiredStart; !date.isAfter(requiredEnd); date = date.plusDays(1)) {
            TimesheetEntryType type = TimesheetEntryType.WORK;
            BigDecimal hours = new BigDecimal("8.00");
            if (date.equals(LocalDate.of(2026, 8, 12))) {
                type = TimesheetEntryType.SICK_LEAVE;
                hours = BigDecimal.ZERO;
            } else if (date.equals(LocalDate.of(2026, 8, 19))) {
                type = TimesheetEntryType.COMP_OFF;
                hours = BigDecimal.ZERO;
            } else if (date.equals(LocalDate.of(2026, 8, 26))) {
                type = TimesheetEntryType.HOLIDAY;
                hours = BigDecimal.ZERO;
            }
            entries.add(TimesheetEntry.builder()
                    .workDate(date)
                    .dayOfWeek(date.getDayOfWeek().name())
                    .hoursWorked(hours)
                    .entryType(type)
                    .build());
        }
        Timesheet monthTimesheet = Timesheet.builder()
                .id("typed-leave-timesheet")
                .organizationId(ORG_ID)
                .userId("typed-leave")
                .periodStartDate(requiredStart)
                .periodEndDate(requiredEnd)
                .entries(entries)
                .status(TimesheetStatus.APPROVED)
                .build();

        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(requiredEnd), eq(requiredStart)))
                .thenReturn(List.of(monthTimesheet));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "typed-leave");

        assertThat(row.readinessState()).isEqualTo(PayrollReadinessState.READY);
        assertThat(row.coveredDays()).isEqualTo(21);
        assertThat(row.missingDays()).isZero();
        assertThat(row.leaveHours()).isEqualByComparingTo("16.00");
        assertThat(row.workedHours()).isEqualByComparingTo("224.00");
        assertThat(row.approvedHours()).isEqualByComparingTo("224.00");
        assertThat(row.overtimeHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void approvedHoursBeyondTheStandardWorkdayAreReportedAsOvertime() {
        EmployeeProfile employee = profile("ot-day", "TD041", "Employee With Overtime");
        seedAdminScope(List.of(employee));

        LocalDate requiredStart = LocalDate.of(2026, 8, 1);
        LocalDate requiredEnd = LocalDate.of(2026, 8, 31);
        LocalDate overtimeDate = LocalDate.of(2026, 8, 10);
        List<TimesheetEntry> entries = new ArrayList<>();
        for (LocalDate date = requiredStart; !date.isAfter(requiredEnd); date = date.plusDays(1)) {
            boolean isOvertimeDay = date.equals(overtimeDate);
            entries.add(TimesheetEntry.builder()
                    .workDate(date)
                    .dayOfWeek(date.getDayOfWeek().name())
                    .hoursWorked(isOvertimeDay ? new BigDecimal("10.50") : new BigDecimal("8.00"))
                    .build());
        }
        Timesheet monthTimesheet = Timesheet.builder()
                .id("ot-day-timesheet")
                .organizationId(ORG_ID)
                .userId("ot-day")
                .periodStartDate(requiredStart)
                .periodEndDate(requiredEnd)
                .entries(entries)
                .status(TimesheetStatus.APPROVED)
                .build();

        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(requiredEnd), eq(requiredStart)))
                .thenReturn(List.of(monthTimesheet));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "ot-day");

        assertThat(row.readinessState()).isEqualTo(PayrollReadinessState.READY);
        // 30 standard days (240h) + one 10.5h day = 250.50h total approved; 2.5h of
        // that one day is overtime beyond the standard 8h workday.
        assertThat(row.approvedHours()).isEqualByComparingTo("250.50");
        assertThat(row.overtimeHours()).isEqualByComparingTo("2.50");
        assertThat(row.leaveHours()).isEqualByComparingTo("0.00");
    }

    @Test
    void weekendDatesWithoutEntriesAreNotCountedAsMissingByDefault() {
        // Regression coverage: an employee who has an approved entry for every
        // weekday of the month, but none on weekends, must be fully READY - there
        // is no org calendar model, so Mon-Fri is the default required work week
        // and ordinary Saturdays/Sundays are never "missing" coverage.
        EmployeeProfile employee = profile("weekday-only", "TD042", "Weekday Only Employee");
        seedAdminScope(List.of(employee));

        LocalDate requiredStart = LocalDate.of(2026, 7, 1);
        LocalDate requiredEnd = LocalDate.of(2026, 7, 31);
        List<TimesheetEntry> entries = new ArrayList<>();
        for (LocalDate date = requiredStart; !date.isAfter(requiredEnd); date = date.plusDays(1)) {
            if (date.getDayOfWeek() == DayOfWeek.SATURDAY || date.getDayOfWeek() == DayOfWeek.SUNDAY) continue;
            entries.add(TimesheetEntry.builder()
                    .workDate(date)
                    .dayOfWeek(date.getDayOfWeek().name())
                    .hoursWorked(new BigDecimal("8.00"))
                    .build());
        }
        Timesheet monthTimesheet = Timesheet.builder()
                .id("weekday-only-timesheet")
                .organizationId(ORG_ID)
                .userId("weekday-only")
                .periodStartDate(requiredStart)
                .periodEndDate(requiredEnd)
                .entries(entries)
                .status(TimesheetStatus.APPROVED)
                .build();

        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(requiredEnd), eq(requiredStart)))
                .thenReturn(List.of(monthTimesheet));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 8, 1)), anyCollection()))
                .thenReturn(List.of());

        // Payroll run in August validates the required (previous) month of July.
        PayrollReadinessRowResponse row = row(service.getReadiness(ADMIN_ID, LocalDate.of(2026, 8, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25), "weekday-only");

        assertThat(row.readinessState()).isEqualTo(PayrollReadinessState.READY);
        assertThat(row.requiredDays()).isEqualTo(23);
        assertThat(row.coveredDays()).isEqualTo(23);
        assertThat(row.missingDays()).isZero();
    }

    @Test
    void scopesHrReadinessToSelfAndDirectReports() {
        var hr = actor("hr-1", UserRole.HR);
        when(authenticatedUserService.require(eq("hr-1"), anySet(), eq(false))).thenReturn(hr);
        EmployeeProfile hrProfile = profile("hr-1", "TD010", "HR User");
        EmployeeProfile directReport = profile("employee-1", "TD011", "Direct Report");
        EmployeeProfile unauthorized = profile("employee-2", "TD012", "Other Employee");
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(hrProfile));
        when(reportingAssignmentRepository.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(assignment("employee-1", "hr-1")));
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<String> ids = invocation.getArgument(0);
            return List.of(hrProfile, directReport, unauthorized).stream()
                    .filter(profile -> ids.contains(profile.getUserId()))
                    .toList();
        });
        when(userRepository.findByIdIn(anyCollection())).thenReturn(List.of(
                activeUser("hr-1", UserRole.HR), activeUser("employee-1", UserRole.EMPLOYEE), activeUser("employee-2", UserRole.EMPLOYEE)));
        when(employeeProfileRepository.findByOrganizationId(ORG_ID)).thenReturn(List.of(hrProfile, directReport, unauthorized));
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of(assignment("employee-1", "hr-1")));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), any(), any())).thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), any(), any())).thenReturn(List.of());

        PayrollReadinessResponse response = service.getReadiness("hr-1", LocalDate.of(2026, 7, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25);

        assertThat(response.content()).extracting(PayrollReadinessRowResponse::employeeUserId)
                .containsExactly("employee-1", "hr-1");
    }


    @Test
    void supportsPaginationSearchReadinessAndManagerFiltersForAdminScope() {
        EmployeeProfile alpha = profile("alpha", "TD100", "Alpha Employee");
        EmployeeProfile beta = profile("beta", "TD101", "Beta Employee");
        EmployeeProfile gamma = profile("gamma", "TD102", "Gamma Employee");
        seedAdminScope(List.of(alpha, beta, gamma));
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of(
                assignment("alpha", "manager-1"),
                assignment("beta", "manager-1"),
                assignment("gamma", "manager-2")));
        when(userRepository.findByIdIn(anyCollection())).thenReturn(List.of(
                activeUser("alpha", UserRole.EMPLOYEE), activeUser("beta", UserRole.EMPLOYEE),
                activeUser("gamma", UserRole.EMPLOYEE), activeUser("manager-1", UserRole.HR), activeUser("manager-2", UserRole.HR)));
        when(employeeProfileRepository.findByOrganizationId(ORG_ID)).thenReturn(List.of(
                alpha, beta, gamma, profile("manager-1", "M001", "Manager One"), profile("manager-2", "M002", "Manager Two")));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 8, 31)), eq(LocalDate.of(2026, 8, 1))))
                .thenReturn(List.of(
                        timesheet("alpha", TimesheetStatus.APPROVED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31)),
                        timesheet("beta", TimesheetStatus.APPROVED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 20)),
                        timesheet("gamma", TimesheetStatus.SUBMITTED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31))));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());

        PayrollReadinessResponse page = service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", "manager-1", null, "EMPLOYEE_ASC", 0, 1);
        assertThat(page.period().payrollMonth()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(page.period().requiredPeriodStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        assertThat(page.content()).extracting(PayrollReadinessRowResponse::employeeUserId).containsExactly("alpha");

        PayrollReadinessResponse search = service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), "TD101", "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25);
        assertThat(search.content()).extracting(PayrollReadinessRowResponse::employeeUserId).containsExactly("beta");

        PayrollReadinessResponse filtered = service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "PARTIALLY_APPROVED", "ALL", null, null, "EMPLOYEE_ASC", 0, 25);
        assertThat(filtered.content()).extracting(PayrollReadinessRowResponse::employeeUserId).containsExactly("beta");
        assertThat(filtered.summary().partiallyApproved()).isEqualTo(1);
        assertThat(filtered.summary().totalEmployees()).isEqualTo(5);
        assertThat(filtered.summary().readyForPayroll()).isEqualTo(1);
        assertThat(filtered.summary().awaitingApproval()).isEqualTo(1);
        PayrollReadinessResponse empty = service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "CORRECTION_REQUIRED", "ALL", null, null, "EMPLOYEE_ASC", 0, 25);
        assertThat(empty.content()).isEmpty();
        assertThat(empty.totalElements()).isZero();
        assertThat(empty.summary()).isEqualTo(filtered.summary());
        PayrollReadinessResponse submitted = service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "MONTH_SUBMITTED", "ALL", null, null, "EMPLOYEE_ASC", 0, 25);
        assertThat(submitted.content()).extracting(PayrollReadinessRowResponse::employeeUserId).containsExactly("alpha", "gamma");
        assertThat(submitted.content()).allMatch(PayrollReadinessRowResponse::monthSubmissionComplete);
        assertThat(submitted.totalElements()).isEqualTo(submitted.summary().monthSubmitted());
        assertThat(submitted.summary()).isEqualTo(filtered.summary());
        PayrollReadinessResponse followUp = service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "NEEDS_FOLLOW_UP", "ALL", null, null, "EMPLOYEE_ASC", 0, 25);
        assertThat(followUp.content()).allMatch(row -> row.readinessState() != PayrollReadinessState.READY && row.readinessState() != PayrollReadinessState.AWAITING_APPROVAL);
        assertThat(followUp.totalElements()).isEqualTo(followUp.summary().totalEmployees() - followUp.summary().readyForPayroll() - followUp.summary().awaitingApproval());
        assertThat(page.summary().totalEmployees()).isEqualTo(2);
        assertThat(search.summary().totalEmployees()).isEqualTo(1);
    }

    @Test
    void rejectsUnauthorizedAndInvalidQueryParameters() {
        when(authenticatedUserService.require(eq("employee-1"), anySet(), eq(false)))
                .thenThrow(new ForbiddenException("You do not have access to payroll readiness"));
        assertThatThrownBy(() -> service.getReadiness("employee-1", LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "EMPLOYEE_ASC", 0, 25))
                .isInstanceOf(ForbiddenException.class);

        lenient().when(authenticatedUserService.require(eq(ADMIN_ID), anySet(), eq(false))).thenReturn(actor(ADMIN_ID, UserRole.COMPANY_ADMIN));
        assertThatThrownBy(() -> service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "BOGUS", "ALL", null, null, "EMPLOYEE_ASC", 0, 25))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported payroll readiness status");
        assertThatThrownBy(() -> service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, "client-1", "EMPLOYEE_ASC", 0, 25))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Client filtering is not supported");
        assertThatThrownBy(() -> service.getReadiness(ADMIN_ID, LocalDate.of(2026, 9, 1), null, "ALL", "ALL", null, null, "DROP_TABLE", 0, 25))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unsupported payroll readiness sort");
    }

    @Test
    void sendsReminderOnlyForEmployeeActionRequiredReadinessAndDeduplicates() {
        EmployeeProfile missing = profile("missing", "TD002", "Missing Employee");
        EmployeeProfile submitted = profile("submitted", "TD004", "Submitted Employee");
        seedAdminScope(List.of(missing, submitted));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), any(), any()))
                .thenReturn(List.of(timesheet("submitted", TimesheetStatus.SUBMITTED, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30))));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), any(), any())).thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                eq(ORG_ID), eq("missing"), eq(LocalDate.of(2026, 7, 1)), eq(PayrollReadinessReminderType.EMPLOYEE_TIMESHEET_ACTION)))
                .thenReturn(Optional.empty());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                eq(ORG_ID), eq("submitted"), eq(LocalDate.of(2026, 7, 1)), eq(PayrollReadinessReminderType.EMPLOYEE_TIMESHEET_ACTION)))
                .thenReturn(Optional.empty());
        when(notificationService.createNotification(eq(ORG_ID), eq("missing"), eq(NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED),
                eq("Timesheet Reminder - June 2026"), any(), eq("missing"), eq("/myhr/employee/timesheets?year=2026&month=6")))
                .thenReturn(Notification.builder().id("notification-1").build());
        when(notificationService.createNotification(eq(ORG_ID), eq("submitted"), eq(NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED),
                eq("Timesheet Approval Pending - June 2026"), any(), eq("submitted"), eq("/myhr/employee/timesheets?year=2026&month=6")))
                .thenReturn(Notification.builder().id("notification-2").build());

        PayrollReadinessReminderResult result = service.sendBulkReminders(ADMIN_ID, LocalDate.of(2026, 7, 1),
                new PayrollReadinessBulkReminderRequest(List.of("missing", "submitted", "outside-scope")));

        assertThat(result.requested()).isEqualTo(3);
        assertThat(result.sent()).isEqualTo(2);
        assertThat(result.skipped()).isEqualTo(1);
        verify(notificationService).createNotification(eq(ORG_ID), eq("missing"), eq(NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED),
                eq("Timesheet Reminder - June 2026"), any(), eq("missing"), eq("/myhr/employee/timesheets?year=2026&month=6"));
        verify(notificationService).createNotification(eq(ORG_ID), eq("submitted"), eq(NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED),
                eq("Timesheet Approval Pending - June 2026"), any(), eq("submitted"), eq("/myhr/employee/timesheets?year=2026&month=6"));
        verify(reminderRepository, times(2)).save(any(PayrollReadinessReminder.class));
    }

    @Test
    void skipsDuplicateReminderForSameEmployeePayrollMonthAndType() {
        EmployeeProfile missing = profile("missing", "TD002", "Missing Employee");
        seedAdminScope(List.of(missing));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), any(), any())).thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), any(), any())).thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                eq(ORG_ID), eq("missing"), eq(LocalDate.of(2026, 7, 1)), eq(PayrollReadinessReminderType.EMPLOYEE_TIMESHEET_ACTION)))
                .thenReturn(Optional.of(PayrollReadinessReminder.builder().id("reminder-1").build()));

        PayrollReadinessReminderResult result = service.sendReminder(ADMIN_ID, LocalDate.of(2026, 7, 1), "missing");

        assertThat(result.sent()).isZero();
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.results().get(0).reason()).isEqualTo("RECENT_REMINDER_ALREADY_SENT");
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
    }


    @Test
    void automaticDayFiveReminderSendsToEligibleEmployeesAndSkipsReadyWithoutChangingTimesheets() {
        EmployeeProfile ready = profile("ready", "TD001", "Ready Employee");
        EmployeeProfile missing = profile("missing", "TD002", "Missing Employee");
        EmployeeProfile partial = profile("partial", "TD003", "Partial Employee");
        EmployeeProfile submitted = profile("submitted", "TD004", "Submitted Employee");
        EmployeeProfile rejected = profile("rejected", "TD005", "Rejected Employee");
        seedAdminScope(List.of(ready, missing, partial, submitted, rejected));
        Timesheet readySheet = timesheet("ready", TimesheetStatus.APPROVED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        Timesheet partialSheet = timesheet("partial", TimesheetStatus.APPROVED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 28));
        Timesheet submittedSheet = timesheet("submitted", TimesheetStatus.SUBMITTED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        Timesheet rejectedSheet = timesheet("rejected", TimesheetStatus.REJECTED, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 8, 31)), eq(LocalDate.of(2026, 8, 1))))
                .thenReturn(List.of(readySheet, partialSheet, submittedSheet, rejectedSheet));
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                eq(ORG_ID), anyString(), eq(LocalDate.of(2026, 9, 1)), eq(PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_5)))
                .thenReturn(Optional.empty());
        when(notificationService.createNotification(eq(ORG_ID), anyString(), eq(NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED),
                anyString(), anyString(), anyString(), eq("/myhr/employee/timesheets?year=2026&month=8")))
                .thenReturn(Notification.builder().id("notification-auto").build());

        var result = service.runAutomaticForActor(actor(ADMIN_ID, UserRole.COMPANY_ADMIN), LocalDate.of(2026, 9, 5));

        assertThat(result.evaluated()).isEqualTo(5);
        assertThat(result.eligible()).isEqualTo(4);
        assertThat(result.sent()).isEqualTo(4);
        assertThat(result.skippedReady()).isEqualTo(1);
        verify(reminderRepository, times(4)).save(argThat(reminder ->
                reminder.getSource().name().equals("AUTOMATIC")
                        && reminder.getReminderType() == PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_5
                        && reminder.getPayrollMonth().equals(LocalDate.of(2026, 9, 1))));
        verify(timesheetRepository, never()).save(any());
    }

    @Test
    void previousAutomaticStageDoesNotBlockLaterFollowUpButDuplicateSameStageIsSkipped() {
        EmployeeProfile missing = profile("missing", "TD002", "Missing Employee");
        seedAdminScope(List.of(missing));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 8, 31)), eq(LocalDate.of(2026, 8, 1))))
                .thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), eq(LocalDate.of(2026, 9, 1)), anyCollection()))
                .thenReturn(List.of(PayrollReadinessReminder.builder()
                        .employeeUserId("missing")
                        .payrollMonth(LocalDate.of(2026, 9, 1))
                        .reminderType(PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_5)
                        .sentAt(LocalDate.of(2026, 9, 5).atStartOfDay())
                        .build()));
        when(notificationService.createNotification(eq(ORG_ID), eq("missing"), eq(NotificationType.PAYROLL_TIMESHEET_ACTION_REQUIRED),
                eq("Timesheet Follow-up - August 2026"), anyString(), eq("missing"), eq("/myhr/employee/timesheets?year=2026&month=8")))
                .thenReturn(Notification.builder().id("notification-day-7").build());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                eq(ORG_ID), eq("missing"), eq(LocalDate.of(2026, 9, 1)), eq(PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_7)))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(PayrollReadinessReminder.builder().id("day-7").build()));

        var firstRun = service.runAutomaticForActor(actor(ADMIN_ID, UserRole.COMPANY_ADMIN), LocalDate.of(2026, 9, 7));
        var retryRun = service.runAutomaticForActor(actor(ADMIN_ID, UserRole.COMPANY_ADMIN), LocalDate.of(2026, 9, 7));

        assertThat(firstRun.sent()).isEqualTo(1);
        assertThat(retryRun.sent()).isZero();
        assertThat(retryRun.skippedDuplicate()).isEqualTo(1);
        verify(reminderRepository).save(argThat(reminder -> reminder.getReminderType() == PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_7));
    }

    @Test
    void automaticRunDoesNotSendEmployeeReminderAfterCutoff() {
        EmployeeProfile missing = profile("missing", "TD002", "Missing Employee");
        seedAdminScope(List.of(missing));

        var result = service.runAutomaticForActor(actor(ADMIN_ID, UserRole.COMPANY_ADMIN), LocalDate.of(2026, 9, 11));

        assertThat(result.evaluated()).isZero();
        assertThat(result.sent()).isZero();
        verify(notificationService, never()).createNotification(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void automaticManagementSummaryUsesHrReportingScope() {
        var hr = actor("hr-1", UserRole.HR);
        EmployeeProfile hrProfile = profile("hr-1", "TD010", "HR User");
        EmployeeProfile directReport = profile("employee-1", "TD011", "Direct Report");
        EmployeeProfile unauthorized = profile("employee-2", "TD012", "Other Employee");
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(hrProfile));
        when(reportingAssignmentRepository.findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(assignment("employee-1", "hr-1")));
        when(employeeProfileRepository.findByUserIdIn(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<String> ids = invocation.getArgument(0);
            return List.of(hrProfile, directReport, unauthorized).stream()
                    .filter(profile -> ids.contains(profile.getUserId()))
                    .toList();
        });
        when(userRepository.findByIdIn(anyCollection())).thenReturn(List.of(
                activeUser("hr-1", UserRole.HR), activeUser("employee-1", UserRole.EMPLOYEE), activeUser("employee-2", UserRole.EMPLOYEE)));
        when(employeeProfileRepository.findByOrganizationId(ORG_ID)).thenReturn(List.of(hrProfile, directReport, unauthorized));
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of(assignment("employee-1", "hr-1")));
        when(timesheetRepository.findByOrganizationIdAndUserIdInAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqual(
                eq(ORG_ID), anyCollection(), any(), any())).thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
                eq(ORG_ID), anyCollection(), any(), any())).thenReturn(List.of());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                eq(ORG_ID), eq("hr-1"), eq(LocalDate.of(2026, 9, 1)), eq(PayrollReadinessReminderType.AUTO_MANAGEMENT_DAY_7)))
                .thenReturn(Optional.empty());
        when(reminderRepository.findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
                eq(ORG_ID), anyString(), eq(LocalDate.of(2026, 9, 1)), eq(PayrollReadinessReminderType.AUTO_EMPLOYEE_DAY_7)))
                .thenReturn(Optional.of(PayrollReadinessReminder.builder().id("skip-employee-reminders").build()));
        when(notificationService.createNotification(eq(ORG_ID), eq("hr-1"), eq(NotificationType.PAYROLL_TIMESHEET_FOLLOW_UP_REQUIRED),
                eq("Payroll Readiness Update - September 2026"), contains("2 employees reviewed"), eq("2026-09-01"), eq("/myhr/hr/payroll-readiness?payrollMonth=2026-09-01")))
                .thenReturn(Notification.builder().id("summary-1").build());

        var result = service.runAutomaticForActor(hr, LocalDate.of(2026, 9, 7));

        assertThat(result.managementSummarySent()).isTrue();
        verify(notificationService).createNotification(eq(ORG_ID), eq("hr-1"), eq(NotificationType.PAYROLL_TIMESHEET_FOLLOW_UP_REQUIRED),
                eq("Payroll Readiness Update - September 2026"), contains("2 employees reviewed"), eq("2026-09-01"), eq("/myhr/hr/payroll-readiness?payrollMonth=2026-09-01"));
    }

    private void seedAdminScope(List<EmployeeProfile> profiles) {
        lenient().when(authenticatedUserService.require(eq(ADMIN_ID), anySet(), eq(false))).thenReturn(actor(ADMIN_ID, UserRole.COMPANY_ADMIN));
        when(employeeProfileRepository.findByOrganizationId(ORG_ID)).thenReturn(profiles);
        when(userRepository.findByIdIn(anyCollection())).thenReturn(profiles.stream()
                .map(profile -> activeUser(profile.getUserId(), profile.getUserId().equals("admin-1") ? UserRole.COMPANY_ADMIN : UserRole.EMPLOYEE))
                .toList());
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of());
    }

    private PayrollReadinessRowResponse row(PayrollReadinessResponse response, String userId) {
        return response.content().stream()
                .filter(row -> row.employeeUserId().equals(userId))
                .findFirst()
                .orElseThrow();
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor(String userId, UserRole role) {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                userId, userId, userId + "@example.test", role, ORG_ID, AccountStatus.ACTIVE, false);
    }

    private EmployeeProfile profile(String userId, String employeeId, String name) {
        return EmployeeProfile.builder()
                .id(userId + "-profile")
                .organizationId(ORG_ID)
                .userId(userId)
                .employeeId(employeeId)
                .name(name)
                .designation("Consultant")
                .build();
    }

    private User activeUser(String userId, UserRole role) {
        return User.builder()
                .id(userId)
                .organizationId(ORG_ID)
                .role(role)
                .accountStatus(AccountStatus.ACTIVE)
                .loginId(userId)
                .email(userId + "@example.test")
                .build();
    }

    private ReportingAssignment assignment(String employeeUserId, String managerUserId) {
        return ReportingAssignment.builder()
                .organizationId(ORG_ID)
                .employeeUserId(employeeUserId)
                .reportingManagerUserId(managerUserId)
                .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                .isActive(true)
                .build();
    }

    private Timesheet timesheet(String userId, TimesheetStatus status, LocalDate start, LocalDate end) {
        return timesheet(userId, status, start, end, new BigDecimal("8.00"));
    }

    private Timesheet timesheet(String userId, TimesheetStatus status, LocalDate start, LocalDate end, BigDecimal dailyHours) {
        return Timesheet.builder()
                .id(userId + "-timesheet-" + status + "-" + start)
                .organizationId(ORG_ID)
                .userId(userId)
                .periodStartDate(start)
                .periodEndDate(end)
                .entries(entries(start, end, dailyHours))
                .status(status)
                .build();
    }

    private List<TimesheetEntry> entries(LocalDate start, LocalDate end, BigDecimal dailyHours) {
        List<TimesheetEntry> entries = new ArrayList<>();
        LocalDate current = start;
        while (!current.isAfter(end)) {
            entries.add(TimesheetEntry.builder()
                    .workDate(current)
                    .dayOfWeek(current.getDayOfWeek().name())
                    .hoursWorked(dailyHours)
                    .build());
            current = current.plusDays(1);
        }
        return entries;
    }
}







