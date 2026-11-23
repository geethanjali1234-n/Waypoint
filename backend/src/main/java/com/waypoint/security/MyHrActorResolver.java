package com.waypoint.security;

import com.waypoint.enums.UserRole;
import com.waypoint.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * The only issuer of {@link VerifiedMyHrActor}. The user id comes from the request's bearer token,
 * whose signature and expiry {@link CurrentUserResolver} verifies on every call, so this works
 * whether or not JwtAuthInterceptor covers the path. Role and organization are then loaded from the
 * user's current database record by {@link MyHrAuthenticatedUserService}; the token's role claim,
 * request parameters, headers, bodies and attributes are never consulted.
 */
@Component
@RequiredArgsConstructor
public class MyHrActorResolver {

    private static final String INVALID_TOKEN = "Invalid or expired token";

    private final CurrentUserResolver currentUserResolver;
    private final MyHrAuthenticatedUserService authenticatedUserService;

    /** An active HR or COMPANY_ADMIN actor, for knowledge base management. */
    public VerifiedMyHrActor resolveKnowledgeManager(HttpServletRequest request) {
        return resolve(request, VerifiedMyHrActor.KNOWLEDGE_MANAGER_ROLES);
    }

    /**
     * An active actor whose current database role is one of allowedRoles and who has no pending
     * password change; otherwise UnauthorizedException or ForbiddenException.
     */
    public VerifiedMyHrActor resolve(HttpServletRequest request, Set<UserRole> allowedRoles) {
        if (allowedRoles == null || allowedRoles.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed role is required");
        }
        if (request == null) {
            throw new UnauthorizedException(INVALID_TOKEN);
        }
        String userId;
        try {
            userId = currentUserResolver.resolveUserId(request);
        } catch (IllegalArgumentException emptyToken) {
            // JJWT rejects an empty token ("Bearer ") this way rather than with a JwtException.
            throw new UnauthorizedException(INVALID_TOKEN);
        }
        if (userId == null || userId.isBlank()) {
            throw new UnauthorizedException(INVALID_TOKEN);
        }
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser user =
                authenticatedUserService.require(userId, allowedRoles, false);
        return VerifiedMyHrActor.issue(user.userId(), user.organizationId(), user.role());
    }
}
