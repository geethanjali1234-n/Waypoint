package com.waypoint.service;

import com.waypoint.dto.myhr.CompanyAdminProvisioningRequest;
import com.waypoint.entity.Organization;
import com.waypoint.entity.User;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
class CompanyAdminProvisioningServiceTest {

    @Mock OrganizationRepository organizationRepository;
    @Mock UserRepository userRepository;
    @Mock OrganizationEmployeeIdService employeeIdService;
    @Mock TemporaryPasswordGenerator passwordGenerator;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks CompanyAdminProvisioningService service;

    @Test
    void provisionsCompanyAdminWithoutPersistingPlaintextPassword() {
        Organization organization = Organization.builder().id("org-1").organizationCode("TD").build();
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(organization));
        when(employeeIdService.generateNextLoginId("org-1", UserRole.COMPANY_ADMIN)).thenReturn("TDADMIN");
        when(passwordGenerator.generate()).thenReturn("Temporary9!");
        when(passwordEncoder.encode("Temporary9!")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User user = invocation.getArgument(0);
            user.setId("admin-1");
            return user;
        });

        var result = service.provision(new CompanyAdminProvisioningRequest("org-1", "Admin@Tardus.test"));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User stored = captor.getValue();
        assertThat(stored.getLoginId()).isEqualTo("TDADMIN");
        assertThat(stored.getRole()).isEqualTo(UserRole.COMPANY_ADMIN);
        assertThat(stored.getOrganizationId()).isEqualTo("org-1");
        assertThat(stored.getPasswordHash()).isEqualTo("bcrypt-hash").isNotEqualTo("Temporary9!");
        assertThat(stored.getMustChangePassword()).isTrue();
        assertThat(result.temporaryPassword()).isEqualTo("Temporary9!");
    }

    @Test
    void existingTdAdminIsReturnedWithoutResettingPasswordOrConsumingSequence() {
        Organization organization = Organization.builder().id("org-1").organizationCode("TD").build();
        User existing = User.builder().id("admin-1").loginId("TDADMIN")
                .email("admin@tardus.test").role(UserRole.COMPANY_ADMIN)
                .organizationId("org-1").passwordHash("existing-hash").mustChangePassword(false).build();
        when(organizationRepository.findById("org-1")).thenReturn(Optional.of(organization));
        when(employeeIdService.generateNextLoginId("org-1", UserRole.COMPANY_ADMIN)).thenReturn("TDADMIN");
        when(userRepository.findByLoginId("TDADMIN")).thenReturn(Optional.of(existing));

        var result = service.provision(new CompanyAdminProvisioningRequest("org-1", "ignored@tardus.test"));

        assertThat(result.userId()).isEqualTo("admin-1");
        assertThat(result.temporaryPassword()).isNull();
        verify(userRepository, never()).save(any());
        verify(passwordGenerator, never()).generate();
        verify(passwordEncoder, never()).encode(any());
    }
}
