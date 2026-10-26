package com.waypoint.service;

import com.waypoint.dto.profile.EmployerProfileResponse;
import com.waypoint.dto.profile.EmployerProfileUpdateRequest;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.User;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class EmployerProfileService {

    private static final int TRACKED_FIELD_COUNT = 11;

    private final UserRepository userRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final FileStorageService fileStorageService;

    public EmployerProfileResponse getProfile(String userId) {
        return toResponse(findUser(userId), findProfile(userId));
    }

    public EmployerProfileResponse updateProfile(String userId, EmployerProfileUpdateRequest request) {
        User user = findUser(userId);
        EmployerProfile profile = findProfile(userId);

        profile.setCompanyName(request.getCompanyName().trim());
        profile.setCompanyDescription(normalizeOptional(request.getCompanyDescription()));
        profile.setContactPersonName(normalizeOptional(request.getContactPersonName()));
        profile.setIndustry(normalizeOptional(request.getIndustry()));
        profile.setCompanyAddress(normalizeOptional(request.getCompanyAddress()));
        profile.setPhoneNumber(normalizeOptional(request.getPhoneNumber()));
        profile.setCompanySize(request.getCompanySize());
        profile.setFoundedYear(request.getFoundedYear());
        profile.setCareersContactEmail(normalizeLowercase(request.getCareersContactEmail()));
        profile.setWebsite(normalizeOptional(request.getWebsite()));

        if (request.isRemoveCompanyLogo() && isNotBlank(profile.getCompanyLogoPath())) {
            fileStorageService.remove("company-logos", profile.getId());
            profile.setCompanyLogoPath(null);
        }

        profile.setUpdatedAt(LocalDateTime.now());
        return toResponse(user, employerProfileRepository.save(profile));
    }

    public EmployerProfileResponse updateLogo(String userId, MultipartFile file) {
        User user = findUser(userId);
        EmployerProfile profile = findProfile(userId);

        profile.setCompanyLogoPath(fileStorageService.store(file, "company-logos", profile.getId()));
        profile.setUpdatedAt(LocalDateTime.now());
        return toResponse(user, employerProfileRepository.save(profile));
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private EmployerProfile findProfile(String userId) {
        return employerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Employer profile not found"));
    }

    private EmployerProfileResponse toResponse(User user, EmployerProfile profile) {
        return EmployerProfileResponse.builder()
                .employerProfileId(profile.getId())
                .userId(user.getId())
                .companyName(profile.getCompanyName())
                .companyDescription(profile.getCompanyDescription())
                .contactPersonName(profile.getContactPersonName())
                .industry(profile.getIndustry())
                .email(user.getEmail())
                .companyAddress(profile.getCompanyAddress())
                .phoneNumber(profile.getPhoneNumber())
                .companySize(profile.getCompanySize())
                .foundedYear(profile.getFoundedYear())
                .careersContactEmail(profile.getCareersContactEmail())
                .website(profile.getWebsite())
                .companyLogoUrl(isNotBlank(profile.getCompanyLogoPath())
                        ? "/uploads/" + profile.getCompanyLogoPath() : null)
                .completionPercentage(computeCompletionPercentage(profile))
                .createdAt(profile.getCreatedAt())
                .updatedAt(profile.getUpdatedAt())
                .build();
    }

    private int computeCompletionPercentage(EmployerProfile profile) {
        int filled = 0;
        if (isNotBlank(profile.getCompanyName())) filled++;
        if (isNotBlank(profile.getCompanyDescription())) filled++;
        if (isNotBlank(profile.getContactPersonName())) filled++;
        if (isNotBlank(profile.getIndustry())) filled++;
        if (isNotBlank(profile.getCompanyAddress())) filled++;
        if (isNotBlank(profile.getPhoneNumber())) filled++;
        if (profile.getCompanySize() != null) filled++;
        if (profile.getFoundedYear() != null) filled++;
        if (isNotBlank(profile.getCareersContactEmail())) filled++;
        if (isNotBlank(profile.getWebsite())) filled++;
        if (isNotBlank(profile.getCompanyLogoPath())) filled++;
        return Math.round((filled * 100f) / TRACKED_FIELD_COUNT);
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String normalizeOptional(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String normalizeLowercase(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toLowerCase();
    }
}
