package com.waypoint.security;

import com.waypoint.exception.UnauthorizedException;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * Resolves the current authenticated user's id/role from the request's
 * bearer token. Delegates all parsing and signature/expiry validation to
 * the existing JwtService rather than decoding the token by hand.
 */
@Component
public class CurrentUserResolver {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public CurrentUserResolver(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    public String resolveUserId(HttpServletRequest request) {
        String token = extractToken(request);

        try {
            return jwtService.extractUserId(token);
        } catch (JwtException exception) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    public String resolveRole(HttpServletRequest request) {
        String token = extractToken(request);

        try {
            return jwtService.extractRole(token);
        } catch (JwtException exception) {
            throw new UnauthorizedException("Invalid or expired token");
        }
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new UnauthorizedException("Missing or invalid Authorization header");
        }

        return header.substring(BEARER_PREFIX.length()).trim();
    }
}
