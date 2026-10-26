package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.profile.JobSeekerProfileResponse;
import com.waypoint.dto.profile.JobSeekerProfileUpdateRequest;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.service.JobSeekerProfileService;
import com.waypoint.service.JobSeekerResumeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
@RequestMapping("/api/job-seeker/profile")
@RequiredArgsConstructor
public class JobSeekerProfileController {

    private final JobSeekerProfileService jobSeekerProfileService;
    private final JobSeekerResumeService jobSeekerResumeService;
    private final CurrentUserResolver currentUserResolver;

    @GetMapping
    public ResponseEntity<ApiResponse<JobSeekerProfileResponse>> getProfile(HttpServletRequest request) {
        String userId = resolveJobSeekerUserId(request);
        JobSeekerProfileResponse response = jobSeekerProfileService.getProfile(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Profile fetched successfully", response));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<JobSeekerProfileResponse>> updateProfile(
            HttpServletRequest request,
            @Valid @RequestBody JobSeekerProfileUpdateRequest updateRequest) {

        String userId = resolveJobSeekerUserId(request);
        JobSeekerProfileResponse response = jobSeekerProfileService.updateProfile(userId, updateRequest);

        return ResponseEntity.ok(new ApiResponse<>(true, "Profile updated successfully", response));
    }

    @PostMapping("/photo")
    public ResponseEntity<ApiResponse<JobSeekerProfileResponse>> updatePhoto(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file) {

        String userId = resolveJobSeekerUserId(request);
        JobSeekerProfileResponse response = jobSeekerProfileService.updateProfilePhoto(userId, file);

        return ResponseEntity.ok(new ApiResponse<>(true, "Profile photo updated successfully", response));
    }

    @PostMapping(value = "/resume", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<JobSeekerProfileResponse>> updateResume(
            HttpServletRequest request,
            @RequestParam("file") MultipartFile file) {

        String userId = resolveJobSeekerUserId(request);
        jobSeekerResumeService.uploadResume(userId, file);
        JobSeekerProfileResponse response = jobSeekerProfileService.getProfile(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Resume updated successfully", response));
    }

    private String resolveJobSeekerUserId(HttpServletRequest request) {
        String userId = currentUserResolver.resolveUserId(request);
        String role = currentUserResolver.resolveRole(request);

        if (!UserRole.JOB_SEEKER.name().equals(role)) {
            throw new ForbiddenException("Access restricted to job seekers");
        }

        return userId;
    }
}
