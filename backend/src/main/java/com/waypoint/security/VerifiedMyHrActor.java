package com.waypoint.security;

import com.waypoint.enums.UserRole;

import java.util.Set;

/**
 * A My HR user whose identity was verified server-side: the user id comes from a signature-checked
 * JWT, and the role and organization from the user's current database record. Services that act on
 * organization data take this instead of raw ids, so no caller can name an arbitrary user or
 * organization.
 *
 * <p>Only {@link MyHrActorResolver} issues actors. The constructor demands a key object that never
 * leaves this class, so reflective construction - Spring MVC constructor binding of an unannotated
 * controller parameter, or Jackson deserialization of a request body - always fails instead of
 * producing an actor from request-supplied values. The class is final, immutable and not
 * Serializable.
 */
public final class VerifiedMyHrActor {

    /** Roles allowed to approve, replace or revoke MyHR knowledge base documents. */
    public static final Set<UserRole> KNOWLEDGE_MANAGER_ROLES = Set.of(UserRole.HR, UserRole.COMPANY_ADMIN);

    private static final Object ISSUER = new Object();

    private final String userId;
    private final String organizationId;
    private final UserRole role;

    private VerifiedMyHrActor(Object issuer, String userId, String organizationId, UserRole role) {
        if (issuer != ISSUER) {
            throw new IllegalStateException("A VerifiedMyHrActor can only be issued by MyHrActorResolver");
        }
        if (isBlank(userId) || isBlank(organizationId) || role == null) {
            throw new IllegalArgumentException("A verified actor requires a user, an organization and a role");
        }
        this.userId = userId;
        this.organizationId = organizationId;
        this.role = role;
    }

    /** Package-private, so only code in this package - MyHrActorResolver - can issue actors. */
    static VerifiedMyHrActor issue(String userId, String organizationId, UserRole role) {
        return new VerifiedMyHrActor(ISSUER, userId, organizationId, role);
    }

    public String userId() {
        return userId;
    }

    public String organizationId() {
        return organizationId;
    }

    public UserRole role() {
        return role;
    }

    public boolean hasAnyRole(Set<UserRole> roles) {
        return roles != null && roles.contains(role);
    }

    public boolean canManageKnowledge() {
        return KNOWLEDGE_MANAGER_ROLES.contains(role);
    }

    /** Role only: user and organization ids stay out of logs. */
    @Override
    public String toString() {
        return "VerifiedMyHrActor[role=" + role + "]";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
