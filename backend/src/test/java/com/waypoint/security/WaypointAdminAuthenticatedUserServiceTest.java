package com.waypoint.security;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WaypointAdminAuthenticatedUserServiceTest {

    @Mock UserRepository userRepository;
    private WaypointAdminAuthenticatedUserService service;

    @BeforeEach
    void setUp() {
        service = new WaypointAdminAuthenticatedUserService(userRepository);
    }

    @Test
    void acceptsActiveOrganizationIndependentWaypointAdmin() {
        when(userRepository.findById("platform-1")).thenReturn(Optional.of(user(
                UserRole.WAYPOINT_ADMIN, AccountStatus.ACTIVE, null)));

        var result = service.require("platform-1");

        assertThat(result.role()).isEqualTo(UserRole.WAYPOINT_ADMIN);
        assertThat(result.loginId()).isEqualTo("WPADMIN");
    }

    @Test
    void rejectsCompanyAdminWithoutGrantingPlatformAccess() {
        when(userRepository.findById("company-1")).thenReturn(Optional.of(user(
                UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE, "org-1")));

        assertThatThrownBy(() -> service.require("company-1"))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rejectsPlatformRoleAssociatedWithCustomerOrganization() {
        when(userRepository.findById("platform-1")).thenReturn(Optional.of(user(
                UserRole.WAYPOINT_ADMIN, AccountStatus.ACTIVE, "org-1")));

        assertThatThrownBy(() -> service.require("platform-1"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("customer organization");
    }

    @Test
    void rejectsInactivePlatformAdmin() {
        when(userRepository.findById("platform-1")).thenReturn(Optional.of(user(
                UserRole.WAYPOINT_ADMIN, AccountStatus.INACTIVE, null)));

        assertThatThrownBy(() -> service.require("platform-1"))
                .isInstanceOf(UnauthorizedException.class);
    }

    private User user(UserRole role, AccountStatus status, String organizationId) {
        return User.builder().id("platform-1").loginId("WPADMIN").email("admin@waypoint.test")
                .role(role).accountStatus(status).organizationId(organizationId).build();
    }
}
