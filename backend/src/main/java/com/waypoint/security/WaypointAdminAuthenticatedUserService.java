package com.waypoint.security;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Resolves a platform administrator from the persisted user record. Platform
 * administrators deliberately have no customer organization association and
 * are never passed through organization-scoped My HR authorization.
 */
@Service
@RequiredArgsConstructor
public class WaypointAdminAuthenticatedUserService {

    private final UserRepository userRepository;

    public AuthenticatedWaypointAdmin require(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new UnauthorizedException("Account is not active");
        }
        if (user.getRole() != UserRole.WAYPOINT_ADMIN) {
            throw new ForbiddenException("You do not have access to Waypoint Admin");
        }
        if (user.getOrganizationId() != null && !user.getOrganizationId().isBlank()) {
            throw new ForbiddenException("Waypoint Admin cannot be associated with a customer organization");
        }
        return new AuthenticatedWaypointAdmin(
                user.getId(), user.getLoginId(), user.getEmail(), user.getRole(),
                Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    public record AuthenticatedWaypointAdmin(
            String userId, String loginId, String email, UserRole role, boolean mustChangePassword) {
    }
}
