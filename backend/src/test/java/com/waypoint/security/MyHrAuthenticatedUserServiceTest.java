package com.waypoint.security;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MyHrAuthenticatedUserServiceTest {
    @Mock UserRepository userRepository;
    @InjectMocks MyHrAuthenticatedUserService service;

    @Test
    void returnsCurrentDatabaseBackedIdentity() {
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(user(false)));
        var result = service.require("admin-1", Set.of(UserRole.COMPANY_ADMIN), false);
        assertThat(result.organizationId()).isEqualTo("org-1");
        assertThat(result.accountStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void rejectsNormalMyHrAccessUntilPasswordChanges() {
        when(userRepository.findById("admin-1")).thenReturn(Optional.of(user(true)));
        assertThatThrownBy(() -> service.require("admin-1", Set.of(UserRole.COMPANY_ADMIN), false))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Password change is required");
    }

    private User user(boolean mustChangePassword) {
        return User.builder().id("admin-1").role(UserRole.COMPANY_ADMIN).organizationId("org-1")
                .accountStatus(AccountStatus.ACTIVE).mustChangePassword(mustChangePassword).build();
    }
}
