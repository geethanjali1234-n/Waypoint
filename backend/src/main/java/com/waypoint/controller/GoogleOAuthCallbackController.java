package com.waypoint.controller;

import com.waypoint.service.google.GoogleConnectResult;
import com.waypoint.service.google.GoogleConnectionService;
import com.waypoint.service.google.GoogleRateLimits;
import com.waypoint.service.google.PlatformGoogleAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Where Google sends the browser after the consent screen. Public (the browser carries no Authorization header here),
 * and deliberately outside {@code /api/myhr/**}. It identifies the organization and admin only through the stored,
 * single-use state, and always answers with a redirect to the settings page:
 * {@code ?google=connected} or {@code ?google=error&reason=CODE}. Nothing from the query string is echoed back except,
 * for a domain mismatch, the domain of the account the admin chose.
 */
@RestController
@RequestMapping("/api/oauth/google")
@RequiredArgsConstructor
public class GoogleOAuthCallbackController {

    private static final java.util.regex.Pattern IP_ADDRESS = java.util.regex.Pattern.compile("^[0-9a-fA-F:.]{3,45}$");
    static final String RETURN_PATH = "/myhr/admin/organization";
    static final String PLATFORM_RETURN_PATH = "/waypoint-admin/dashboard";

    private final GoogleConnectionService googleConnectionService;
    private final PlatformGoogleAuthorizationService platformGoogleAuthorizationService;
    private final GoogleRateLimits rateLimits;

    @Value("${waypoint.app.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl;

    @GetMapping("/callback")
    public ResponseEntity<Void> callback(
            @RequestParam(required = false) String code,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String error,
            HttpServletRequest request) {

        RoutedOutcome routed = outcomeOf(code, state, error, request);
        GoogleConnectionService.CallbackOutcome outcome = routed.outcome();

        UriComponentsBuilder target = UriComponentsBuilder.fromUriString(stripTrailingSlash(frontendBaseUrl)
                + (routed.platform() ? PLATFORM_RETURN_PATH : RETURN_PATH));
        if (outcome.result() == GoogleConnectResult.CONNECTED) {
            target.queryParam(routed.platform() ? "platformGoogle" : "google", "connected");
        } else {
            target.queryParam(routed.platform() ? "platformGoogle" : "google", "error").queryParam("reason", outcome.result().name());
            if (outcome.accountDomain() != null) target.queryParam("accountDomain", outcome.accountDomain());
        }
        URI location = target.build().encode().toUri();

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(location)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }

    /**
     * Bounded per client address before anything else happens (no database access when blocked), and never throws: every
     * outcome, including an unexpected failure, becomes a redirect with a generic reason code.
     */
    private record RoutedOutcome(boolean platform, GoogleConnectionService.CallbackOutcome outcome) { }

    private RoutedOutcome outcomeOf(String code, String state, String error, HttpServletRequest request) {
        try {
            if (!rateLimits.allowCallback(clientAddress(request))) {
                return new RoutedOutcome(false, GoogleConnectionService.CallbackOutcome.of(GoogleConnectResult.TOO_MANY_ATTEMPTS));
            }
            boolean platform = platformGoogleAuthorizationService.ownsState(state);
            if (platform) {
                return new RoutedOutcome(true, GoogleConnectionService.CallbackOutcome.of(
                        platformGoogleAuthorizationService.handleCallback(code, state, error)));
            }
            return new RoutedOutcome(false, googleConnectionService.handleCallback(code, state, error));
        } catch (RuntimeException failure) {
            return new RoutedOutcome(false, GoogleConnectionService.CallbackOutcome.of(GoogleConnectResult.CONNECT_FAILED));
        }
    }

    /**
     * The first X-Forwarded-For entry, as the rest of the application (AuthRateLimitingFilter) uses behind the platform
     * proxy, but only when it looks like an IP address: arbitrary header text must not become a limiter key. A caller can
     * still choose that entry, which is why {@link GoogleRateLimits} also applies one ceiling for all clients.
     */
    static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (IP_ADDRESS.matcher(first).matches()) return first;
        }
        return request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
