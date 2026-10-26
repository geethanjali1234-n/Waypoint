package com.waypoint.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Protects authentication endpoints against brute-force, dictionary, and
 * credential stuffing attacks by enforcing an in-memory rate limit per client IP.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class AuthRateLimitingFilter implements Filter {

    private static final int MAX_REQUESTS_PER_MINUTE = 30;
    private static final long WINDOW_MS = 60_000L;

    private final Map<String, ClientRateLimit> clients = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (request instanceof HttpServletRequest httpRequest
                && response instanceof HttpServletResponse httpResponse) {

            String uri = httpRequest.getRequestURI();
            String method = httpRequest.getMethod();

            if (isAuthEndpoint(uri, method)) {
                String clientIp = resolveClientIp(httpRequest);
                if (!isAllowed(clientIp)) {
                    httpResponse.setStatus(429);
                    httpResponse.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    httpResponse.setHeader("Retry-After", "60");
                    httpResponse.getWriter().write(
                            "{\"success\":false,\"message\":\"Too many authentication attempts. Please wait a minute and try again.\",\"data\":null}"
                    );
                    return;
                }
            }
        }

        chain.doFilter(request, response);
    }

    private boolean isAuthEndpoint(String uri, String method) {
        if (HttpMethod.OPTIONS.matches(method)) {
            return false;
        }
        if (uri == null) {
            return false;
        }
        return uri.equals("/api/auth/login")
                || uri.equals("/api/auth/forgot-password")
                || uri.equals("/api/auth/verify-reset-otp")
                || uri.equals("/api/auth/reset-password")
                // Public and may carry a logo upload, so bound it per client too.
                || uri.equals("/api/auth/organization-registration")
                || uri.equals("/api/myhr/auth/login");
    }

    private boolean isAllowed(String clientIp) {
        long now = System.currentTimeMillis();
        // Periodically purge entries older than 2 windows to keep memory bounded
        if (clients.size() > 5000) {
            clients.entrySet().removeIf(entry -> now - entry.getValue().windowStart > WINDOW_MS * 2);
        }

        ClientRateLimit limit = clients.compute(clientIp, (key, existing) -> {
            if (existing == null || (now - existing.windowStart) > WINDOW_MS) {
                return new ClientRateLimit(now, new AtomicInteger(1));
            }
            existing.count.incrementAndGet();
            return existing;
        });

        return limit.count.get() <= MAX_REQUESTS_PER_MINUTE;
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private static class ClientRateLimit {
        final long windowStart;
        final AtomicInteger count;

        ClientRateLimit(long windowStart, AtomicInteger count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}
