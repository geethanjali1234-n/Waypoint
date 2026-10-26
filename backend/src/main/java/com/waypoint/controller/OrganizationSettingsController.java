package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.OrganizationSettingsResponse;
import com.waypoint.dto.myhr.UpdateOrganizationSettingsRequest;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.OrganizationSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * COMPANY_ADMIN organization settings API. Thin controller: it only resolves
 * the acting user id from the bearer token and delegates - the organization
 * scope and the COMPANY_ADMIN-only gate are enforced entirely in
 * OrganizationSettingsService. No endpoint accepts an organization id, so the
 * frontend can never choose which organization is read or written.
 */
@RestController
@RequestMapping("/api/myhr/organization-settings")
@RequiredArgsConstructor
public class OrganizationSettingsController {

    private final OrganizationSettingsService organizationSettingsService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ApiResponse<OrganizationSettingsResponse> get(HttpServletRequest servletRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Organization settings",
                organizationSettingsService.getForActor(actorUserId));
    }

    @PutMapping
    public ApiResponse<OrganizationSettingsResponse> update(
            HttpServletRequest servletRequest,
            @Valid @RequestBody UpdateOrganizationSettingsRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Organization settings updated",
                organizationSettingsService.updateForActor(actorUserId, request));
    }

    @PostMapping("/logo")
    public ApiResponse<OrganizationSettingsResponse> uploadLogo(
            HttpServletRequest servletRequest,
            @RequestParam("file") MultipartFile file) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Organization logo updated",
                organizationSettingsService.uploadLogo(actorUserId, file));
    }
}
