package com.waypoint.service;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaypointAdminProvisioningServiceTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;
    @InjectMocks WaypointAdminProvisioningService service;

    @Test
    void createsOrganizationIndependentPlatformAdminUsingEncodedPassword() {
        when(userRepository.findByRole(UserRole.WAYPOINT_ADMIN)).thenReturn(List.of());
        when(passwordEncoder.encode("Temporary9!")).thenReturn("bcrypt-hash");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId("platform-1");
            return saved;
        });

        var result = service.provision(" wpadmin ", " Admin@Waypoint.Test ", "Temporary9!");

        ArgumentCaptor<User> account = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(account.capture());
        assertThat(account.getValue().getLoginId()).isEqualTo("WPADMIN");
        assertThat(account.getValue().getEmail()).isEqualTo("admin@waypoint.test");
        assertThat(account.getValue().getRole()).isEqualTo(UserRole.WAYPOINT_ADMIN);
        assertThat(account.getValue().getOrganizationId()).isNull();
        assertThat(account.getValue().getAccountStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.getValue().getIsEmailVerified()).isTrue();
        assertThat(account.getValue().getMustChangePassword()).isTrue();
        assertThat(account.getValue().getPasswordHash()).isEqualTo("bcrypt-hash")
                .isNotEqualTo("Temporary9!");
        assertThat(result.created()).isTrue();
    }

    @Test
    void returnsExactExistingAccountWithoutChangingIt() {
        User existing = validExisting();
        when(userRepository.findByRole(UserRole.WAYPOINT_ADMIN)).thenReturn(List.of(existing));

        var result = service.provision("WPADMIN", "admin@waypoint.test", "DifferentTemporary9!");

        assertThat(result.created()).isFalse();
        assertThat(result.userId()).isEqualTo("platform-1");
        verify(userRepository, never()).save(any());
        verify(passwordEncoder, never()).encode(any());
    }

    @Test
    void refusesConflictingOrMultiplePlatformAccounts() {
        User conflicting = validExisting();
        conflicting.setOrganizationId("customer-org");
        when(userRepository.findByRole(UserRole.WAYPOINT_ADMIN)).thenReturn(List.of(conflicting));

        assertThatThrownBy(() -> service.provision("WPADMIN", "admin@waypoint.test", "Temporary9!"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("conflicting");

        when(userRepository.findByRole(UserRole.WAYPOINT_ADMIN))
                .thenReturn(List.of(validExisting(), validExisting()));
        assertThatThrownBy(() -> service.provision("WPADMIN", "admin@waypoint.test", "Temporary9!"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Multiple");
        verify(userRepository, never()).save(any());
    }

    @Test
    void refusesLoginIdOrEmailOwnedByAnotherAccount() {
        when(userRepository.findByRole(UserRole.WAYPOINT_ADMIN)).thenReturn(List.of());
        when(userRepository.existsByLoginId("WPADMIN")).thenReturn(true);
        assertThatThrownBy(() -> service.provision("WPADMIN", "admin@waypoint.test", "Temporary9!"))
                .hasMessageContaining("login ID");

        when(userRepository.existsByLoginId("WPADMIN")).thenReturn(false);
        when(userRepository.existsByEmail("admin@waypoint.test")).thenReturn(true);
        assertThatThrownBy(() -> service.provision("WPADMIN", "admin@waypoint.test", "Temporary9!"))
                .hasMessageContaining("email");
        verify(userRepository, never()).save(any());
    }

    private User validExisting() {
        return User.builder()
                .id("platform-1")
                .loginId("WPADMIN")
                .email("admin@waypoint.test")
                .passwordHash("bcrypt-hash")
                .role(UserRole.WAYPOINT_ADMIN)
                .organizationId(null)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .mustChangePassword(true)
                .build();
    }
}
