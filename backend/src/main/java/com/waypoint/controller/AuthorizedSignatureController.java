package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.AuthorizedSignatureResponse;
import com.waypoint.dto.myhr.UpdateAuthorizedSignatureRequest;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.AuthorizedSignatureService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * COMPANY_ADMIN Authorized Signature settings. Like OrganizationSettingsController,
 * the organization always comes from the authenticated user, never the request.
 * The signature image is the admin's own Profile signature, so there is no
 * upload endpoint here.
 */
@RestController
@RequestMapping("/api/myhr/organization-settings/authorized-signature")
@RequiredArgsConstructor
public class AuthorizedSignatureController {
    private final AuthorizedSignatureService service;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ApiResponse<AuthorizedSignatureResponse> get(HttpServletRequest request) {
        return new ApiResponse<>(true, "Authorized signature", service.get(userId(request)));
    }

    @PutMapping
    public ApiResponse<AuthorizedSignatureResponse> update(HttpServletRequest request,
                                                           @Valid @RequestBody UpdateAuthorizedSignatureRequest input) {
        return new ApiResponse<>(true, "Authorized signature updated", service.update(userId(request), input));
    }

    private String userId(HttpServletRequest request) { return currentUserResolver.resolveUserId(request); }
}
