package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrEmployeeHoursReportResponse;
import com.waypoint.dto.myhr.MyHrReportEmployeeRow;
import com.waypoint.dto.myhr.MyHrTimesheetReportResponse;
import com.waypoint.dto.myhr.MyHrWorkforceReportResponse;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.Timesheet;
import com.waypoint.entity.TimesheetEntry;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.TimesheetStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.TimesheetRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationResults;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.bson.Document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHrReportServiceTest {

    private static final String ORG_ID = "org-1";

    @Mock
    private EmployeeProfileRepository employeeProfileRepository;

    @Mock
    private TimesheetRepository timesheetRepository;

    @Mock
    private ReportingAssignmentRepository reportingAssignmentRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    private MyHrReportService service;

    private MyHrReportService service() {
        return new MyHrReportService(
                employeeProfileRepository, timesheetRepository, reportingAssignmentRepository, userRepository, mongoTemplate);
    }

    @Test
    void reflectsTheMostRecentTimesheetStatusEvenWhenAnOlderOneWasSubmitted() {
        service = service();
        EmployeeProfile employee = profile("employee-1", "TD002", "lilly m");
        when(employeeProfileRepository.findByOrganizationId(ORG_ID)).thenReturn(List.of(employee));
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of(activeUser("employee-1")));

        Timesheet olderSubmitted = timesheet("employee-1", TimesheetStatus.SUBMITTED, "40", 5);
        Timesheet newerRejected = timesheet("employee-1", TimesheetStatus.REJECTED, "40", 1);
        when(timesheetRepository.findByOrganizationIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
                eq(ORG_ID), any(), any()))
                .thenReturn(List.of(newerRejected, olderSubmitted));

        MyHrWorkforceReportResponse response = service.getWorkforceReport(companyAdmin(), 30);

        assertThat(response.employees()).hasSize(1);
        assertThat(response.employees().get(0).timesheetStatus()).isEqualTo(TimesheetStatus.REJECTED);
    }

    @Test
    void excludesRejectedAndDraftHoursFromTheTotal() {
        service = service();
        EmployeeProfile employee = profile("employee-1", "TD002", "lilly m");
        when(employeeProfileRepository.findByOrganizationId(ORG_ID)).thenReturn(List.of(employee));
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of());
        when(userRepository.findAllById(any())).thenReturn(List.of(activeUser("employee-1")));

        Timesheet rejected = timesheet("employee-1", TimesheetStatus.REJECTED, "20", 1);
        Timesheet approved = timesheet("employee-1", TimesheetStatus.APPROVED, "15", 2);
        when(timesheetRepository.findByOrganizationIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
                eq(ORG_ID), any(), any()))
                .thenReturn(List.of(rejected, approved));

        MyHrWorkforceReportResponse response = service.getWorkforceReport(companyAdmin(), 30);

        MyHrReportEmployeeRow row = response.employees().get(0);
        assertThat(row.totalHours()).isEqualByComparingTo(new BigDecimal("15"));
    }

    @Test
    void fallsBackToLoginIdWhenAReportingManagerHasNoEmployeeProfile() {
        service = service();
        EmployeeProfile employee = profile("employee-1", "TD002", "lilly m");
        when(employeeProfileRepository.findByOrganizationId(ORG_ID)).thenReturn(List.of(employee));

        ReportingAssignment assignment = ReportingAssignment.builder()
                .organizationId(ORG_ID)
                .employeeUserId("employee-1")
                .reportingManagerUserId("admin-1")
                .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER)
                .isActive(true)
                .build();
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of(assignment));

        User adminWithNoProfile = User.builder()
                .id("admin-1").organizationId(ORG_ID).role(UserRole.COMPANY_ADMIN)
                .accountStatus(AccountStatus.ACTIVE).loginId("TDADMIN").email("admin@tardus.test").build();
        when(userRepository.findAllById(any())).thenReturn(List.of(activeUser("employee-1"), adminWithNoProfile));
        when(timesheetRepository.findByOrganizationIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
                eq(ORG_ID), any(), any())).thenReturn(List.of());

        MyHrWorkforceReportResponse response = service.getWorkforceReport(companyAdmin(), 30);

        assertThat(response.employees().get(0).reportingManagerName()).isEqualTo("TDADMIN");
        assertThat(response.managerSummary()).hasSize(1);
        assertThat(response.managerSummary().get(0).managerName()).isEqualTo("TDADMIN");
    }

    @Test
    void scopesToOnlyTheHrsOwnDirectReports() {
        service = service();
        EmployeeProfile ownReport = profile("employee-1", "TD002", "lilly m");
        EmployeeProfile someoneElsesReport = profile("employee-2", "TD003", "jack h");
        when(employeeProfileRepository.findByOrganizationId(ORG_ID))
                .thenReturn(List.of(ownReport, someoneElsesReport));

        ReportingAssignment toMe = ReportingAssignment.builder()
                .organizationId(ORG_ID).employeeUserId("employee-1").reportingManagerUserId("hr-1")
                .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER).isActive(true).build();
        ReportingAssignment toSomeoneElse = ReportingAssignment.builder()
                .organizationId(ORG_ID).employeeUserId("employee-2").reportingManagerUserId("hr-2")
                .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER).isActive(true).build();
        when(reportingAssignmentRepository.findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
                ORG_ID, RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of(toMe, toSomeoneElse));
        when(userRepository.findAllById(any())).thenReturn(List.of(activeUser("employee-1")));
        when(timesheetRepository.findByOrganizationIdAndReportingManagerUserIdAndSubmittedAtBetweenOrderBySubmittedAtDesc(
                eq(ORG_ID), eq("hr-1"), any(), any())).thenReturn(List.of());

        MyHrWorkforceReportResponse response = service.getWorkforceReport(hrUser("hr-1"), 30);

        assertThat(response.scope()).isEqualTo("DIRECT_REPORTS");
        assertThat(response.employees()).extracting(MyHrReportEmployeeRow::employeeId).containsExactly("TD002");
        assertThat(response.managerSummary()).isEmpty();
    }

    @Test
    void companyAdminCanViewAnyEmployeesHoursInADateRange() {
        service = service();
        EmployeeProfile employee = profile("employee-1", "TD002", "lilly m");
        when(employeeProfileRepository.findByOrganizationIdAndEmployeeId(ORG_ID, "TD002"))
                .thenReturn(Optional.of(employee));

        Timesheet approved = periodTimesheet(TimesheetStatus.APPROVED, "20", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));
        Timesheet rejected = periodTimesheet(TimesheetStatus.REJECTED, "15", LocalDate.of(2026, 6, 8), LocalDate.of(2026, 6, 14));
        when(timesheetRepository
                .findByOrganizationIdAndUserIdAndPeriodStartDateLessThanEqualAndPeriodEndDateGreaterThanEqualOrderByPeriodStartDateDesc(
                        eq(ORG_ID), eq("employee-1"), any(), any()))
                .thenReturn(List.of(rejected, approved));

        MyHrEmployeeHoursReportResponse response = service.getEmployeeHoursReport(
                companyAdmin(), "TD002", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30));

        assertThat(response.totalHours()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(response.timesheets()).hasSize(2);
    }

    @Test
    void timesheetReportTotalsOnlyHoursInsideSelectedDatesForOverlappingTimesheets() {
        service = service();
        EmployeeProfile employee = profile("employee-1", "TD002", "lilly m");
        Timesheet overlapping = periodTimesheet(TimesheetStatus.APPROVED, "24", LocalDate.of(2026, 7, 27), LocalDate.of(2026, 8, 2));
        overlapping.setId("timesheet-1");
        overlapping.setEntries(List.of(
                TimesheetEntry.builder().workDate(LocalDate.of(2026, 7, 31)).hoursWorked(new BigDecimal("8")).build(),
                TimesheetEntry.builder().workDate(LocalDate.of(2026, 8, 1)).hoursWorked(new BigDecimal("8")).build(),
                TimesheetEntry.builder().workDate(LocalDate.of(2026, 8, 2)).hoursWorked(new BigDecimal("8")).build()));

        when(mongoTemplate.count(any(Query.class), eq(Timesheet.class))).thenReturn(1L);
        when(mongoTemplate.find(any(Query.class), eq(Timesheet.class))).thenReturn(List.of(overlapping));
        when(mongoTemplate.findDistinct(any(Query.class), eq("userId"), eq(Timesheet.class), eq(String.class)))
                .thenReturn(List.of("employee-1"));
        when(mongoTemplate.aggregate(any(Aggregation.class), anyString(), eq(Document.class)))
                .thenReturn(new AggregationResults<>(List.of(new Document("hours", new BigDecimal("16"))), new Document()));
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of(employee));
        when(userRepository.findByIdIn(any())).thenReturn(List.of(activeUser("employee-1")));

        MyHrTimesheetReportResponse response = service.getTimesheetReport(
                companyAdmin(), "ORGANIZATION", null,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                null, "ALL", "ALL", "PERIOD_DESC", 0, 25);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).periodStartDate()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(response.content().get(0).periodEndDate()).isEqualTo(LocalDate.of(2026, 8, 31));
        assertThat(response.content().get(0).totalHours()).isEqualByComparingTo(new BigDecimal("16"));
        assertThat(response.summary().totalWorkedHours()).isEqualByComparingTo(new BigDecimal("16"));
    }

    @Test
    void hrCannotViewHoursForSomeoneWhoIsNotTheirDirectReport() {
        service = service();
        EmployeeProfile employee = profile("employee-2", "TD003", "jack h");
        when(employeeProfileRepository.findByOrganizationIdAndEmployeeId(ORG_ID, "TD003"))
                .thenReturn(Optional.of(employee));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "employee-2", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(ReportingAssignment.builder().reportingManagerUserId("hr-2").build()));

        assertThatThrownBy(() -> service.getEmployeeHoursReport(
                hrUser("hr-1"), "TD003", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rejectsAnEndDateBeforeTheStartDate() {
        service = service();

        assertThatThrownBy(() -> service.getEmployeeHoursReport(
                companyAdmin(), "TD002", LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void throwsWhenTheEmployeeDoesNotExist() {
        service = service();
        when(employeeProfileRepository.findByOrganizationIdAndEmployeeId(ORG_ID, "TD999"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEmployeeHoursReport(
                companyAdmin(), "TD999", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Timesheet periodTimesheet(TimesheetStatus status, String hours, LocalDate periodStart, LocalDate periodEnd) {
        return Timesheet.builder()
                .organizationId(ORG_ID).userId("employee-1").status(status)
                .totalHours(new BigDecimal(hours))
                .periodStartDate(periodStart).periodEndDate(periodEnd)
                .build();
    }

    private EmployeeProfile profile(String userId, String employeeId, String name) {
        return EmployeeProfile.builder()
                .userId(userId).organizationId(ORG_ID).employeeId(employeeId).name(name).build();
    }

    private User activeUser(String userId) {
        return User.builder().id(userId).organizationId(ORG_ID).role(UserRole.EMPLOYEE)
                .accountStatus(AccountStatus.ACTIVE).build();
    }

    private Timesheet timesheet(String userId, TimesheetStatus status, String hours, int daysAgo) {
        return Timesheet.builder()
                .organizationId(ORG_ID).userId(userId).status(status)
                .totalHours(new BigDecimal(hours))
                .submittedAt(LocalDateTime.now().minusDays(daysAgo))
                .build();
    }

    private AuthenticatedMyHrUser companyAdmin() {
        return new AuthenticatedMyHrUser(
                "admin-1", "TDADMIN", "admin@tardus.test", UserRole.COMPANY_ADMIN, ORG_ID,
                AccountStatus.ACTIVE, false);
    }

    private AuthenticatedMyHrUser hrUser(String userId) {
        return new AuthenticatedMyHrUser(
                userId, "TDHR", "hr@tardus.test", UserRole.HR, ORG_ID, AccountStatus.ACTIVE, false);
    }
}
