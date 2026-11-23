package com.waypoint.security;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.UserRepository;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Runs the real token verification (JwtService, CurrentUserResolver) and the real database checks
 * (MyHrAuthenticatedUserService) with a test-only signing secret; only the user lookup is mocked.
 */
@ExtendWith(MockitoExtension.class)
class MyHrActorResolverTest {

    private static final String SECRET = "waypoint-test-only-jwt-signing-secret-0123456789";
    private static final String OTHER_SECRET = "an-unrelated-test-only-jwt-secret-9876543210xyz";
    private static final long ONE_HOUR = 3_600_000;

    @Mock
    private UserRepository userRepository;

    private MyHrActorResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new MyHrActorResolver(
                new CurrentUserResolver(new JwtService(SECRET, ONE_HOUR)),
                new MyHrAuthenticatedUserService(userRepository));
    }

    // --- Verified identity ----------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"HR", "COMPANY_ADMIN"})
    void resolvesKnowledgeManagersFromTheVerifiedTokenAndTheDatabaseRecord(UserRole role) {
        storedUser("manager-1", role, "org-1");

        VerifiedMyHrActor actor = resolver.resolveKnowledgeManager(requestWithToken(token("manager-1", role.name())));

        assertThat(actor.userId()).isEqualTo("manager-1");
        assertThat(actor.organizationId()).isEqualTo("org-1");
        assertThat(actor.role()).isEqualTo(role);
        assertThat(actor.canManageKnowledge()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"EMPLOYEE", "JOB_SEEKER", "EMPLOYER"})
    void rejectsEveryOtherRoleForKnowledgeManagement(UserRole role) {
        storedUser("user-1", role, "org-1");

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(requestWithToken(token("user-1", role.name()))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void databaseRoleOverridesAnElevatedRoleClaimInTheToken() {
        storedUser("employee-1", UserRole.EMPLOYEE, "org-1");

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(requestWithToken(token("employee-1", "COMPANY_ADMIN"))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void databaseRoleIsUsedEvenWhenTheTokenClaimIsStale() {
        storedUser("hr-1", UserRole.HR, "org-1");

        VerifiedMyHrActor actor = resolver.resolveKnowledgeManager(requestWithToken(token("hr-1", "EMPLOYEE")));

        assertThat(actor.role()).isEqualTo(UserRole.HR);
    }

    @Test
    void ignoresIdentitySuppliedInParametersHeadersAndAttributes() {
        storedUser("hr-1", UserRole.HR, "org-1");
        MockHttpServletRequest request = requestWithToken(token("hr-1", "HR"));
        request.addParameter("userId", "admin-9");
        request.addParameter("organizationId", "org-evil");
        request.addParameter("role", "COMPANY_ADMIN");
        request.addHeader("X-User-Id", "admin-9");
        request.addHeader("X-Organization-Id", "org-evil");
        request.addHeader("X-Role", "COMPANY_ADMIN");
        request.setAttribute(JwtAuthInterceptor.AUTHENTICATED_USER_ID, "admin-9");
        request.setAttribute(JwtAuthInterceptor.AUTHENTICATED_ROLE, "COMPANY_ADMIN");

        VerifiedMyHrActor actor = resolver.resolveKnowledgeManager(request);

        assertThat(actor.userId()).isEqualTo("hr-1");
        assertThat(actor.organizationId()).isEqualTo("org-1");
        assertThat(actor.role()).isEqualTo(UserRole.HR);
        verify(userRepository).findById("hr-1");
        verify(userRepository, never()).findById("admin-9");
    }

    @Test
    void resolvesAnyExplicitlyAllowedRoleSet() {
        storedUser("employee-1", UserRole.EMPLOYEE, "org-1");

        VerifiedMyHrActor actor = resolver.resolve(requestWithToken(token("employee-1", "EMPLOYEE")), Set.of(UserRole.EMPLOYEE));

        assertThat(actor.role()).isEqualTo(UserRole.EMPLOYEE);
        assertThat(actor.canManageKnowledge()).isFalse();
    }

    // --- Invalid tokens -------------------------------------------------------------------------

    @ParameterizedTest
    @MethodSource("invalidAuthorizationHeaders")
    void rejectsMissingInvalidAndSubjectlessTokensBeforeAnyDatabaseLookup(String description, String header) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (header != null) {
            request.addHeader("Authorization", header);
        }

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(request))
                .as(description)
                .isInstanceOf(UnauthorizedException.class);
        verifyNoInteractions(userRepository);
    }

    static Stream<Arguments> invalidAuthorizationHeaders() {
        String valid = token("hr-1", "HR");
        return Stream.of(
                Arguments.of("no Authorization header", null),
                Arguments.of("empty header", ""),
                Arguments.of("scheme only", "Bearer"),
                Arguments.of("empty bearer token", "Bearer "),
                Arguments.of("whitespace bearer token", "Bearer    "),
                Arguments.of("basic credentials", "Basic dXNlcjpwYXNz"),
                Arguments.of("lowercase scheme", "bearer " + valid),
                Arguments.of("not a JWT", "Bearer not-a-jwt"),
                Arguments.of("expired", "Bearer " + new JwtService(SECRET, -ONE_HOUR).generateToken("hr-1", "hr@x", "HR")),
                Arguments.of("signed with another key", "Bearer " + new JwtService(OTHER_SECRET, ONE_HOUR).generateToken("hr-1", "hr@x", "HR")),
                Arguments.of("payload swapped after signing", "Bearer " + withPayload(valid, "{\"sub\":\"admin-9\",\"role\":\"COMPANY_ADMIN\"}")),
                Arguments.of("unsigned (alg none)", "Bearer " + Jwts.builder().subject("hr-1").claim("role", "HR")
                        .expiration(new Date(System.currentTimeMillis() + ONE_HOUR)).compact()),
                Arguments.of("no subject", "Bearer " + signedWithoutSubject(null)),
                Arguments.of("blank subject", "Bearer " + signedWithoutSubject("   ")));
    }

    // --- Database checks ------------------------------------------------------------------------

    @Test
    void rejectsATokenForAUserWhoNoLongerExists() {
        when(userRepository.findById("hr-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(requestWithToken(token("hr-1", "HR"))))
                .isInstanceOf(UnauthorizedException.class);
    }

    @ParameterizedTest
    @EnumSource(value = AccountStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void rejectsInactiveAccounts(AccountStatus status) {
        when(userRepository.findById("hr-1")).thenReturn(Optional.of(user("hr-1", UserRole.HR, "org-1", status, false)));

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(requestWithToken(token("hr-1", "HR"))))
                .isInstanceOf(UnauthorizedException.class);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "   ")
    void rejectsUsersWithoutAnOrganization(String organizationId) {
        storedUser("hr-1", UserRole.HR, organizationId);

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(requestWithToken(token("hr-1", "HR"))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void rejectsUsersWhoMustChangeTheirPassword() {
        when(userRepository.findById("hr-1"))
                .thenReturn(Optional.of(user("hr-1", UserRole.HR, "org-1", AccountStatus.ACTIVE, true)));

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(requestWithToken(token("hr-1", "HR"))))
                .isInstanceOf(ForbiddenException.class);
    }

    // --- Programming errors ---------------------------------------------------------------------

    @Test
    void requiresAtLeastOneAllowedRoleBeforeReadingTheToken() {
        MockHttpServletRequest request = requestWithToken(token("hr-1", "HR"));

        assertThatThrownBy(() -> resolver.resolve(request, Set.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> resolver.resolve(request, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(userRepository);
    }

    @Test
    void rejectsAMissingRequest() {
        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(null)).isInstanceOf(UnauthorizedException.class);
        verify(userRepository, never()).findById(anyString());
    }

    @Test
    void failureMessagesNeverEchoTheToken() {
        String forged = new JwtService(OTHER_SECRET, ONE_HOUR).generateToken("hr-1", "hr@x", "HR");

        assertThatThrownBy(() -> resolver.resolveKnowledgeManager(requestWithToken(forged)))
                .isInstanceOf(UnauthorizedException.class)
                .message()
                .doesNotContain(forged)
                .doesNotContain("hr-1");
    }

    // --- Helpers --------------------------------------------------------------------------------

    private static String token(String userId, String roleClaim) {
        return new JwtService(SECRET, ONE_HOUR).generateToken(userId, userId + "@waypoint.local", roleClaim);
    }

    private static String signedWithoutSubject(String subject) {
        var builder = Jwts.builder().claim("role", "HR").expiration(new Date(System.currentTimeMillis() + ONE_HOUR));
        if (subject != null) {
            builder.subject(subject);
        }
        return builder.signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    /** Keeps the original header and signature but swaps in a different payload. */
    private static String withPayload(String token, String payloadJson) {
        String[] parts = token.split("\\.");
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
        return parts[0] + "." + payload + "." + parts[2];
    }

    private static MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);
        return request;
    }

    private void storedUser(String userId, UserRole role, String organizationId) {
        when(userRepository.findById(userId))
                .thenReturn(Optional.of(user(userId, role, organizationId, AccountStatus.ACTIVE, false)));
    }

    private static User user(String userId, UserRole role, String organizationId, AccountStatus status, boolean mustChangePassword) {
        return User.builder()
                .id(userId)
                .loginId("LOGIN-" + userId)
                .email(userId + "@waypoint.local")
                .role(role)
                .organizationId(organizationId)
                .accountStatus(status)
                .mustChangePassword(mustChangePassword)
                .build();
    }
}
