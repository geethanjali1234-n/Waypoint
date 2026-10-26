package com.waypoint.service;

import com.waypoint.dto.jobseeker.JobSeekerResumeUploadResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.JobApplication;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class JobSeekerResumeService {

    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final ResumeStorageService resumeStorageService;

    public JobSeekerResumeUploadResponse uploadResume(String jobSeekerUserId, MultipartFile file) {
        JobSeekerProfile profile = jobSeekerProfileRepository.findByUserId(jobSeekerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("JobSeeker profile was not found"));

        ResumeStorageService.StoredResume storedResume = resumeStorageService.store(file, profile.getId());
        LocalDateTime now = LocalDateTime.now();
        profile.setResumePath(storedResume.storedPath());
        profile.setUpdatedAt(now);
        jobSeekerProfileRepository.save(profile);

        // Preserve the resume snapshot used by applications that were already
        // submitted when its file is still available. If the recorded file is
        // missing, an explicit re-upload repairs that application so employers
        // do not remain stuck with a broken resume link.
        java.util.List<JobApplication> applications = jobApplicationRepository
                .findByJobSeekerProfileId(profile.getId());
        java.util.List<JobApplication> applicationsToRepair = applications.stream()
                .filter(application -> isBlank(application.getResumePath())
                        || !resumeStorageService.exists(application.getResumePath()))
                .peek(application -> {
                    application.setResumePath(storedResume.storedPath());
                    application.setUpdatedAt(now);
                })
                .toList();
        if (!applicationsToRepair.isEmpty()) {
            jobApplicationRepository.saveAll(applicationsToRepair);
        }

        return JobSeekerResumeUploadResponse.builder()
                .fileName(storedResume.fileName())
                .contentType(storedResume.mediaType().toString())
                .contentLength(storedResume.contentLength())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
