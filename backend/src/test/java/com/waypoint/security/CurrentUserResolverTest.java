package com.waypoint.security;

import com.waypoint.exception.UnauthorizedException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CurrentUserResolverTest {

    @Mock
    private JwtService jwtService;

    @Mock
    private HttpServletRequest request;

    private CurrentUserResolver currentUserResolver;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        currentUserResolver = new CurrentUserResolver(jwtService);
    }

    @Test
    void resolveUserIdReturnsUserIdFromValidToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");

        assertThat(currentUserResolver.resolveUserId(request)).isEqualTo("user-1");
    }

    @Test
    void resolveUserIdTrimsWhitespaceAroundToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer  valid-token  ");
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");

        assertThat(currentUserResolver.resolveUserId(request)).isEqualTo("user-1");
    }

    @Test
    void resolveRoleReturnsRoleFromValidToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtService.extractRole("valid-token")).thenReturn("JOB_SEEKER");

        assertThat(currentUserResolver.resolveRole(request)).isEqualTo("JOB_SEEKER");
    }

    @Test
    void resolveUserIdThrowsWhenAuthorizationHeaderMissing() {
        when(request.getHeader("Authorization")).thenReturn(null);

        assertThatThrownBy(() -> currentUserResolver.resolveUserId(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Missing or invalid Authorization header");
    }

    @Test
    void resolveUserIdThrowsWhenAuthorizationHeaderDoesNotStartWithBearer() {
        when(request.getHeader("Authorization")).thenReturn("Basic dXNlcjpwYXNz");

        assertThatThrownBy(() -> currentUserResolver.resolveUserId(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Missing or invalid Authorization header");
    }

    @Test
    void resolveUserIdThrowsWhenJwtServiceRejectsToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
        when(jwtService.extractUserId("invalid-token")).thenThrow(new JwtException("bad token"));

        assertThatThrownBy(() -> currentUserResolver.resolveUserId(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired token");
    }

    @Test
    void resolveRoleThrowsWhenJwtServiceRejectsToken() {
        when(request.getHeader("Authorization")).thenReturn("Bearer invalid-token");
        when(jwtService.extractRole("invalid-token")).thenThrow(new JwtException("bad token"));

        assertThatThrownBy(() -> currentUserResolver.resolveRole(request))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage("Invalid or expired token");
    }
}
