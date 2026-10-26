package com.waypoint.service;

import com.waypoint.config.DatabaseInitializer;
import com.waypoint.dto.myhr.CompanyAdminProvisioningRequest;
import com.waypoint.dto.myhr.CreateEmployeeAccountRequest;
import com.waypoint.dto.myhr.CreateHrAccountRequest;
import com.waypoint.dto.myhr.CreatedAccountCredential;
import com.waypoint.dto.myhr.CreatedAccountResult;
import com.waypoint.dto.myhr.EmployeeProfileFields;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.ReportingAssignmentRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(properties = {
        "JWT_SECRET=test-only-waypoint-jwt-secret-that-is-long-enough",
        "MAIL_ENABLED=false"
})
@EnabledIfEnvironmentVariable(named = "MYHR_DEV_ACCOUNTS_PROVISIONING_ENABLED", matches = "(?i:true)")
@EnabledIfEnvironmentVariable(named = "MONGODB_URI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MONGODB_DATABASE", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MYHR_DEV_COMPANY_ADMIN_EMAIL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MYHR_DEV_HR_EMAIL", matches = ".+")
@EnabledIfEnvironmentVariable(named = "MYHR_DEV_EMPLOYEE_EMAIL", matches = ".+")
class TardusMyHrDevelopmentAccountsProvisioningTest {

    @MockitoBean private DatabaseInitializer databaseInitializer;
    @MockitoBean private ResumeStorageMigrationService resumeStorageMigrationService;
    @MockitoBean private EventLocationMigrationService eventLocationMigrationService;
    @MockitoBean private JobLocationMigrationService jobLocationMigrationService;
    @MockitoBean private FileStorageMigrationService fileStorageMigrationService;
    @MockitoBean private MyHrAuthenticatedUserService authenticatedUserService;

    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private EmployeeProfileRepository employeeProfileRepository;
    @Autowired private ReportingAssignmentRepository reportingAssignmentRepository;
    @Autowired private CompanyAdminProvisioningService adminProvisioningService;
    @Autowired private MyHrAccountService accountService;
    @Autowired private MongoTemplate mongoTemplate;

    @Test
    void provisionsExactlyOneAdminHrAndEmployeeUsingRealAtlasRepositoriesAndAccountServices() {
        assertThat(mongoTemplate.getDb().getName()).isEqualTo(System.getenv("MONGODB_DATABASE"));
        Organization organization = organizationRepository.findByOrganizationCode("TD").orElseThrow();

        CreatedAccountCredential admin = adminProvisioningService.provision(
                new CompanyAdminProvisioningRequest(
                        organization.getId(), System.getenv("MYHR_DEV_COMPANY_ADMIN_EMAIL")));
        assertThat(admin.loginId()).isEqualTo("TDADMIN");

        when(authenticatedUserService.require(any(), any(), eq(false)))
                .thenReturn(new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                        admin.userId(), "TDADMIN", admin.email(), UserRole.COMPANY_ADMIN,
                        organization.getId(), AccountStatus.ACTIVE, false));

        CreatedAccountResult hr = existingCredential("TD001");
        if (hr == null) {
            CreateHrAccountRequest request = new CreateHrAccountRequest();
            request.setProfile(profile(
                    "Tardus Development HR", System.getenv("MYHR_DEV_HR_EMAIL"), "HR"));
            hr = accountService.createHr(admin.userId(), request);
        }
        assertThat(hr.loginId()).isEqualTo("TD001");
        assertThat(employeeProfileRepository.findByUserId(hr.userId())).isPresent();

        CreatedAccountResult employee = existingCredential("TD002");
        if (employee == null) {
            CreateEmployeeAccountRequest request = new CreateEmployeeAccountRequest();
            request.setProfile(profile(
                    "Tardus Development Employee", System.getenv("MYHR_DEV_EMPLOYEE_EMAIL"), "Employee"));
            request.setReportingManagerUserId(hr.userId());
            employee = accountService.createEmployee(admin.userId(), request);
        }
        assertThat(employee.loginId()).isEqualTo("TD002");
        assertThat(employeeProfileRepository.findByUserId(employee.userId())).isPresent();
        String hrUserId = hr.userId();
        assertThat(reportingAssignmentRepository
                .findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
                        employee.userId(), com.waypoint.enums.RelationshipType.PRIMARY_REPORTING_MANAGER))
                .hasValueSatisfying(assignment ->
                        assertThat(assignment.getReportingManagerUserId()).isEqualTo(hrUserId));

        Organization updatedOrganization = organizationRepository.findById(organization.getId()).orElseThrow();
        assertThat(updatedOrganization.getNextEmployeeSequence()).isEqualTo(3L);

        printCredential(admin);
        printAccount(hr);
        printAccount(employee);
        System.out.println("MYHR_DEV_ORGANIZATION_ID=" + organization.getId());
        System.out.println("MYHR_DEV_NEXT_EMPLOYEE_SEQUENCE=" + updatedOrganization.getNextEmployeeSequence());
    }

    private CreatedAccountResult existingCredential(String loginId) {
        User existing = userRepository.findByLoginId(loginId).orElse(null);
        if (existing == null) {
            return null;
        }
        return new CreatedAccountResult(
                existing.getId(), employeeProfileRepository.findByUserId(existing.getId())
                .map(profile -> profile.getId()).orElse(null), existing.getLoginId(),
                employeeProfileRepository.findByUserId(existing.getId())
                .map(profile -> profile.getEmployeeId()).orElse(null), existing.getEmail(),
                existing.getRole(), existing.getOrganizationId(),
                Boolean.TRUE.equals(existing.getMustChangePassword()), false);
    }

    private EmployeeProfileFields profile(String name, String email, String designation) {
        EmployeeProfileFields fields = new EmployeeProfileFields();
        int separator = name.lastIndexOf(' ');
        fields.setFirstName(separator < 0 ? name : name.substring(0, separator));
        fields.setLastName(separator < 0 ? "User" : name.substring(separator + 1));
        fields.setPersonalEmail(email);
        fields.setDesignation(designation);
        return fields;
    }

    /** HR/Employee passwords are emailed to the new user and never returned or printed. */
    private void printAccount(CreatedAccountResult account) {
        System.out.println("MYHR_DEV_LOGIN_ID=" + account.loginId());
        System.out.println("MYHR_DEV_CREDENTIAL_EMAIL_DELIVERED=" + account.emailDelivered());
    }

    private void printCredential(CreatedAccountCredential credential) {
        System.out.println("MYHR_DEV_LOGIN_ID=" + credential.loginId());
        if (credential.temporaryPassword() == null) {
            System.out.println("MYHR_DEV_CREDENTIAL_STATUS=ALREADY_EXISTS_PASSWORD_NOT_RESET");
        } else {
            System.out.println("MYHR_DEV_TEMPORARY_PASSWORD=" + credential.temporaryPassword());
        }
    }
}
