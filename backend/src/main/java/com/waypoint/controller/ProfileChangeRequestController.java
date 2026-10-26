package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.myhr.CreateProfileChangeRequestRequest;
import com.waypoint.dto.myhr.ProfileChangeDecisionRequest;
import com.waypoint.dto.myhr.ProfileChangeRequestDetailResponse;
import com.waypoint.dto.myhr.ProfileChangeRequestResponse;
import com.waypoint.entity.ProfileChangeRequest;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.ProfileChangeRequestService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/myhr/profile-change-requests")
@RequiredArgsConstructor
public class ProfileChangeRequestController {

    private final ProfileChangeRequestService profileChangeRequestService;
    private final CurrentUserResolver currentUserResolver;

    @PostMapping
    public ResponseEntity<ApiResponse<ProfileChangeRequestResponse>> create(
            HttpServletRequest servletRequest,
            @Valid @RequestBody CreateProfileChangeRequestRequest request) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        ProfileChangeRequestResponse result = profileChangeRequestService.create(actorUserId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Profile change request submitted", result));
    }

    @GetMapping("/mine")
    public ApiResponse<List<ProfileChangeRequestResponse>> mine(HttpServletRequest servletRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        return new ApiResponse<>(true, "Your profile change requests", profileChangeRequestService.listMine(actorUserId));
    }

    @GetMapping
    public ApiResponse<List<ProfileChangeRequestResponse>> listForAdmin(HttpServletRequest servletRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        List<ProfileChangeRequestResponse> requests = profileChangeRequestService.listForAdmin(actorUserId);
        return new ApiResponse<>(true, "Organization profile change requests", requests);
    }

    @GetMapping("/{requestId}")
    public ApiResponse<ProfileChangeRequestDetailResponse> getDetail(
            HttpServletRequest servletRequest, @PathVariable String requestId) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        ProfileChangeRequestDetailResponse detail =
                profileChangeRequestService.getDetailForAdmin(actorUserId, requestId);
        return new ApiResponse<>(true, "Profile change request detail", detail);
    }

    @PostMapping("/{requestId}/approve")
    public ApiResponse<ProfileChangeRequestResponse> approve(
            HttpServletRequest servletRequest,
            @PathVariable String requestId,
            @Valid @RequestBody ProfileChangeDecisionRequest decisionRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        ProfileChangeRequest updated = profileChangeRequestService.approve(
                actorUserId, requestId, decisionRequest.processingComment());
        return new ApiResponse<>(true, "Profile change request approved", profileChangeRequestService.toResponse(updated));
    }

    @PostMapping("/{requestId}/reject")
    public ApiResponse<ProfileChangeRequestResponse> reject(
            HttpServletRequest servletRequest,
            @PathVariable String requestId,
            @Valid @RequestBody ProfileChangeDecisionRequest decisionRequest) {
        String actorUserId = currentUserResolver.resolveUserId(servletRequest);
        ProfileChangeRequest updated = profileChangeRequestService.reject(
                actorUserId, requestId, decisionRequest.processingComment());
        return new ApiResponse<>(true, "Profile change request rejected", profileChangeRequestService.toResponse(updated));
    }
}
