package com.waypoint.security;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class MyHrAuthenticatedUserService {

    private final UserRepository userRepository;

    public AuthenticatedMyHrUser require(String userId, Set<UserRole> allowedRoles, boolean allowPasswordChangeRequired) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UnauthorizedException("Authenticated user no longer exists"));
        if (user.getAccountStatus() != AccountStatus.ACTIVE) {
            throw new UnauthorizedException("Account is not active");
        }
        if (user.getOrganizationId() == null || user.getOrganizationId().isBlank()) {
            throw new ForbiddenException("My HR user is not associated with an organization");
        }
        if (!allowedRoles.contains(user.getRole())) {
            throw new ForbiddenException("You do not have access to this resource");
        }
        if (!allowPasswordChangeRequired && Boolean.TRUE.equals(user.getMustChangePassword())) {
            throw new ForbiddenException("Password change is required before accessing My HR");
        }
        return new AuthenticatedMyHrUser(
                user.getId(), user.getLoginId(), user.getEmail(), user.getRole(), user.getOrganizationId(),
                user.getAccountStatus(), Boolean.TRUE.equals(user.getMustChangePassword()));
    }

    public record AuthenticatedMyHrUser(
            String userId, String loginId, String email, UserRole role, String organizationId,
            AccountStatus accountStatus, boolean mustChangePassword) {
    }
}
