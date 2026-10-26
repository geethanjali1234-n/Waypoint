package com.waypoint.service;

import com.waypoint.entity.Organization;
import com.waypoint.enums.CompanySize;
import com.waypoint.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TardusOrganizationProvisioningServiceTest {

    @Mock
    private OrganizationRepository organizationRepository;

    @InjectMocks
    private TardusOrganizationProvisioningService service;

    @Test
    void createsTardusWhenOrganizationCodeDoesNotExist() {
        when(organizationRepository.findByOrganizationCode("TD")).thenReturn(Optional.empty());
        when(organizationRepository.save(any(Organization.class))).thenAnswer(invocation -> {
            Organization organization = invocation.getArgument(0, Organization.class);
            organization.setId("generated-id");
            return organization;
        });

        var result = service.provision();

        ArgumentCaptor<Organization> captor = ArgumentCaptor.forClass(Organization.class);
        verify(organizationRepository).save(captor.capture());
        Organization saved = captor.getValue();
        assertThat(result.created()).isTrue();
        assertThat(saved.getId()).isEqualTo("generated-id");
        assertThat(saved.getOrganizationCode()).isEqualTo("TD");
        assertThat(saved.getOrganizationName()).isEqualTo("Tardus Inc");
        assertThat(saved.getOrganizationEmail()).isEqualTo("myhr.dev.tardus@waypoint.local");
        assertThat(saved.getOrganizationSize()).isEqualTo(CompanySize.MEDIUM);
        assertThat(saved.getNextEmployeeSequence()).isEqualTo(1L);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isEqualTo(saved.getCreatedAt());
    }

    @Test
    void repeatedProvisioningDoesNotCreateDuplicateOrOverwriteExistingData() {
        LocalDateTime originalCreatedAt = LocalDateTime.of(2026, 1, 10, 9, 30);
        Organization existing = Organization.builder()
                .id("existing-id")
                .organizationCode("TD")
                .organizationName("Existing Tardus Name")
                .organizationEmail("existing@tardus.test")
                .organizationSize(CompanySize.LARGE)
                .description("Existing data must remain unchanged")
                .nextEmployeeSequence(42L)
                .createdAt(originalCreatedAt)
                .updatedAt(originalCreatedAt)
                .build();
        when(organizationRepository.findByOrganizationCode("TD"))
                .thenReturn(Optional.of(existing))
                .thenReturn(Optional.of(existing));

        var first = service.provision();
        var second = service.provision();

        assertThat(first.created()).isFalse();
        assertThat(second.created()).isFalse();
        assertThat(first.organization()).isSameAs(existing);
        assertThat(existing.getOrganizationName()).isEqualTo("Existing Tardus Name");
        assertThat(existing.getDescription()).isEqualTo("Existing data must remain unchanged");
        assertThat(existing.getNextEmployeeSequence()).isEqualTo(42L);
        assertThat(existing.getUpdatedAt()).isEqualTo(originalCreatedAt);
        verify(organizationRepository, times(2)).findByOrganizationCode("TD");
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void newOrganizationSequenceStartsAtOne() {
        when(organizationRepository.findByOrganizationCode("TD")).thenReturn(Optional.empty());
        when(organizationRepository.save(any(Organization.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, Organization.class));

        assertThat(service.provision().organization().getNextEmployeeSequence()).isEqualTo(1L);
    }

    @Test
    void existingOrganizationFieldsAreNotOverwritten() {
        Organization existing = existingOrganization(17L);
        when(organizationRepository.findByOrganizationCode("TD")).thenReturn(Optional.of(existing));

        service.provision();

        assertThat(existing.getOrganizationName()).isEqualTo("Existing Tardus Name");
        assertThat(existing.getOrganizationEmail()).isEqualTo("existing@tardus.test");
        assertThat(existing.getOrganizationSize()).isEqualTo(CompanySize.LARGE);
        assertThat(existing.getDescription()).isEqualTo("Existing data must remain unchanged");
        verify(organizationRepository, never()).save(any());
    }

    @Test
    void existingEmployeeSequenceIsNotReset() {
        Organization existing = existingOrganization(73L);
        when(organizationRepository.findByOrganizationCode("TD")).thenReturn(Optional.of(existing));

        service.provision();

        assertThat(existing.getNextEmployeeSequence()).isEqualTo(73L);
        verify(organizationRepository, never()).save(any());
    }

    private Organization existingOrganization(long nextEmployeeSequence) {
        return Organization.builder()
                .id("existing-id")
                .organizationCode("TD")
                .organizationName("Existing Tardus Name")
                .organizationEmail("existing@tardus.test")
                .organizationSize(CompanySize.LARGE)
                .description("Existing data must remain unchanged")
                .nextEmployeeSequence(nextEmployeeSequence)
                .createdAt(LocalDateTime.of(2026, 1, 10, 9, 30))
                .updatedAt(LocalDateTime.of(2026, 1, 10, 9, 30))
                .build();
    }
}
