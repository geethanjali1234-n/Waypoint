package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.GoogleConnectStartResponse;
import com.waypoint.dto.myhr.GoogleConnectionStatusResponse;
import com.waypoint.dto.myhr.GoogleTestEmailResponse;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.google.GoogleConnectionService;
import com.waypoint.service.google.GoogleTestEmailService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Company Admin endpoints for the organization's Google email connection. Under {@code /api/myhr/**}, so the JWT
 * interceptor applies; the organization is always the authenticated user's own and is never a request parameter.
 */
@RestController
@RequestMapping("/api/myhr/organization-settings/email/google")
@RequiredArgsConstructor
public class OrganizationGoogleConnectionController {

    private final CurrentUserResolver currentUserResolver;
    private final GoogleConnectionService googleConnectionService;
    private final GoogleTestEmailService googleTestEmailService;

    @GetMapping
    public ApiResponse<GoogleConnectionStatusResponse> status(HttpServletRequest request) {
        return new ApiResponse<>(true, "Google email connection",
                googleConnectionService.status(currentUserResolver.resolveUserId(request)));
    }

    @PostMapping("/connect")
    public ApiResponse<GoogleConnectStartResponse> connect(HttpServletRequest request) {
        return new ApiResponse<>(true, "Google authorization started",
                googleConnectionService.start(currentUserResolver.resolveUserId(request)));
    }

    /**
     * Sends a fixed test message from the organization's connected Google account to that same organization's
     * Organization Email (Reply-To is the Organization Email too). Takes no body or parameters: nothing about the
     * recipient, sender or content can be supplied, and there is no SMTP fallback.
     */
    @PostMapping("/test")
    public ApiResponse<GoogleTestEmailResponse> test(HttpServletRequest request) {
        return new ApiResponse<>(true, "Google test email attempted",
                googleTestEmailService.sendTestEmail(currentUserResolver.resolveUserId(request)));
    }

    @DeleteMapping
    public ApiResponse<GoogleConnectionStatusResponse> disconnect(HttpServletRequest request) {
        return new ApiResponse<>(true, "Google email account disconnected",
                googleConnectionService.disconnect(currentUserResolver.resolveUserId(request)));
    }
}
