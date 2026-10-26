package com.waypoint.service;

import com.waypoint.entity.Organization;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.OrganizationStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.WaypointAdminAuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaypointAdminOrganizationServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock WaypointAdminAuthenticatedUserService authenticatedUserService;
    private WaypointAdminOrganizationService service;

    @BeforeEach
    void setUp() {
        service = new WaypointAdminOrganizationService(organizationRepository, authenticatedUserService);
    }

    @Test
    void returnsAStablePageOfApprovedBusinessFields() {
        Organization organization = organization();
        when(organizationRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(organization)));

        var result = service.list("platform-1", 0, 10, null);

        verify(authenticatedUserService).require("platform-1");
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().organizationName()).isEqualTo("Acme Health");
        assertThat(result.items().getFirst().status()).isEqualTo(OrganizationStatus.ACTIVE);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(organizationRepository).findAll(pageable.capture());
        assertThat(pageable.getValue().getSort().getOrderFor("organizationName")).isNotNull();
    }

    @Test
    void trimsSearchAndAppliesItToApprovedDirectoryFields() {
        when(organizationRepository
                .findByOrganizationNameContainingIgnoreCaseOrOrganizationCodeContainingIgnoreCaseOrOrganizationEmailContainingIgnoreCase(
                        eq("Acme"), eq("Acme"), eq("Acme"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(organization())));

        var result = service.list("platform-1", 0, 20, "  Acme  ");

        assertThat(result.items()).hasSize(1);
        assertThat(result.size()).isEqualTo(1);
    }

    @Test
    void returnsOrganizationDetailsWithoutInternalStorageFields() {
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(organization()));

        var result = service.get("platform-1", "org-1");

        verify(authenticatedUserService).require("platform-1");
        assertThat(result.organizationCode()).isEqualTo("ACME");
        assertThat(result.status()).isEqualTo(OrganizationStatus.ACTIVE);
        assertThat(result.website()).isEqualTo("https://acme.example");
        assertThat(result.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("organizationLogoDocumentId", "nextEmployeeSequence", "passwordHash", "storageKey");
    }

    @Test
    void returnsStoredActiveAndInactiveStatusWithoutConsultingAccountState() {
        Organization active = organization();
        active.setStatus(OrganizationStatus.ACTIVE);
        Organization inactive = organization();
        inactive.setId("org-2");
        inactive.setOrganizationCode("PAUSED");
        inactive.setStatus(OrganizationStatus.INACTIVE);
        when(organizationRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(active, inactive)));

        var result = service.list("platform-1", 0, 10, null);

        assertThat(result.items()).extracting(item -> item.status())
                .containsExactly(OrganizationStatus.ACTIVE, OrganizationStatus.INACTIVE);
    }

    @Test
    void reportsMissingOrganization() {
        when(organizationRepository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get("platform-1", "missing"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Organization not found");
    }

    @Test
    void rejectsInvalidPagination() {
        assertThatThrownBy(() -> service.list("platform-1", -1, 10, null))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.list("platform-1", 0, 101, null))
                .isInstanceOf(BusinessException.class);
    }

    private Organization organization() {
        return Organization.builder()
                .id("org-1").organizationCode("ACME").organizationName("Acme Health")
                .organizationEmail("people@acme.example").phoneNumber("555-0100")
                .city("Chicago").state("IL").streetAddress("100 Main St").zipCode("60601")
                .organizationSize(CompanySize.MEDIUM).industry("Healthcare")
                .description("Regional provider").website("https://acme.example")
                .status(null)
                .organizationLogoDocumentId("private-logo-key").nextEmployeeSequence(41L)
                .createdAt(LocalDateTime.of(2026, 1, 2, 3, 4))
                .updatedAt(LocalDateTime.of(2026, 2, 3, 4, 5)).build();
    }
}
