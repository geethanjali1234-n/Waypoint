package com.waypoint.service;

import com.waypoint.dto.profile.JobSeekerProfileResponse;
import com.waypoint.dto.profile.JobSeekerProfileUpdateRequest;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.JobApplication;
import com.waypoint.entity.User;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.RegistrationStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EventRegistrationRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class JobSeekerProfileService {

    private static final int TRACKED_FIELD_COUNT = 10;

    private final UserRepository userRepository;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final EventRegistrationRepository eventRegistrationRepository;
    private final FileStorageService fileStorageService;
    private final ResumeStorageService resumeStorageService;

    public JobSeekerProfileResponse getProfile(String userId) {
        User user = findUser(userId);
        JobSeekerProfile profile = findProfile(userId);

        return toResponse(user, profile);
    }

    public JobSeekerProfileResponse updateProfile(String userId, JobSeekerProfileUpdateRequest request) {
        User user = findUser(userId);
        JobSeekerProfile profile = findProfile(userId);

        if (request.getExpectedSalaryMin() != null && request.getExpectedSalaryMax() != null
                && request.getExpectedSalaryMin().compareTo(request.getExpectedSalaryMax()) > 0) {
            throw new BusinessException("Salary min must not be greater than salary max");
        }

        profile.setFirstName(request.getFirstName());
        profile.setLastName(request.getLastName());
        profile.setPhoneNumber(request.getPhoneNumber());
        profile.setSkills(request.getSkills());
        profile.setPreferredJobRoles(request.getPreferredJobRoles());
        profile.setPreferredEmploymentType(request.getPreferredEmploymentType());
        profile.setExpectedSalaryMin(request.getExpectedSalaryMin());
        profile.setExpectedSalaryMax(request.getExpectedSalaryMax());

        if (request.isRemoveProfilePicture() && isNotBlank(profile.getProfilePicturePath())) {
            fileStorageService.remove("profile-pictures", profile.getId());
            profile.setProfilePicturePath(null);
        }

        profile.setUpdatedAt(LocalDateTime.now());

        JobSeekerProfile saved = jobSeekerProfileRepository.save(profile);

        return toResponse(user, saved);
    }

    public JobSeekerProfileResponse updateProfilePhoto(String userId, MultipartFile file) {
        User user = findUser(userId);
        JobSeekerProfile profile = findProfile(userId);

        String relativePath = fileStorageService.store(file, "profile-pictures", profile.getId());
        profile.setProfilePicturePath(relativePath);
        profile.setUpdatedAt(LocalDateTime.now());

        JobSeekerProfile saved = jobSeekerProfileRepository.save(profile);

        return toResponse(user, saved);
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    private JobSeekerProfile findProfile(String userId) {
        return jobSeekerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("JobSeeker profile not found"));
    }

    private JobSeekerProfileResponse toResponse(User user, JobSeekerProfile profile) {
        String recordedResumePath = profile.getResumePath();
        boolean resumeAvailable = isNotBlank(recordedResumePath)
                && resumeStorageService.exists(recordedResumePath)
                && (!resumeStorageService.isSharedStorageEnabled()
                || resumeStorageService.isSharedStoragePath(recordedResumePath));
        FileStorageService.FileMetadata resumeMetadata = resumeAvailable
                ? fileStorageService.getMetadata(recordedResumePath)
                : null;

        String resumeFileName = null;
        LocalDateTime resumeUploadedAt = null;
        Long resumeSizeBytes = null;

        if (resumeMetadata != null) {
            resumeFileName = resumeMetadata.fileName();
            resumeUploadedAt = resumeMetadata.uploadedAt();
            resumeSizeBytes = resumeMetadata.sizeBytes();
        } else if (resumeAvailable) {
            resumeFileName = Path.of(recordedResumePath).getFileName().toString();
        }

        String profilePictureUrl = profile.getProfilePicturePath() != null
                ? "/uploads/" + profile.getProfilePicturePath()
                : null;

        List<JobApplication> applications =
                jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc(profile.getId());
        long jobsApplied = applications.size();
        long inReview = applications.stream()
                .filter(application -> application.getStatus() == ApplicationStatus.UNDER_REVIEW)
                .count();
        long shortlisted = applications.stream()
                .filter(application -> application.getStatus() == ApplicationStatus.SHORTLISTED)
                .count();

        long eventsRegistered = eventRegistrationRepository
                .findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                        profile.getId(), RegistrationStatus.REGISTERED)
                .size();

        return JobSeekerProfileResponse.builder()
                .userId(user.getId())
                .jobSeekerProfileId(profile.getId())
                .firstName(profile.getFirstName())
                .lastName(profile.getLastName())
                .email(user.getEmail())
                .phoneNumber(profile.getPhoneNumber())
                .address(profile.getAddress())
                .profilePictureUrl(profilePictureUrl)
                .resumeFileName(resumeFileName)
                .resumeUploadedAt(resumeUploadedAt)
                .resumeSizeBytes(resumeSizeBytes)
                .skills(profile.getSkills())
                .preferredJobRoles(profile.getPreferredJobRoles())
                .preferredEmploymentType(profile.getPreferredEmploymentType())
                .expectedSalaryMin(profile.getExpectedSalaryMin())
                .expectedSalaryMax(profile.getExpectedSalaryMax())
                .completionPercentage(computeCompletionPercentage(profile))
                .jobsApplied(jobsApplied)
                .inReview(inReview)
                .shortlisted(shortlisted)
                .eventsRegistered(eventsRegistered)
                .build();
    }

    private int computeCompletionPercentage(JobSeekerProfile profile) {
        int filled = 0;

        if (isNotBlank(profile.getFirstName())) filled++;
        if (isNotBlank(profile.getLastName())) filled++;
        if (isNotBlank(profile.getPhoneNumber())) filled++;
        if (isNotBlank(profile.getAddress())) filled++;
        if (isNotBlank(profile.getProfilePicturePath())) filled++;
        if (isNotBlank(profile.getResumePath())) filled++;
        if (isNotEmpty(profile.getSkills())) filled++;
        if (isNotEmpty(profile.getPreferredJobRoles())) filled++;
        if (isNotEmpty(profile.getPreferredEmploymentType())) filled++;
        if (hasSalaryRange(profile)) filled++;

        return Math.round((filled * 100f) / TRACKED_FIELD_COUNT);
    }

    private boolean hasSalaryRange(JobSeekerProfile profile) {
        return profile.getExpectedSalaryMin() != null && profile.getExpectedSalaryMax() != null;
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isNotEmpty(List<?> value) {
        return value != null && !value.isEmpty();
    }
}
