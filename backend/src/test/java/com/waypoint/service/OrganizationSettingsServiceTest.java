package com.waypoint.service;

import com.waypoint.dto.myhr.OrganizationSettingsResponse;
import com.waypoint.dto.myhr.UpdateOrganizationSettingsRequest;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationSettings;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.PayrollPeriod;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.OrganizationSettingsRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrganizationSettingsServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock OrganizationSettingsRepository organizationSettingsRepository;
    @Mock MyHrAuthenticatedUserService authenticatedUserService;
    @Mock FileStorageService fileStorageService;
    @Mock AuditService auditService;

    private static final Set<UserRole> MANAGE_ROLES = Set.of(UserRole.COMPANY_ADMIN);

    private OrganizationSettingsService service() {
        return new OrganizationSettingsService(
                organizationRepository, organizationSettingsRepository, authenticatedUserService, fileStorageService, auditService);
    }

    private MyHrAuthenticatedUserService.AuthenticatedMyHrUser adminActor(String organizationId) {
        return new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(
                "admin-1", "TDADMIN", "admin@tardus.test", UserRole.COMPANY_ADMIN,
                organizationId, AccountStatus.ACTIVE, false);
    }

    /** A fully provisioned organization so every protected field has a distinct, checkable value. */
    private Organization existingOrganization(String organizationId) {
        return Organization.builder()
                .id(organizationId)
                .organizationCode("TD")
                .organizationName("Tardus Inc")
                .organizationEmail("myhr.dev.tardus@waypoint.local")
                .phoneNumber("+15550000000")
                .state("IL")
                .city("Chicago")
                .streetAddress("1 Old Street")
                .zipCode("60601")
                .organizationSize(CompanySize.MEDIUM)
                .organizationLogoDocumentId("logo-doc-1")
                .description("Old description")
                .industry("Old industry")
                .website("https://old.example.com")
                .nextEmployeeSequence(42L)
                .createdAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .updatedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
    }

    private UpdateOrganizationSettingsRequest validUpdate() {
        return new UpdateOrganizationSettingsRequest(
                "Tardus International", "myhr.dev.tardus@waypoint.local", "+15559998888", "200 New Street",
                "Austin", "TX", "78701", CompanySize.LARGE, "Software", "https://new.example.com",
                "843596475", "1533740", "Jane Admin", "jane.admin@tardus.example.com", "+19726936993", "We build things.", PayrollPeriod.MONTHLY, false, false);
    }

    @Test
    void companyAdminCanRetrieveSettingsMergedFromOrganizationAndSettingsDocuments() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1"))
                .thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1")
                        .payrollPeriod(PayrollPeriod.BIWEEKLY).build()));

        OrganizationSettingsResponse response = service().getForActor("admin-1");

        assertThat(response.organizationId()).isEqualTo("organization-1");
        assertThat(response.organizationCode()).isEqualTo("TD");
        assertThat(response.organizationName()).isEqualTo("Tardus Inc");
        assertThat(response.organizationSize()).isEqualTo(CompanySize.MEDIUM);
        assertThat(response.payrollPeriod()).isEqualTo(PayrollPeriod.BIWEEKLY);
        assertThat(response.organizationLogoUrl()).isEqualTo("/uploads/logo-doc-1");
        verify(organizationRepository, never()).save(any());
        verify(organizationSettingsRepository, never()).save(any());
    }

    @Test
    void getReturnsNullLogoUrlWhenNoLogoHasBeenUploaded() {
        Organization organization = existingOrganization("organization-1");
        organization.setOrganizationLogoDocumentId(null);
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1")).thenReturn(Optional.of(organization));
        when(organizationSettingsRepository.findByOrganizationId("organization-1")).thenReturn(Optional.empty());

        OrganizationSettingsResponse response = service().getForActor("admin-1");

        assertThat(response.organizationLogoUrl()).isNull();
    }

    @Test
    void uploadLogoStoresTheFileUnderCompanyLogosAndReturnsTheUpdatedUrl() {
        Organization organization = existingOrganization("organization-1");
        org.springframework.web.multipart.MultipartFile file =
                new org.springframework.mock.web.MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1")).thenReturn(Optional.of(organization));
        when(organizationSettingsRepository.findByOrganizationId("organization-1")).thenReturn(Optional.empty());
        when(fileStorageService.store(file, "company-logos", "organization-1")).thenReturn("company-logos/organization-1/logo.png");
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));

        OrganizationSettingsResponse response = service().uploadLogo("admin-1", file);

        assertThat(response.organizationLogoUrl()).isEqualTo("/uploads/company-logos/organization-1/logo.png");
        ArgumentCaptor<Organization> savedOrg = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(savedOrg.capture());
        assertThat(savedOrg.getValue().getOrganizationLogoDocumentId()).isEqualTo("company-logos/organization-1/logo.png");
    }

    @Test
    void updateRemovesTheStoredLogoWhenRemoveLogoIsRequested() {
        Organization organization = existingOrganization("organization-1");
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1")).thenReturn(Optional.of(organization));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1").build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));
        UpdateOrganizationSettingsRequest request = new UpdateOrganizationSettingsRequest(
                "Tardus International", "myhr.dev.tardus@waypoint.local", "+15559998888", "200 New Street",
                "Austin", "TX", "78701", CompanySize.LARGE, "Software", "https://new.example.com",
                "84-3596475", "1533740", "Jane Admin", "jane.admin@tardus.example.com", "+19726936993", "We build things.", PayrollPeriod.MONTHLY, true, false);

        OrganizationSettingsResponse response = service().updateForActor("admin-1", request);

        verify(fileStorageService).remove("company-logos", "organization-1");
        assertThat(response.organizationLogoUrl()).isNull();
        ArgumentCaptor<Organization> savedOrg = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(savedOrg.capture());
        assertThat(savedOrg.getValue().getOrganizationLogoDocumentId()).isNull();
    }

    @Test
    void updateRemovesTheStoredSignatureWhenRemoveSignatureIsRequested() {
        Organization organization = existingOrganization("organization-1");
        organization.setOrganizationSignatureDocumentId("signature-doc-1");
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1")).thenReturn(Optional.of(organization));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1").build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));
        UpdateOrganizationSettingsRequest request = new UpdateOrganizationSettingsRequest(
                "Tardus International", "myhr.dev.tardus@waypoint.local", "+15559998888", "200 New Street",
                "Austin", "TX", "78701", CompanySize.LARGE, "Software", "https://new.example.com",
                "84-3596475", "1533740", "Jane Admin", "jane.admin@tardus.example.com", "+19726936993", "We build things.", PayrollPeriod.MONTHLY, false, true);

        OrganizationSettingsResponse response = service().updateForActor("admin-1", request);

        verify(fileStorageService).remove("company-signatures", "organization-1");
        assertThat(response.organizationSignatureUrl()).isNull();
        ArgumentCaptor<Organization> savedOrg = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(savedOrg.capture());
        assertThat(savedOrg.getValue().getOrganizationSignatureDocumentId()).isNull();
    }

    @Test
    void getReturnsNullPayrollPeriodWhenNoSettingsDocumentExistsAndDoesNotCreateOne() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1"))
                .thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.empty());

        OrganizationSettingsResponse response = service().getForActor("admin-1");

        assertThat(response.payrollPeriod()).isNull();
        verify(organizationSettingsRepository, never()).save(any());
    }

    @Test
    void companyAdminCanUpdateOrganizationInformationFields() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1"))
                .thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1").build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));

        OrganizationSettingsResponse response = service().updateForActor("admin-1", validUpdate());

        ArgumentCaptor<Organization> savedOrg = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(savedOrg.capture());
        Organization org = savedOrg.getValue();
        assertThat(org.getOrganizationName()).isEqualTo("Tardus International");
        assertThat(org.getOrganizationEmail()).isEqualTo("myhr.dev.tardus@waypoint.local");
        assertThat(org.getPhoneNumber()).isEqualTo("+15559998888");
        assertThat(org.getStreetAddress()).isEqualTo("200 New Street");
        assertThat(org.getCity()).isEqualTo("Austin");
        assertThat(org.getState()).isEqualTo("TX");
        assertThat(org.getZipCode()).isEqualTo("78701");
        assertThat(org.getOrganizationSize()).isEqualTo(CompanySize.LARGE);
        assertThat(org.getIndustry()).isEqualTo("Software");
        assertThat(org.getWebsite()).isEqualTo("https://new.example.com");
        assertThat(org.getEinNumber()).isEqualTo("84-3596475");
        assertThat(org.getEVerifyNumber()).isEqualTo("1533740");
        assertThat(org.getDescription()).isEqualTo("We build things.");
        assertThat(response.organizationName()).isEqualTo("Tardus International");
        assertThat(response.organizationSize()).isEqualTo(CompanySize.LARGE);
        assertThat(response.einNumber()).isEqualTo("84-3596475");
        assertThat(response.eVerifyNumber()).isEqualTo("1533740");
    }

    @Test
    void updateRejectsAnOrganizationEmailAlreadyUsedByAnotherOrganization() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1"))
                .thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1").build()));
        UpdateOrganizationSettingsRequest request = new UpdateOrganizationSettingsRequest(
                "Tardus International", "taken@example.com", "+15559998888", "200 New Street",
                "Austin", "TX", "78701", CompanySize.LARGE, "Software", "https://new.example.com",
                "84-3596475", "1533740", "Jane Admin", "jane.admin@tardus.example.com", "+19726936993", "We build things.", PayrollPeriod.MONTHLY, false, false);
        when(organizationRepository.findByOrganizationEmail("taken@example.com"))
                .thenReturn(Optional.of(existingOrganization("organization-2")));

        assertThatThrownBy(() -> service().updateForActor("admin-1", request))
                .isInstanceOf(BusinessException.class);
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void companyAdminCanUpdatePayrollPeriodOnTheExistingSettingsDocument() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1"))
                .thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1")
                        .payrollPeriod(PayrollPeriod.WEEKLY).build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));

        OrganizationSettingsResponse response = service().updateForActor("admin-1", validUpdate());

        ArgumentCaptor<OrganizationSettings> savedSettings = ArgumentCaptor.forClass(OrganizationSettings.class);
        verify(organizationSettingsRepository).save(savedSettings.capture());
        OrganizationSettings settings = savedSettings.getValue();
        assertThat(settings.getId()).isEqualTo("settings-1");
        assertThat(settings.getOrganizationId()).isEqualTo("organization-1");
        assertThat(settings.getPayrollPeriod()).isEqualTo(PayrollPeriod.MONTHLY);
        assertThat(settings.getUpdatedByUserId()).isEqualTo("admin-1");
        assertThat(response.payrollPeriod()).isEqualTo(PayrollPeriod.MONTHLY);
    }

    @Test
    void updateLazilyCreatesASingleSettingsDocumentWhenNoneExists() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1"))
                .thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.empty());
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));

        service().updateForActor("admin-1", validUpdate());

        ArgumentCaptor<OrganizationSettings> savedSettings = ArgumentCaptor.forClass(OrganizationSettings.class);
        verify(organizationSettingsRepository).save(savedSettings.capture());
        OrganizationSettings created = savedSettings.getValue();
        assertThat(created.getId()).isNull();
        assertThat(created.getOrganizationId()).isEqualTo("organization-1");
        assertThat(created.getPayrollPeriod()).isEqualTo(PayrollPeriod.MONTHLY);
        assertThat(created.getUpdatedByUserId()).isEqualTo("admin-1");
        assertThat(created.getCreatedAt()).isNotNull();
    }

    @Test
    void updateReusesTheExistingSettingsDocumentRatherThanDuplicatingIt() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1"))
                .thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1")
                        .payrollPeriod(PayrollPeriod.WEEKLY)
                        .createdAt(LocalDateTime.of(2026, 2, 2, 2, 2)).build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));

        service().updateForActor("admin-1", validUpdate());

        ArgumentCaptor<OrganizationSettings> savedSettings = ArgumentCaptor.forClass(OrganizationSettings.class);
        verify(organizationSettingsRepository).save(savedSettings.capture());
        OrganizationSettings settings = savedSettings.getValue();
        assertThat(settings.getId()).isEqualTo("settings-1");
        assertThat(settings.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 2, 2, 2, 2));
    }

    @Test
    void updateRecordsOneOrganizationScopedAuditEventNamingChangedFieldsAndMaskingIdentifiers() {
        givenUpdatableOrganization(PayrollPeriod.MONTHLY);

        service().updateForActor("admin-1", validUpdate());

        ArgumentCaptor<Object> previous = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> updated = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(org.mockito.ArgumentMatchers.eq(adminActor("organization-1")),
                org.mockito.ArgumentMatchers.eq(com.waypoint.enums.AuditActionType.ORGANIZATION_SETTINGS_UPDATED),
                org.mockito.ArgumentMatchers.eq("ORGANIZATION"), org.mockito.ArgumentMatchers.eq("organization-1"),
                org.mockito.ArgumentMatchers.eq("Tardus International"), description.capture(), previous.capture(), updated.capture());
        assertThat(description.getValue()).contains("Organization Name", "Admin Email", "EIN Number");
        @SuppressWarnings("unchecked") java.util.Map<String, String> after = (java.util.Map<String, String>) updated.getValue();
        assertThat(after).containsEntry("Organization Name", "Tardus International")
                .containsEntry("Admin Email", "jane.admin@tardus.example.com")
                .containsEntry("EIN Number", "Changed").containsEntry("E-Verify Number", "Changed");
        assertThat(after.values()).doesNotContain("84-3596475", "1533740");
        // The payroll period did not change (MONTHLY -> MONTHLY), so no payroll event is written.
        verify(auditService, never()).record(any(), org.mockito.ArgumentMatchers.eq(com.waypoint.enums.AuditActionType.PAYROLL_PERIOD_CHANGED),
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateRecordsAPayrollPeriodChangeOnlyWhenThePeriodChanges() {
        givenUpdatableOrganization(PayrollPeriod.WEEKLY);

        service().updateForActor("admin-1", validUpdate());

        verify(auditService).record(any(), org.mockito.ArgumentMatchers.eq(com.waypoint.enums.AuditActionType.PAYROLL_PERIOD_CHANGED),
                org.mockito.ArgumentMatchers.eq("ORGANIZATION"), org.mockito.ArgumentMatchers.eq("organization-1"), any(),
                org.mockito.ArgumentMatchers.contains("MONTHLY"), any(), any());
    }

    @Test
    void updateWithNothingChangedWritesNoAuditEvent() {
        givenUpdatableOrganization(PayrollPeriod.MONTHLY);
        UpdateOrganizationSettingsRequest unchanged = new UpdateOrganizationSettingsRequest(
                "Tardus Inc", "myhr.dev.tardus@waypoint.local", "+15550000000", "1 Old Street", "Chicago", "IL", "60601",
                CompanySize.MEDIUM, "Old industry", "https://old.example.com", null, null, null, null, null,
                "Old description", PayrollPeriod.MONTHLY, false, false);

        service().updateForActor("admin-1", unchanged);

        verify(auditService, never()).record(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private void givenUpdatableOrganization(PayrollPeriod currentPeriod) {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false)).thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1")).thenReturn(Optional.of(existingOrganization("organization-1")));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder().id("settings-1").organizationId("organization-1")
                        .payrollPeriod(currentPeriod).createdAt(LocalDateTime.of(2026, 2, 2, 2, 2)).build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));
    }

    @Test
    void hrCannotAccessOrganizationSettings() {
        when(authenticatedUserService.require("hr-1", MANAGE_ROLES, false))
                .thenThrow(new ForbiddenException("You do not have access to this resource"));

        assertThatThrownBy(() -> service().getForActor("hr-1"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service().updateForActor("hr-1", validUpdate()))
                .isInstanceOf(ForbiddenException.class);
        verify(organizationRepository, never()).findById(any());
        verify(organizationRepository, never()).save(any());
        verify(organizationSettingsRepository, never()).save(any());
    }

    @Test
    void employeeCannotAccessOrganizationSettings() {
        when(authenticatedUserService.require("employee-1", MANAGE_ROLES, false))
                .thenThrow(new ForbiddenException("You do not have access to this resource"));

        assertThatThrownBy(() -> service().getForActor("employee-1"))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> service().updateForActor("employee-1", validUpdate()))
                .isInstanceOf(ForbiddenException.class);
        verify(organizationRepository, never()).save(any());
        verify(organizationSettingsRepository, never()).save(any());
    }

    @Test
    void organizationScopeAlwaysComesFromTheAuthenticatedUserNotTheCaller() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-A"));
        when(organizationRepository.findById("organization-A"))
                .thenReturn(Optional.of(existingOrganization("organization-A")));
        when(organizationSettingsRepository.findByOrganizationId("organization-A"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-A").organizationId("organization-A").build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));

        service().updateForActor("admin-1", validUpdate());

        verify(organizationRepository).findById("organization-A");
        verify(organizationSettingsRepository).findByOrganizationId("organization-A");
        ArgumentCaptor<OrganizationSettings> savedSettings = ArgumentCaptor.forClass(OrganizationSettings.class);
        verify(organizationSettingsRepository).save(savedSettings.capture());
        assertThat(savedSettings.getValue().getOrganizationId()).isEqualTo("organization-A");
    }

    @Test
    void aCompanyAdminCannotReadOrWriteAnotherOrganizationsSettings() {
        // The admin belongs to organization-A; no request field can point at
        // organization-B, and the service only ever touches the actor own org.
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-A"));
        when(organizationRepository.findById("organization-A"))
                .thenReturn(Optional.of(existingOrganization("organization-A")));
        when(organizationSettingsRepository.findByOrganizationId("organization-A"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-A").organizationId("organization-A").build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));

        service().updateForActor("admin-1", validUpdate());

        verify(organizationRepository, never()).findById("organization-B");
        verify(organizationSettingsRepository, never()).findByOrganizationId("organization-B");
    }

    @Test
    void updateRejectsWhenTheActorsOrganizationDoesNotExist() {
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-gone"));
        when(organizationRepository.findById("organization-gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().updateForActor("admin-1", validUpdate()))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(organizationRepository, never()).save(any());
        verify(organizationSettingsRepository, never()).save(any());
    }

    @Test
    void updateNeverModifiesProtectedOrganizationFields() {
        Organization original = existingOrganization("organization-1");
        when(authenticatedUserService.require("admin-1", MANAGE_ROLES, false))
                .thenReturn(adminActor("organization-1"));
        when(organizationRepository.findById("organization-1")).thenReturn(Optional.of(original));
        when(organizationSettingsRepository.findByOrganizationId("organization-1"))
                .thenReturn(Optional.of(OrganizationSettings.builder()
                        .id("settings-1").organizationId("organization-1").build()));
        when(organizationRepository.save(any(Organization.class))).thenAnswer(i -> i.getArgument(0));
        when(organizationSettingsRepository.save(any(OrganizationSettings.class))).thenAnswer(i -> i.getArgument(0));

        service().updateForActor("admin-1", validUpdate());

        ArgumentCaptor<Organization> savedOrg = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(savedOrg.capture());
        Organization org = savedOrg.getValue();
        assertThat(org.getId()).isEqualTo("organization-1");
        assertThat(org.getOrganizationCode()).isEqualTo("TD");
        assertThat(org.getNextEmployeeSequence()).isEqualTo(42L);
        assertThat(org.getOrganizationLogoDocumentId()).isEqualTo("logo-doc-1");
        assertThat(org.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 1, 1, 9, 0));
        ArgumentCaptor<OrganizationSettings> savedSettings = ArgumentCaptor.forClass(OrganizationSettings.class);
        verify(organizationSettingsRepository).save(savedSettings.capture());
        assertThat(savedSettings.getValue().getPayrollTaxProvider()).isNull();
    }
}
