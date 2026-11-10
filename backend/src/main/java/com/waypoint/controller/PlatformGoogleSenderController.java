package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.GoogleConnectStartResponse;
import com.waypoint.dto.platform.PlatformGoogleSenderStatusResponse;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.google.PlatformGoogleAuthorizationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Waypoint-admin-only controls for the dedicated platform Gmail authorization. */
@RestController
@RequestMapping("/api/waypoint-admin/email/google")
@RequiredArgsConstructor
public class PlatformGoogleSenderController {
    private final CurrentUserResolver currentUserResolver;
    private final PlatformGoogleAuthorizationService authorizationService;

    @PostMapping("/connect")
    public ApiResponse<GoogleConnectStartResponse> connect(HttpServletRequest request) {
        return new ApiResponse<>(true, "Platform Google authorization started",
                authorizationService.start(currentUserResolver.resolveUserId(request)));
    }

    @GetMapping
    public ApiResponse<PlatformGoogleSenderStatusResponse> status(HttpServletRequest request) {
        return new ApiResponse<>(true, "Platform Google sender status",
                authorizationService.status(currentUserResolver.resolveUserId(request)));
    }
}
