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
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Injects essential OWASP-recommended HTTP security headers into all responses:
 * - X-Content-Type-Options: nosniff (prevents MIME confusion attacks)
 * - X-Frame-Options: SAMEORIGIN (prevents clickjacking)
 * - X-XSS-Protection: 1; mode=block (enables legacy browser XSS filters)
 * - Referrer-Policy: strict-origin-when-cross-origin (prevents token/URL leaking)
 * - Permissions-Policy: camera=(), microphone=(), geolocation=() (restricts hardware)
 * - Cache-Control: prevents browser/proxy caching of sensitive enterprise API data
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (response instanceof HttpServletResponse httpResponse) {
            httpResponse.setHeader("X-Content-Type-Options", "nosniff");
            httpResponse.setHeader("X-Frame-Options", "SAMEORIGIN");
            httpResponse.setHeader("X-XSS-Protection", "1; mode=block");
            httpResponse.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
            httpResponse.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");

            if (request instanceof HttpServletRequest httpRequest) {
                String uri = httpRequest.getRequestURI();
                if (uri != null && uri.startsWith("/api/")) {
                    httpResponse.setHeader("Cache-Control", "no-cache, no-store, max-age=0, must-revalidate");
                    httpResponse.setHeader("Pragma", "no-cache");
                    httpResponse.setHeader("Expires", "0");
                }
            }
        }

        chain.doFilter(request, response);
    }
}
