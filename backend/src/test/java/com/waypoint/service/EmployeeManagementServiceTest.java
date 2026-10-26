package com.waypoint.service;

import com.waypoint.dto.myhr.ChangeReportingManagerRequest;
import com.waypoint.dto.myhr.EligibleReportingManagerResponse;
import com.waypoint.dto.myhr.EmployeeDetailResponse;
import com.waypoint.dto.myhr.EmployeeSummaryResponse;
import com.waypoint.dto.myhr.MyHrOwnProfileResponse;
import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.ReportingAssignment;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.RelationshipType;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.MyHrAuthenticatedUserService.AuthenticatedMyHrUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.mock.web.MockMultipartFile;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployeeManagementServiceTest {

    @Mock UserRepository userRepository;
    @Mock EmployeeProfileRepository employeeProfileRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock ReportingAssignmentRepository reportingAssignmentRepository;
    @Mock ReportingAssignmentService reportingAssignmentService;
    @Mock MyHrAuthenticatedUserService authenticatedUserService;
    @Mock AuditService auditService;
    @Mock InAppNotificationService inAppNotificationService;
    @Mock FileStorageService fileStorageService;
    @Mock OrganizationSettingsRepository organizationSettingsRepository;

    private EmployeeManagementService service;

    @BeforeEach
    void setUp() {
        service = new EmployeeManagementService(userRepository, employeeProfileRepository, organizationRepository,
                reportingAssignmentRepository, reportingAssignmentService, authenticatedUserService, auditService,
                inAppNotificationService, fileStorageService, organizationSettingsRepository);
        lenient().when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of());
        lenient().when(userRepository.findByIdIn(any())).thenReturn(List.of());
    }

    private void actor(String userId, UserRole role) {
        when(authenticatedUserService.require(eq(userId), any(), eq(false)))
                .thenReturn(new AuthenticatedMyHrUser(userId, "TD000", "actor@tardus.test",
                        role, "org-1", AccountStatus.ACTIVE, false));
    }

    private User user(String id, UserRole role, AccountStatus status) {
        return User.builder().id(id).loginId(id).email(id + "@tardus.test")
                .organizationId("org-1").role(role).accountStatus(status).build();
    }

    private ReportingAssignment active(String employeeUserId, String managerUserId) {
        return ReportingAssignment.builder()
                .organizationId("org-1").employeeUserId(employeeUserId).reportingManagerUserId(managerUserId)
                .relationshipType(RelationshipType.PRIMARY_REPORTING_MANAGER).isActive(true)
                .effectiveFrom(LocalDate.now()).build();
    }

    @Test
    void hrListsOnlyOwnDirectReports() {
        actor("hr-1", UserRole.HR);
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(active("emp-1", "hr-1")));
        when(userRepository.findByIdIn(List.of("emp-1")))
                .thenReturn(List.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));

        List<EmployeeSummaryResponse> result = service.list("hr-1");

        assertThat(result).extracting(EmployeeSummaryResponse::userId).containsExactly("emp-1");
        assertThat(result.get(0).personType()).isEqualTo("EMPLOYEE");
    }

    @Test
    void companyAdminListsAllHrAndEmployeesInOrganization() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findByOrganizationIdAndRoleIn("org-1", List.of(UserRole.HR, UserRole.EMPLOYEE)))
                .thenReturn(List.of(
                        user("hr-1", UserRole.HR, AccountStatus.ACTIVE),
                        user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                anyString(), eq(RelationshipType.PRIMARY_REPORTING_MANAGER)))
                .thenReturn(Optional.empty());

        List<EmployeeSummaryResponse> result = service.list("admin-1");

        assertThat(result).extracting(EmployeeSummaryResponse::userId)
                .containsExactlyInAnyOrder("hr-1", "emp-1");
    }

    @Test
    void hrCannotViewEmployeeThatIsNotADirectReport() {
        actor("hr-1", UserRole.HR);
        when(userRepository.findById("emp-9"))
                .thenReturn(Optional.of(user("emp-9", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-9", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-9", "hr-2")));

        assertThatThrownBy(() -> service.getDetail("hr-1", "emp-9"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("direct reports");
    }

    @Test
    void updateAccountStatusRejectsSelfAndBlocked() {
        actor("hr-1", UserRole.HR);

        assertThatThrownBy(() -> service.updateAccountStatus("hr-1", "hr-1", AccountStatus.INACTIVE, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("your own account");

        assertThatThrownBy(() -> service.updateAccountStatus("hr-1", "emp-1", AccountStatus.BLOCKED, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ACTIVE or INACTIVE");
    }

    @Test
    void updateAccountStatusPersistsChangeForDirectReport() {
        actor("hr-1", UserRole.HR);
        User target = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(target));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1"))
                .thenReturn(List.of(active("emp-1", "hr-1")));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());

        EmployeeDetailResponse detail =
                service.updateAccountStatus("hr-1", "emp-1", AccountStatus.INACTIVE, null);

        assertThat(target.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(detail.accountStatus()).isEqualTo(AccountStatus.INACTIVE);
        verify(userRepository).save(target);
    }

    @Test
    void adminViewingHrSeesReportingManagerHistoryAndDirectReports() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.empty());
        ReportingAssignment reportsToAdmin = active("hr-1", "admin-1");
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("hr-1"))
                .thenReturn(List.of(reportsToAdmin));
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(active("emp-1", "hr-1"), active("emp-2", "hr-1")));
        when(userRepository.findByIdIn(List.of("emp-1", "emp-2"))).thenReturn(List.of(
                user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE),
                user("emp-2", UserRole.EMPLOYEE, AccountStatus.INACTIVE)));

        EmployeeDetailResponse detail = service.getDetail("admin-1", "hr-1");

        assertThat(detail.reportingManager()).isNotNull();
        assertThat(detail.reportingHistory()).hasSize(1);
        assertThat(detail.directReports()).extracting("userId")
                .containsExactlyInAnyOrder("emp-1", "emp-2");
    }

    @Test
    void employeeCanViewTheirOwnProfileDetail() {
        actor("emp-1", UserRole.EMPLOYEE);
        User employee = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(employee));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.of(
                EmployeeProfile.builder().userId("emp-1").employeeId("TD002")
                        .name("Jordan Lee").firstName("Jordan").lastName("Lee")
                        .personalEmail("jordan.personal@example.com").build()));
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(
                Organization.builder().id("org-1").organizationName("Tardus").organizationCode("TD")
                        .streetAddress("200 Lake Street").city("Austin").state("TX").zipCode("78701")
                        .phoneNumber("+15551234567").website("https://tardus.example.com")
                        .einNumber("84-3596475").eVerifyNumber("1533740")
                        .organizationLogoDocumentId("company-logos/org-1/logo.png")
                        .organizationSignatureDocumentId("company-signatures/org-1/signature.png").build()));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(
                User.builder().id("hr-1").loginId("TD001").role(UserRole.HR).organizationId("org-1").build()));
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.of(
                EmployeeProfile.builder().userId("hr-1").employeeId("TD001").name("Riley Chen").build()));

        MyHrOwnProfileResponse detail = service.getOwnDetail("emp-1");

        assertThat(detail.userId()).isEqualTo("emp-1");
        assertThat(detail.employeeId()).isEqualTo("TD002");
        assertThat(detail.name()).isEqualTo("Jordan Lee");
        assertThat(detail.organizationName()).isEqualTo("Tardus");
        assertThat(detail.organizationAddress()).isEqualTo("200 Lake Street, Austin, TX 78701");
        assertThat(detail.organizationPhoneNumber()).isEqualTo("+15551234567");
        assertThat(detail.organizationWebsite()).isEqualTo("https://tardus.example.com");
        assertThat(detail.organizationEinNumber()).isEqualTo("84-3596475");
        assertThat(detail.organizationEVerifyNumber()).isEqualTo("1533740");
        assertThat(detail.organizationLogoUrl()).isEqualTo("/uploads/company-logos/org-1/logo.png");
        assertThat(detail.organizationSignatureUrl()).isEqualTo("/uploads/company-signatures/org-1/signature.png");
        assertThat(detail.accountEmail()).isEqualTo("emp-1@tardus.test");
        assertThat(detail.personalEmail()).isEqualTo("jordan.personal@example.com");
        assertThat(detail.reportingManager()).isNotNull();
        assertThat(detail.reportingManager().userId()).isEqualTo("hr-1");
        assertThat(detail.reportingManager().employeeId()).isEqualTo("TD001");
        assertThat(detail.reportingManager().name()).isEqualTo("Riley Chen");
    }

    @Test
    void hrCanViewTheirOwnProfileDetail() {
        actor("hr-1", UserRole.HR);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.empty());
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(
                Organization.builder().id("org-1").organizationName("Tardus").organizationCode("TD").build()));

        MyHrOwnProfileResponse detail = service.getOwnDetail("hr-1");

        assertThat(detail.userId()).isEqualTo("hr-1");
        assertThat(detail.role()).isEqualTo(UserRole.HR);
    }

    @Test
    void companyAdminCanViewOwnAccountWithoutEmployeeProfile() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findById("admin-1"))
                .thenReturn(Optional.of(user("admin-1", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE)));
        when(employeeProfileRepository.findByUserId("admin-1")).thenReturn(Optional.empty());
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(
                Organization.builder().id("org-1").organizationName("Tardus").organizationCode("TD").build()));

        MyHrOwnProfileResponse detail = service.getOwnDetail("admin-1");

        assertThat(detail.loginId()).isEqualTo("admin-1");
        assertThat(detail.accountEmail()).isEqualTo("admin-1@tardus.test");
        assertThat(detail.personalEmail()).isNull();
        assertThat(detail.employeeId()).isNull();
        assertThat(detail.organizationAddress()).isEqualTo("");
        assertThat(detail.organizationPhoneNumber()).isNull();
    }

    private void ownAccount(String userId, UserRole role) {
        actor(userId, role);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId, role, AccountStatus.ACTIVE)));
        when(employeeProfileRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(
                Organization.builder().id("org-1").organizationName("Tardus").organizationCode("TD")
                        .organizationSignatureDocumentId("company-signatures/org-1/signature.png").build()));
    }

    @Test
    void ownProfileIncludesTheUsersOwnSignatureSeparatelyFromTheOrganizationSignature() {
        ownAccount("hr-1", UserRole.HR);
        when(fileStorageService.findStoredPath("user-signatures", "hr-1"))
                .thenReturn(Optional.of("gridfs/6ab5000000000000000000aa/organization-signature.png"));

        MyHrOwnProfileResponse detail = service.getOwnDetail("hr-1");

        assertThat(detail.signatureUrl()).isEqualTo("/uploads/gridfs/6ab5000000000000000000aa/organization-signature.png");
        assertThat(detail.organizationSignatureUrl()).isEqualTo("/uploads/company-signatures/org-1/signature.png");
    }

    @Test
    void anyMyHrRoleStoresItsSignatureForTheAuthenticatedUserOnly() {
        for (var entry : List.of(List.of("admin-1", UserRole.COMPANY_ADMIN), List.of("hr-1", UserRole.HR), List.of("emp-1", UserRole.EMPLOYEE))) {
            String userId = (String) entry.get(0);
            ownAccount(userId, (UserRole) entry.get(1));
            MockMultipartFile file = new MockMultipartFile("file", "organization-signature.png", "image/png", new byte[]{1, 2, 3});
            when(fileStorageService.findStoredPath("user-signatures", userId)).thenReturn(Optional.of("gridfs/abc/organization-signature.png"));

            MyHrOwnProfileResponse detail = service.uploadOwnSignature(userId, file);

            verify(fileStorageService).store(file, "user-signatures", userId);
            assertThat(detail.userId()).isEqualTo(userId);
            assertThat(detail.signatureUrl()).isEqualTo("/uploads/gridfs/abc/organization-signature.png");
        }
    }

    @Test
    void rejectsAnOwnSignatureThatIsNotAPngOrJpegImage() {
        actor("emp-1", UserRole.EMPLOYEE);
        MockMultipartFile file = new MockMultipartFile("file", "signature.pdf", "application/pdf", new byte[]{1});

        assertThatThrownBy(() -> service.uploadOwnSignature("emp-1", file))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Use a PNG or JPEG signature no larger than 2 MB");
        verify(fileStorageService, never()).store(any(), any(), any());
    }

    @Test
    void removesOnlyTheAuthenticatedUsersOwnSignature() {
        ownAccount("emp-1", UserRole.EMPLOYEE);
        when(fileStorageService.findStoredPath("user-signatures", "emp-1")).thenReturn(Optional.empty());

        MyHrOwnProfileResponse detail = service.removeOwnSignature("emp-1");

        verify(fileStorageService).remove("user-signatures", "emp-1");
        assertThat(detail.signatureUrl()).isNull();
        // The organization's official signature is untouched.
        assertThat(detail.organizationSignatureUrl()).isEqualTo("/uploads/company-signatures/org-1/signature.png");
    }

    @Test
    void ownProfileUsesAuthenticatedOrganizationAndDoesNotExposeAnotherOrganization() {
        actor("emp-1", UserRole.EMPLOYEE);
        when(userRepository.findById("emp-1"))
                .thenReturn(Optional.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(
                Organization.builder().id("org-1").organizationName("Tardus").organizationCode("TD").build()));

        MyHrOwnProfileResponse detail = service.getOwnDetail("emp-1");

        assertThat(detail.organizationId()).isEqualTo("org-1");
        verify(organizationRepository).findById("org-1");
        verify(organizationRepository, never()).findById("org-2");
    }

    @Test
    void unsupportedRoleIsRejectedBeforeProfileDataIsLoaded() {
        when(authenticatedUserService.require(eq("other-1"), eq(Set.of(
                UserRole.COMPANY_ADMIN, UserRole.EMPLOYEE, UserRole.HR)), eq(false)))
                .thenThrow(new ForbiddenException("You do not have access to this resource"));

        assertThatThrownBy(() -> service.getOwnDetail("other-1")).isInstanceOf(ForbiddenException.class);
        verify(userRepository, never()).findById(anyString());
        verify(organizationRepository, never()).findById(anyString());
    }

    @Test
    void changeReportingManagerCreatesAssignmentWhenNoneActive() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.empty());
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("hr-1")).thenReturn(List.of());
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(List.of());
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.empty());

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("admin-1");
        service.changeReportingManager("admin-1", "hr-1", request);

        verify(reportingAssignmentService).createActiveAssignment(any(ReportingAssignment.class));
    }

    @Test
    void changeReportingManagerDelegatesToReportingAssignmentService() {
        actor("hr-1", UserRole.HR);
        when(userRepository.findById("emp-1"))
                .thenReturn(Optional.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1"))
                .thenReturn(List.of(active("emp-1", "hr-2")));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-2");

        service.changeReportingManager("hr-1", "emp-1", request);

        verify(reportingAssignmentService).reassignPrimaryManager(
                eq("org-1"), eq("emp-1"), eq("hr-2"), eq("hr-1"), any(LocalDate.class));
    }

    @Test
    void changeReportingManagerRejectsAPastEffectiveDate() {
        actor("hr-1", UserRole.HR);
        when(userRepository.findById("emp-1"))
                .thenReturn(Optional.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-2");
        request.setEffectiveDate(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.changeReportingManager("hr-1", "emp-1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be in the past");
    }

    @Test
    void changeReportingManagerRejectsAnEffectiveDateNotAfterTheEmployeesStartDate() {
        actor("hr-1", UserRole.HR);
        when(userRepository.findById("emp-1"))
                .thenReturn(Optional.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        LocalDate startDate = LocalDate.now().plusDays(5);
        when(employeeProfileRepository.findByUserId("emp-1"))
                .thenReturn(Optional.of(EmployeeProfile.builder().userId("emp-1").startDate(startDate).build()));

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-2");
        request.setEffectiveDate(startDate);

        assertThatThrownBy(() -> service.changeReportingManager("hr-1", "emp-1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be after the employee's start date");
    }

    @Test
    void changeReportingManagerDefaultsEffectiveDateToTheLaterOfTodayOrDayAfterStart() {
        actor("hr-1", UserRole.HR);
        when(userRepository.findById("emp-1"))
                .thenReturn(Optional.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1"))
                .thenReturn(List.of(active("emp-1", "hr-2")));
        LocalDate startDate = LocalDate.now().plusDays(3);
        when(employeeProfileRepository.findByUserId("emp-1"))
                .thenReturn(Optional.of(EmployeeProfile.builder().userId("emp-1").startDate(startDate).build()));

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-2");

        service.changeReportingManager("hr-1", "emp-1", request);

        verify(reportingAssignmentService).reassignPrimaryManager(
                eq("org-1"), eq("emp-1"), eq("hr-2"), eq("hr-1"), eq(startDate.plusDays(1)));
    }

    @Test
    void changeReportingManagerRejectsAnEffectiveDateNotAfterTheNewManagersStartDate() {
        actor("hr-1", UserRole.HR);
        when(userRepository.findById("emp-1"))
                .thenReturn(Optional.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());
        LocalDate managerStartDate = LocalDate.now().plusDays(7);
        when(employeeProfileRepository.findByUserId("hr-2"))
                .thenReturn(Optional.of(EmployeeProfile.builder().userId("hr-2").startDate(managerStartDate).build()));

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-2");
        request.setEffectiveDate(managerStartDate);

        assertThatThrownBy(() -> service.changeReportingManager("hr-1", "emp-1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("must be after the new reporting manager's start date");
    }

    @Test
    void changeReportingManagerDefaultEffectiveDateAccountsForTheNewManagersFutureStartDate() {
        actor("hr-1", UserRole.HR);
        when(userRepository.findById("emp-1"))
                .thenReturn(Optional.of(user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1"))
                .thenReturn(List.of(active("emp-1", "hr-2")));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());
        LocalDate managerStartDate = LocalDate.now().plusDays(10);
        when(employeeProfileRepository.findByUserId("hr-2"))
                .thenReturn(Optional.of(EmployeeProfile.builder().userId("hr-2").startDate(managerStartDate).build()));

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-2");

        service.changeReportingManager("hr-1", "emp-1", request);

        verify(reportingAssignmentService).reassignPrimaryManager(
                eq("org-1"), eq("emp-1"), eq("hr-2"), eq("hr-1"), eq(managerStartDate.plusDays(1)));
    }

    @Test
    void getDetailThrowsNotFoundWhenEmployeeMissingOrInAnotherOrganization() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDetail("admin-1", "ghost"))
                .isInstanceOf(ResourceNotFoundException.class);

        User otherOrgUser = User.builder().id("emp-9").organizationId("org-2")
                .role(UserRole.EMPLOYEE).accountStatus(AccountStatus.ACTIVE).build();
        when(userRepository.findById("emp-9")).thenReturn(Optional.of(otherOrgUser));

        assertThatThrownBy(() -> service.getDetail("admin-1", "emp-9"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void companyAdminCannotManageAnotherCompanyAdmin() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findById("admin-2"))
                .thenReturn(Optional.of(user("admin-2", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE)));

        assertThatThrownBy(() -> service.getDetail("admin-1", "admin-2"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("access to this resource");
    }

    @Test
    void eligibleReportingManagersReturnsActiveHrAndAdminsExcludingTheEmployeeItself() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findByOrganizationIdAndRoleIn("org-1", List.of(UserRole.HR, UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(
                        user("hr-1", UserRole.HR, AccountStatus.ACTIVE),
                        user("hr-2", UserRole.HR, AccountStatus.INACTIVE),
                        user("admin-1", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE)));

        List<EligibleReportingManagerResponse> result =
                service.eligibleReportingManagers("admin-1", "emp-1");

        assertThat(result).extracting(EligibleReportingManagerResponse::userId)
                .containsExactlyInAnyOrder("hr-1", "admin-1");
    }

    @Test
    void eligibleReportingManagersRestrictsToCompanyAdminWhenTargetIsHr() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findById("hr-9"))
                .thenReturn(Optional.of(user("hr-9", UserRole.HR, AccountStatus.ACTIVE)));
        when(userRepository.findByOrganizationIdAndRoleIn("org-1", List.of(UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(user("admin-1", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE)));

        List<EligibleReportingManagerResponse> result =
                service.eligibleReportingManagers("admin-1", "hr-9");

        assertThat(result).extracting(EligibleReportingManagerResponse::userId)
                .containsExactly("admin-1");
        assertThat(result.get(0).roleLabel()).isEqualTo("Company Admin");
    }

    @Test
    void deactivatingHrWithNoDirectReportsNeedsNoReassignment() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of());
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("hr-1")).thenReturn(List.of());
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.empty());
        EmployeeDetailResponse detail =
                service.updateAccountStatus("admin-1", "hr-1", AccountStatus.INACTIVE, null);

        assertThat(hr.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(detail.accountStatus()).isEqualTo(AccountStatus.INACTIVE);
        verify(reportingAssignmentService, never()).reassignPrimaryManager(any(), any(), any(), any(), any());
    }

    @Test
    void deactivatingHrWithActiveDirectReportsRequiresAReassignmentTarget() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(active("emp-1", "hr-1")));

        assertThatThrownBy(() ->
                service.updateAccountStatus("admin-1", "hr-1", AccountStatus.INACTIVE, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Reassign this HR's employees");

        assertThat(hr.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository, never()).save(hr);
    }

    @Test
    void deactivatingHrReassignsDirectReportsBeforeChangingStatus() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(active("emp-1", "hr-1"), active("emp-2", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("hr-1")).thenReturn(List.of());
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.empty());
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());
        when(employeeProfileRepository.findByUserId("emp-2")).thenReturn(Optional.empty());
        when(employeeProfileRepository.findByUserId("admin-1")).thenReturn(Optional.empty());
        when(userRepository.findByIdIn(List.of("emp-1", "emp-2"))).thenReturn(List.of(
                user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE),
                user("emp-2", UserRole.EMPLOYEE, AccountStatus.ACTIVE)));

        EmployeeDetailResponse detail =
                service.updateAccountStatus("admin-1", "hr-1", AccountStatus.INACTIVE, "admin-1");

        verify(reportingAssignmentService).reassignPrimaryManager(
                eq("org-1"), eq("emp-1"), eq("admin-1"), eq("admin-1"), any(LocalDate.class));
        verify(reportingAssignmentService).reassignPrimaryManager(
                eq("org-1"), eq("emp-2"), eq("admin-1"), eq("admin-1"), any(LocalDate.class));
        verify(auditService, times(2)).record(any(), eq(AuditActionType.REPORTING_MANAGER_CHANGED),
                eq("USER"), anyString(), anyString(), anyString(), any(), any());
        verify(auditService).record(any(), eq(AuditActionType.ACCOUNT_STATUS_CHANGED),
                eq("USER"), eq("hr-1"), anyString(), anyString(), any(), any());
        assertThat(hr.getAccountStatus()).isEqualTo(AccountStatus.INACTIVE);
        assertThat(detail.accountStatus()).isEqualTo(AccountStatus.INACTIVE);
    }

    @Test
    void reassignmentFailurePreventsTheHrFromBeingDeactivated() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(active("emp-1", "hr-1")));
        when(reportingAssignmentService.reassignPrimaryManager(
                eq("org-1"), eq("emp-1"), eq("not-eligible"), eq("admin-1"), any(LocalDate.class)))
                .thenThrow(new BusinessException("Reporting Manager must be an active HR or Company Admin user"));

        assertThatThrownBy(() -> service.updateAccountStatus(
                "admin-1", "hr-1", AccountStatus.INACTIVE, "not-eligible"))
                .isInstanceOf(BusinessException.class);

        assertThat(hr.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository, never()).save(hr);
    }

    @Test
    void deactivatingHrRejectsReassigningToAManagerWhoHasNotStartedYet() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User hr = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(hr));
        when(reportingAssignmentRepository
                .findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
                        "org-1", "hr-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(List.of(active("emp-1", "hr-1")));
        LocalDate futureStart = LocalDate.now().plusDays(4);
        when(employeeProfileRepository.findByUserId("hr-3"))
                .thenReturn(Optional.of(EmployeeProfile.builder().userId("hr-3").startDate(futureStart).build()));

        assertThatThrownBy(() -> service.updateAccountStatus("admin-1", "hr-1", AccountStatus.INACTIVE, "hr-3"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("has not started yet");

        assertThat(hr.getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(userRepository, never()).save(hr);
        verify(reportingAssignmentService, never()).reassignPrimaryManager(any(), any(), any(), any(), any());
    }

    @Test
    void eligibleReportingManagersExcludesTheEmployeeWhenTheyAreThemselvesAManager() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findByOrganizationIdAndRoleIn("org-1", List.of(UserRole.HR, UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(user("hr-1", UserRole.HR, AccountStatus.ACTIVE)));

        List<EligibleReportingManagerResponse> result =
                service.eligibleReportingManagers("admin-1", "hr-1");

        assertThat(result).isEmpty();
    }

    @Test
    void eligibleReportingManagersExcludesManagersWhoHaveNotStartedYet() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findByOrganizationIdAndRoleIn("org-1", List.of(UserRole.HR, UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(
                        user("hr-1", UserRole.HR, AccountStatus.ACTIVE),
                        user("hr-2", UserRole.HR, AccountStatus.ACTIVE)));
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of(
                EmployeeProfile.builder().userId("hr-2").startDate(LocalDate.now().plusDays(2)).build()));

        List<EligibleReportingManagerResponse> result =
                service.eligibleReportingManagers("admin-1", "emp-1");

        assertThat(result).extracting(EligibleReportingManagerResponse::userId).containsExactly("hr-1");
    }

    @Test
    void eligibleReportingManagersIncludesManagersWithNoProfileOrAStartDateNotInTheFuture() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findByOrganizationIdAndRoleIn("org-1", List.of(UserRole.HR, UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(
                        user("hr-1", UserRole.HR, AccountStatus.ACTIVE),
                        user("admin-2", UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE)));
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of(
                EmployeeProfile.builder().userId("hr-1").startDate(LocalDate.now()).build()));

        List<EligibleReportingManagerResponse> result =
                service.eligibleReportingManagers("admin-1", "emp-1");

        assertThat(result).extracting(EligibleReportingManagerResponse::userId)
                .containsExactlyInAnyOrder("hr-1", "admin-2");
    }

    @Test
    void eligibleReportingManagersIncludesTheManagersStartDateInTheResponse() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        when(userRepository.findByOrganizationIdAndRoleIn("org-1", List.of(UserRole.HR, UserRole.COMPANY_ADMIN)))
                .thenReturn(List.of(user("hr-1", UserRole.HR, AccountStatus.ACTIVE)));
        LocalDate startDate = LocalDate.now().minusDays(30);
        when(employeeProfileRepository.findByUserIdIn(any())).thenReturn(List.of(
                EmployeeProfile.builder().userId("hr-1").startDate(startDate).build()));

        List<EligibleReportingManagerResponse> result =
                service.eligibleReportingManagers("admin-1", "emp-1");

        assertThat(result.get(0).startDate()).isEqualTo(startDate);
    }

    @Test
    void changeReportingManagerFirstAssignmentNotifiesTheNewManagerAndTheEmployee() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User employee = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        User newManager = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(employee));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER)).thenReturn(Optional.empty());
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1")).thenReturn(List.of());
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());
        when(employeeProfileRepository.findByUserId("hr-1")).thenReturn(Optional.empty());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(employee, newManager));

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-1");
        service.changeReportingManager("admin-1", "emp-1", request);

        verify(inAppNotificationService).createNotification(eq("org-1"), eq("hr-1"),
                eq(NotificationType.EMPLOYEE_ASSIGNED), eq("New employee assigned"),
                contains("emp-1"), eq("emp-1"), contains("/employees"));
        verify(inAppNotificationService).createNotification(eq("org-1"), eq("emp-1"),
                eq(NotificationType.REPORTING_MANAGER_UPDATED), eq("Reporting manager updated"),
                contains("set to"), eq("emp-1"), contains("/profile"));
        verify(inAppNotificationService, never()).createNotification(
                any(), any(), eq(NotificationType.EMPLOYEE_REASSIGNED), any(), any(), any(), any());
    }

    @Test
    void changeReportingManagerReassignmentNotifiesNewManagerEmployeeAndOldManager() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User employee = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        User oldManager = user("hr-1", UserRole.HR, AccountStatus.ACTIVE);
        User newManager = user("hr-2", UserRole.HR, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(employee));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1"))
                .thenReturn(List.of(active("emp-1", "hr-2")));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());
        when(userRepository.findByIdIn(any())).thenReturn(List.of(employee, oldManager, newManager));

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-2");
        service.changeReportingManager("admin-1", "emp-1", request);

        verify(inAppNotificationService).createNotification(eq("org-1"), eq("hr-2"),
                eq(NotificationType.EMPLOYEE_ASSIGNED), eq("Employee assigned to you"), any(), eq("emp-1"), any());
        verify(inAppNotificationService).createNotification(eq("org-1"), eq("emp-1"),
                eq(NotificationType.REPORTING_MANAGER_UPDATED), eq("Reporting manager updated"),
                contains("changed to"), eq("emp-1"), any());
        verify(inAppNotificationService).createNotification(eq("org-1"), eq("hr-1"),
                eq(NotificationType.EMPLOYEE_REASSIGNED), eq("Employee reassigned"), any(), eq("emp-1"), any());
    }

    @Test
    void changeReportingManagerToTheSameManagerSendsNoReassignmentNotification() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User employee = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(employee));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1")).thenReturn(List.of());
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());

        ChangeReportingManagerRequest request = new ChangeReportingManagerRequest();
        request.setNewReportingManagerUserId("hr-1");
        service.changeReportingManager("admin-1", "emp-1", request);

        verifyNoInteractions(inAppNotificationService);
    }

    @Test
    void updateAccountStatusNotifiesTheAffectedEmployee() {
        actor("hr-1", UserRole.HR);
        User target = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(target));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        when(reportingAssignmentRepository
                .findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc("emp-1"))
                .thenReturn(List.of(active("emp-1", "hr-1")));
        when(employeeProfileRepository.findByUserId("emp-1")).thenReturn(Optional.empty());

        service.updateAccountStatus("hr-1", "emp-1", AccountStatus.INACTIVE, null);

        verify(inAppNotificationService).createNotification(eq("org-1"), eq("emp-1"),
                eq(NotificationType.ACCOUNT_STATUS_UPDATED), eq("Account status updated"),
                contains("deactivated"), eq("emp-1"), contains("/profile"));
    }

    @Test
    void updateAccountStatusToTheSameStatusSendsNoNotification() {
        actor("admin-1", UserRole.COMPANY_ADMIN);
        User target = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(target));

        service.updateAccountStatus("admin-1", "emp-1", AccountStatus.ACTIVE, null);

        verifyNoInteractions(inAppNotificationService);
    }

    @Test
    void aNotificationFailureNeverBreaksAnOtherwiseSuccessfulAccountStatusChange() {
        // Regression test: notification creation is secondary to the business
        // action - a transient failure there must never surface as a failure of
        // the account-status change itself.
        actor("hr-1", UserRole.HR);
        User target = user("emp-1", UserRole.EMPLOYEE, AccountStatus.ACTIVE);
        when(userRepository.findById("emp-1")).thenReturn(Optional.of(target));
        when(reportingAssignmentRepository.findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                "emp-1", RelationshipType.PRIMARY_REPORTING_MANAGER))
                .thenReturn(Optional.of(active("emp-1", "hr-1")));
        doThrow(new IllegalArgumentException("boom")).when(inAppNotificationService)
                .createNotification(any(), any(), any(), any(), any(), any(), any());

        EmployeeDetailResponse detail = service.updateAccountStatus("hr-1", "emp-1", AccountStatus.INACTIVE, null);

        assertThat(detail.accountStatus()).isEqualTo(AccountStatus.INACTIVE);
    }
}
