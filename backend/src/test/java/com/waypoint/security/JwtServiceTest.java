package com.waypoint.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-only-signing-secret-that-is-at-least-256-bits-long-0123456789";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = new JwtService(SECRET, 3600_000L);
    }

    @Test
    void generateTokenProducesTokenWithExpectedClaims() {
        String token = jwtService.generateToken("user-1", "jordan@example.com", "JOB_SEEKER");

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);

        var claims = jwtService.parseToken(token);
        assertThat(claims.getSubject()).isEqualTo("user-1");
        assertThat(claims.get("email", String.class)).isEqualTo("jordan@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("JOB_SEEKER");
        assertThat(claims.getIssuedAt()).isNotNull();
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
    }

    @Test
    void generatedTokenUsesConfiguredThreeHourExpiration() {
        JwtService threeHourJwtService = new JwtService(SECRET, 10_800_000L);

        var claims = threeHourJwtService.parseToken(
                threeHourJwtService.generateToken("user-1", "user@example.com", "EMPLOYEE"));

        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(10_800_000L);
    }

    @Test
    void applicationConfigurationDefaultsJwtExpirationToThreeHours() throws IOException {
        try (var stream = getClass().getResourceAsStream("/application.yaml")) {
            assertThat(stream).isNotNull();
            String configuration = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(configuration).contains("expiration-ms: ${JWT_EXPIRATION_MS:10800000}");
        }
    }

    @Test
    void extractUserIdReturnsTheTokenSubject() {
        String token = jwtService.generateToken("user-42", "someone@example.com", "EMPLOYER");

        assertThat(jwtService.extractUserId(token)).isEqualTo("user-42");
    }

    @Test
    void extractRoleReturnsTheRoleClaim() {
        String token = jwtService.generateToken("user-42", "someone@example.com", "EMPLOYER");

        assertThat(jwtService.extractRole(token)).isEqualTo("EMPLOYER");
    }

    @Test
    void parseTokenThrowsForMalformedToken() {
        assertThatThrownBy(() -> jwtService.parseToken("not-a-valid-jwt"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void parseTokenThrowsForTokenSignedWithDifferentKey() {
        JwtService otherJwtService = new JwtService(
                "a-completely-different-signing-secret-of-sufficient-length-9876543210", 3600_000L);
        String tokenFromOtherService = otherJwtService.generateToken("user-1", "jordan@example.com", "JOB_SEEKER");

        assertThatThrownBy(() -> jwtService.parseToken(tokenFromOtherService))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void parseTokenThrowsExpiredJwtExceptionForExpiredToken() {
        JwtService alreadyExpiredJwtService = new JwtService(SECRET, -1000L);
        String expiredToken = alreadyExpiredJwtService.generateToken("user-1", "jordan@example.com", "JOB_SEEKER");

        assertThatThrownBy(() -> jwtService.parseToken(expiredToken))
                .isInstanceOf(ExpiredJwtException.class);
    }
}
