package com.waypoint.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Validates the Authorization header for protected API paths (registered
 * per-path in WebMvcConfig) using the existing JwtService, and exposes the
 * authenticated identity to controllers via request attributes. This is a
 * deliberately minimal stand-in for a full Spring Security filter chain -
 * see WebMvcConfig for which paths it applies to.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthInterceptor implements HandlerInterceptor {

    public static final String AUTHENTICATED_USER_ID = "authenticatedUserId";
    public static final String AUTHENTICATED_ROLE = "authenticatedRole";

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String REQUIRED_ROLE_FOR_EMPLOYER_PATHS = "EMPLOYER";
    private static final String REQUIRED_ROLE_FOR_WAYPOINT_ADMIN_PATHS = "WAYPOINT_ADMIN";

    private final JwtService jwtService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String authorizationHeader = request.getHeader("Authorization");

        if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid authentication token");
            return false;
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();

        String userId;
        String role;
        try {
            userId = jwtService.extractUserId(token);
            role = jwtService.extractRole(token);
        } catch (JwtException | IllegalArgumentException exception) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "Invalid or expired authentication token");
            return false;
        }

        String requiredRole = requiredRoleFor(request.getRequestURI());
        if (requiredRole != null && !requiredRole.equals(role)) {
            writeError(response, HttpServletResponse.SC_FORBIDDEN, "You do not have access to this resource");
            return false;
        }

        request.setAttribute(AUTHENTICATED_USER_ID, userId);
        request.setAttribute(AUTHENTICATED_ROLE, role);
        return true;
    }

    private String requiredRoleFor(String requestUri) {
        if (requestUri.startsWith("/api/waypoint-admin/")) {
            return REQUIRED_ROLE_FOR_WAYPOINT_ADMIN_PATHS;
        }

        if (requestUri.startsWith("/api/employer/")) {
            return REQUIRED_ROLE_FOR_EMPLOYER_PATHS;
        }

        if (requestUri.startsWith("/api/job-seeker/")) {
            return "JOB_SEEKER";
        }

        return null;
    }

    private void writeError(HttpServletResponse response, int status, String message) throws java.io.IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"message\":\"" + message + "\",\"data\":null}");
    }
}
