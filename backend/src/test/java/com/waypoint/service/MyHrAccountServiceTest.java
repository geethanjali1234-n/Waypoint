package com.waypoint.service;

import com.waypoint.dto.myhr.CreateEmployeeAccountRequest;
import com.waypoint.dto.myhr.CreateHrAccountRequest;
import com.waypoint.dto.myhr.EmployeeProfileFields;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.util.Set;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHrAccountServiceTest {

    @Mock UserRepository userRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock EmployeeProfileRepository employeeProfileRepository;
    @Mock OrganizationEmployeeIdService employeeIdService;
    @Mock ReportingAssignmentService reportingAssignmentService;
    @Mock TemporaryPasswordGenerator passwordGenerator;
    @Mock PasswordEncoder passwordEncoder;
    @Mock MyHrAuthenticatedUserService authenticatedUserService;
    @Mock MyHrCredentialDelivery credentialDelivery;
    @Mock AuditService auditService;
    @Mock InAppNotificationService inAppNotificationService;
    private MyHrAccountService service;

    @BeforeEach
    void setUp() {
        service = new MyHrAccountService(userRepository, organizationRepository, employeeProfileRepository,
                employeeIdService, reportingAssignmentService, passwordGenerator, passwordEncoder,
                authenticatedUserService, credentialDelivery, auditService, inAppNotificationService);
        lenient().when(organizationRepository.findById("org-1"))
                .thenReturn(java.util.Optional.of(Organization.builder().id("org-1").organizationName("Tardus Health").build()));
        lenient().when(credentialDelivery.deliver(anyString(), any(), anyString(), anyString(), anyString())).thenReturn(true);
        lenient().when(passwordGenerator.generate()).thenReturn("Temporary9!");
        lenient().when(passwordEncoder.encode("Temporary9!")).thenReturn("bcrypt-hash");
        lenient().when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("new-user");
            return user;
        });
        lenient().when(employeeProfileRepository.save(any(EmployeeProfile.class))).thenAnswer(invocation -> {
            EmployeeProfile profile = invocation.getArgument(0);
            profile.setId("profile-1");
            return profile;
        });
    }

    @Test
    void companyAdminCreatesHrWithSharedSequentialIdAndHashedPassword() {
        allowActor(UserRole.COMPANY_ADMIN);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.HR)).thenReturn("TD001");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));

        CreateHrAccountRequest request = new CreateHrAccountRequest();
        request.setProfile(profile("hr@tardus.test"));
        var result = service.createHr("admin-1", request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User stored = userCaptor.getValue();
        assertThat(stored.getLoginId()).isEqualTo("TD001");
        assertThat(stored.getRole()).isEqualTo(UserRole.HR);
        assertThat(stored.getOrganizationId()).isEqualTo("org-1");
        assertThat(stored.getPasswordHash()).isEqualTo("bcrypt-hash").isNotEqualTo("Temporary9!");
        assertThat(stored.getMustChangePassword()).isTrue();
        assertThat(result.emailDelivered()).isTrue();
        assertThat(java.util.Arrays.stream(result.getClass().getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("temporaryPassword", "password", "passwordHash");
        assertThat(result.employeeId()).isEqualTo("TD001");
        verify(credentialDelivery).deliver("hr@tardus.test", UserRole.HR, "Tardus Health", "TD001", "Temporary9!");

        ArgumentCaptor<EmployeeProfile> profileCaptor = ArgumentCaptor.forClass(EmployeeProfile.class);
        verify(employeeProfileRepository).save(profileCaptor.capture());
        assertCanonicalProfile(profileCaptor.getValue(), "hr@tardus.test", "TD001");

        ArgumentCaptor<ReportingAssignment> assignmentCaptor = ArgumentCaptor.forClass(ReportingAssignment.class);
        verify(reportingAssignmentService).createActiveAssignment(assignmentCaptor.capture());
        assertThat(assignmentCaptor.getValue().getEmployeeUserId()).isEqualTo("new-user");
        assertThat(assignmentCaptor.getValue().getReportingManagerUserId()).isEqualTo("admin-1");
        verify(employeeIdService).generateNextLoginId("org-1", UserRole.HR);
        verify(authenticatedUserService).require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN), false);
        verify(auditService).record(any(), eq(AuditActionType.HR_ACCOUNT_CREATED), eq("USER"),
                eq("new-user"), eq("Test User"), anyString(), isNull(), any());
        verify(auditService).record(any(), eq(AuditActionType.REPORTING_MANAGER_ASSIGNED), eq("USER"),
                eq("new-user"), eq("Test User"), anyString(), isNull(), any());
    }

    @Test
    void authorizedHrCreatesEmployeeAndPrimaryReportingAssignment() {
        allowActor(UserRole.HR);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.EMPLOYEE)).thenReturn("TD002");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));

        CreateEmployeeAccountRequest request = new CreateEmployeeAccountRequest();
        request.setProfile(profile("employee@tardus.test"));
        request.setReportingManagerUserId("manager-1");
        var result = service.createEmployee("hr-1", request);

        ArgumentCaptor<ReportingAssignment> assignmentCaptor = ArgumentCaptor.forClass(ReportingAssignment.class);
        verify(reportingAssignmentService).createActiveAssignment(assignmentCaptor.capture());
        assertThat(assignmentCaptor.getValue().getEmployeeUserId()).isEqualTo("new-user");
        assertThat(assignmentCaptor.getValue().getReportingManagerUserId()).isEqualTo("manager-1");
        assertThat(assignmentCaptor.getValue().getOrganizationId()).isEqualTo("org-1");
        assertThat(result.loginId()).isEqualTo("TD002");
        assertThat(result.employeeId()).isEqualTo("TD002");
        assertThat(result.role()).isEqualTo(UserRole.EMPLOYEE);
        verify(credentialDelivery).deliver("employee@tardus.test", UserRole.EMPLOYEE, "Tardus Health", "TD002", "Temporary9!");

        ArgumentCaptor<EmployeeProfile> profileCaptor = ArgumentCaptor.forClass(EmployeeProfile.class);
        verify(employeeProfileRepository).save(profileCaptor.capture());
        assertCanonicalProfile(profileCaptor.getValue(), "employee@tardus.test", "TD002");
        verify(employeeIdService).generateNextLoginId("org-1", UserRole.EMPLOYEE);
        verify(authenticatedUserService).require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false);
        verify(auditService).record(any(), eq(AuditActionType.EMPLOYEE_ACCOUNT_CREATED), eq("USER"),
                eq("new-user"), eq("Test User"), anyString(), isNull(), any());
        verify(auditService).record(any(), eq(AuditActionType.REPORTING_MANAGER_ASSIGNED), eq("USER"),
                eq("new-user"), eq("Test User"), anyString(), isNull(), any());
    }

    @Test
    void companyAdminCreatesEmployee() {
        allowActor(UserRole.COMPANY_ADMIN);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.EMPLOYEE)).thenReturn("TD002");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));

        CreateEmployeeAccountRequest request = new CreateEmployeeAccountRequest();
        request.setProfile(profile("employee@tardus.test"));
        request.setReportingManagerUserId("admin-1");

        assertThat(service.createEmployee("admin-1", request).role()).isEqualTo(UserRole.EMPLOYEE);
        verify(authenticatedUserService).require(
                "admin-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false);
    }

    @Test
    void responseUsesEmployeeIdFromSavedProfileIndependentlyOfLoginId() {
        allowActor(UserRole.COMPANY_ADMIN);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.HR)).thenReturn("LOGIN-001");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));
        when(employeeProfileRepository.save(any(EmployeeProfile.class))).thenAnswer(invocation -> {
            EmployeeProfile profile = invocation.getArgument(0);
            profile.setId("profile-1");
            profile.setEmployeeId("EMP-001");
            return profile;
        });
        CreateHrAccountRequest request = new CreateHrAccountRequest();
        request.setProfile(profile("hr@tardus.test"));

        var result = service.createHr("admin-1", request);

        assertThat(result.loginId()).isEqualTo("LOGIN-001");
        assertThat(result.employeeId()).isEqualTo("EMP-001");
    }

    @Test
    void emailFailureKeepsTheSavedAccountAndReportsItWasNotDelivered() {
        allowActor(UserRole.HR);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.EMPLOYEE)).thenReturn("TD002");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));
        when(credentialDelivery.deliver(anyString(), any(), anyString(), anyString(), anyString())).thenReturn(false);
        CreateEmployeeAccountRequest request = new CreateEmployeeAccountRequest();
        request.setProfile(profile("employee@tardus.test"));
        request.setReportingManagerUserId("manager-1");

        var result = service.createEmployee("hr-1", request);

        assertThat(result.emailDelivered()).isFalse();
        assertThat(result.loginId()).isEqualTo("TD002");
        verify(userRepository).save(any(User.class));
        verify(userRepository, org.mockito.Mockito.never()).deleteById(anyString());
    }

    @Test
    void repeatedRequestForAnExistingEmailCreatesNothingAndSendsNoSecondEmail() {
        allowActor(UserRole.COMPANY_ADMIN);
        when(userRepository.existsByEmail("hr@tardus.test")).thenReturn(true);
        CreateHrAccountRequest request = new CreateHrAccountRequest();
        request.setProfile(profile("hr@tardus.test"));

        assertThatThrownBy(() -> service.createHr("admin-1", request))
                .hasMessageContaining("Email already registered");

        verify(userRepository, org.mockito.Mockito.never()).save(any(User.class));
        verifyNoInteractions(credentialDelivery);
    }

    @Test
    void noEmailIsSentWhenAccountPersistenceFails() {
        allowActor(UserRole.COMPANY_ADMIN);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.HR)).thenReturn("TD001");
        when(reportingAssignmentService.createActiveAssignment(any())).thenThrow(new IllegalStateException("write failed"));
        CreateHrAccountRequest request = new CreateHrAccountRequest();
        request.setProfile(profile("hr@tardus.test"));

        assertThatThrownBy(() -> service.createHr("admin-1", request)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(credentialDelivery);
    }

    @Test
    void hrCannotCreateHr() {
        rejectActor("hr-1", Set.of(UserRole.COMPANY_ADMIN));
        assertThatThrownBy(() -> service.createHr("hr-1", new CreateHrAccountRequest()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void employeeCannotCreateHr() {
        rejectActor("employee-1", Set.of(UserRole.COMPANY_ADMIN));
        assertThatThrownBy(() -> service.createHr("employee-1", new CreateHrAccountRequest()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void employeeCannotCreateEmployee() {
        rejectActor("employee-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR));
        assertThatThrownBy(() -> service.createEmployee("employee-1", new CreateEmployeeAccountRequest()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void eligibleReportingManagersAreActiveHrUsersFromActorsOrganization() {
        allowActor(UserRole.HR);
        User manager = User.builder().id("manager-1").loginId("TD010").role(UserRole.HR)
                .organizationId("org-1").accountStatus(AccountStatus.ACTIVE).build();
        EmployeeProfile managerProfile = EmployeeProfile.builder().userId("manager-1")
                .firstName("Sarah").lastName("Jones").build();
        when(userRepository.findByOrganizationIdAndRoleAndAccountStatus(
                "org-1", UserRole.HR, AccountStatus.ACTIVE)).thenReturn(List.of(manager));
        when(employeeProfileRepository.findByUserId("manager-1")).thenReturn(java.util.Optional.of(managerProfile));

        var result = service.getEligibleReportingManagers("hr-1");

        assertThat(result).singleElement().satisfies(option -> {
            assertThat(option.userId()).isEqualTo("manager-1");
            assertThat(option.loginId()).isEqualTo("TD010");
            assertThat(option.firstName()).isEqualTo("Sarah");
            assertThat(option.lastName()).isEqualTo("Jones");
        });
        verify(authenticatedUserService).require(
                "hr-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR), false);
        verify(userRepository).findByOrganizationIdAndRoleAndAccountStatus(
                "org-1", UserRole.HR, AccountStatus.ACTIVE);
    }

    @Test
    void createEmployeeNotifiesTheAssignedReportingManager() {
        allowActor(UserRole.HR);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.EMPLOYEE)).thenReturn("TD002");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById("manager-1")).thenReturn(java.util.Optional.of(
                User.builder().id("manager-1").loginId("TD010").role(UserRole.HR)
                        .organizationId("org-1").accountStatus(AccountStatus.ACTIVE).build()));

        CreateEmployeeAccountRequest request = new CreateEmployeeAccountRequest();
        request.setProfile(profile("employee@tardus.test"));
        request.setReportingManagerUserId("manager-1");
        service.createEmployee("hr-1", request);

        verify(inAppNotificationService).createNotification(eq("org-1"), eq("manager-1"),
                eq(NotificationType.EMPLOYEE_ASSIGNED), eq("New employee assigned"),
                contains("Test User"), eq("new-user"), contains("/employees"));
    }

    @Test
    void createHrDoesNotNotifyTheCreatingCompanyAdminAboutTheirOwnAssignment() {
        // createHr always assigns the creating Company Admin as the new HR's
        // manager - notifying them about their own action would be pointless.
        allowActor(UserRole.COMPANY_ADMIN);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.HR)).thenReturn("TD001");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));

        CreateHrAccountRequest request = new CreateHrAccountRequest();
        request.setProfile(profile("hr@tardus.test"));
        service.createHr("admin-1", request);

        verifyNoInteractions(inAppNotificationService);
    }

    @Test
    void aNotificationFailureNeverBreaksAnOtherwiseSuccessfulEmployeeCreation() {
        // Regression test: notification creation is secondary to account
        // creation itself - a transient failure there must never surface as a
        // failure of the account creation.
        allowActor(UserRole.HR);
        when(employeeIdService.generateNextLoginId("org-1", UserRole.EMPLOYEE)).thenReturn("TD002");
        when(reportingAssignmentService.createActiveAssignment(any())).thenAnswer(i -> i.getArgument(0));
        when(userRepository.findById("manager-1")).thenReturn(java.util.Optional.of(
                User.builder().id("manager-1").loginId("TD010").role(UserRole.HR)
                        .organizationId("org-1").accountStatus(AccountStatus.ACTIVE).build()));
        doThrow(new IllegalArgumentException("boom")).when(inAppNotificationService)
                .createNotification(any(), any(), any(), any(), any(), any(), any());

        CreateEmployeeAccountRequest request = new CreateEmployeeAccountRequest();
        request.setProfile(profile("employee@tardus.test"));
        request.setReportingManagerUserId("manager-1");

        var result = service.createEmployee("hr-1", request);

        assertThat(result.role()).isEqualTo(UserRole.EMPLOYEE);
    }

    @Test
    void employeeCannotListEligibleReportingManagers() {
        rejectActor("employee-1", Set.of(UserRole.COMPANY_ADMIN, UserRole.HR));
        assertThatThrownBy(() -> service.getEligibleReportingManagers("employee-1"))
                .isInstanceOf(ForbiddenException.class);
    }

    private void rejectActor(String actorUserId, Set<UserRole> allowedRoles) {
        when(authenticatedUserService.require(eq(actorUserId), eq(allowedRoles), eq(false)))
                .thenThrow(new ForbiddenException("You do not have access to this resource"));
    }

    private void allowActor(UserRole role) {
        when(authenticatedUserService.require(any(), any(), org.mockito.ArgumentMatchers.eq(false)))
                .thenReturn(new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                        role == UserRole.COMPANY_ADMIN ? "admin-1" : "hr-1",
                        role == UserRole.COMPANY_ADMIN ? "TDADMIN" : "TD001",
                        "actor@tardus.test", role, "org-1", AccountStatus.ACTIVE, false));
    }

    private EmployeeProfileFields profile(String email) {
        EmployeeProfileFields fields = new EmployeeProfileFields();
        fields.setFirstName(" Test ");
        fields.setLastName(" User ");
        fields.setPersonalEmail(email);
        fields.setWorkEmail(" WORK@TARDUS.TEST ");
        fields.setPhoneNumber("555-0100");
        fields.setDesignation("Engineer");
        fields.setEmploymentType(EmploymentType.FULL_TIME);
        fields.setStartDate(LocalDate.of(2026, 9, 1));
        fields.setStreetAddress("1 Main Street");
        fields.setCity("Austin");
        fields.setState("Texas");
        fields.setZipCode("78701");
        return fields;
    }

    private void assertCanonicalProfile(EmployeeProfile profile, String personalEmail, String employeeId) {
        assertThat(profile.getOrganizationId()).isEqualTo("org-1");
        assertThat(profile.getUserId()).isEqualTo("new-user");
        assertThat(profile.getEmployeeId()).isEqualTo(employeeId);
        assertThat(profile.getFirstName()).isEqualTo("Test");
        assertThat(profile.getLastName()).isEqualTo("User");
        assertThat(profile.getName()).isEqualTo("Test User");
        assertThat(profile.getPersonalEmail()).isEqualTo(personalEmail);
        assertThat(profile.getWorkEmail()).isEqualTo("work@tardus.test");
        assertThat(profile.getPhoneNumber()).isEqualTo("555-0100");
        assertThat(profile.getDesignation()).isEqualTo("Engineer");
        assertThat(profile.getEmploymentType()).isEqualTo(EmploymentType.FULL_TIME);
        assertThat(profile.getStartDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(profile.getStreetAddress()).isEqualTo("1 Main Street");
        assertThat(profile.getCity()).isEqualTo("Austin");
        assertThat(profile.getState()).isEqualTo("Texas");
        assertThat(profile.getZipCode()).isEqualTo("78701");
        assertThat(profile.getCreatedByUserId()).isNotBlank();
        assertThat(profile.getCreatedAt()).isNotNull();
        assertThat(profile.getUpdatedAt()).isNotNull();
        assertThat(profile.getHourlyRate()).isNull();
    }
}
