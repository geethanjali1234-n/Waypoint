package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.profile.EmployerProfileResponse;
import com.waypoint.dto.profile.EmployerProfileUpdateRequest;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.EmployerProfileService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/employer/profile")
@RequiredArgsConstructor
public class EmployerProfileController {

    private final EmployerProfileService employerProfileService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<EmployerProfileResponse>> getProfile(HttpServletRequest request) {
        EmployerProfileResponse profile = employerProfileService.getProfile(resolveEmployerUserId(request));
        return ResponseEntity.ok(new ApiResponse<>(true, "Profile fetched successfully", profile));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<EmployerProfileResponse>> updateProfile(
            HttpServletRequest request,
            @Valid @RequestBody EmployerProfileUpdateRequest updateRequest) {
        EmployerProfileResponse profile = employerProfileService.updateProfile(
                resolveEmployerUserId(request), updateRequest);
        return ResponseEntity.ok(new ApiResponse<>(true, "Profile updated successfully", profile));
    }

    @PostMapping("/logo")
    public ResponseEntity<ApiResponse<EmployerProfileResponse>> updateLogo(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file) {
        EmployerProfileResponse profile = employerProfileService.updateLogo(resolveEmployerUserId(request), file);
        return ResponseEntity.ok(new ApiResponse<>(true, "Company logo updated successfully", profile));
    }

    private String resolveEmployerUserId(HttpServletRequest request) {
        String userId = currentUserResolver.resolveUserId(request);
        String role = currentUserResolver.resolveRole(request);
        if (!UserRole.EMPLOYER.name().equals(role)) {
            throw new ForbiddenException("Access restricted to employers");
        }
        return userId;
    }
}
