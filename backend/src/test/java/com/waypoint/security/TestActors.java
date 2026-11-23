package com.waypoint.security;

import com.waypoint.enums.UserRole;

/**
 * Issues VerifiedMyHrActor instances for tests in other packages, which cannot reach the
 * package-private factory. Test sources only: production code still has MyHrActorResolver as the
 * only issuer.
 */
public final class TestActors {

    private TestActors() {
    }

    public static VerifiedMyHrActor actor(String userId, String organizationId, UserRole role) {
        return VerifiedMyHrActor.issue(userId, organizationId, role);
    }

    public static VerifiedMyHrActor hr(String userId, String organizationId) {
        return actor(userId, organizationId, UserRole.HR);
    }

    public static VerifiedMyHrActor companyAdmin(String userId, String organizationId) {
        return actor(userId, organizationId, UserRole.COMPANY_ADMIN);
    }
}
