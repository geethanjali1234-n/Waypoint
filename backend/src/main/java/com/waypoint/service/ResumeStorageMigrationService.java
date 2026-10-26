package com.waypoint.service;

import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.JobApplication;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Keeps resumes uploaded by the local-file implementation usable after the
 * application moves to shared GridFS storage. The original files are kept in
 * place as a safe fallback; only database paths are updated.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "waypoint.startup.resume-storage-migration.enabled", havingValue = "true")
public class ResumeStorageMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(ResumeStorageMigrationService.class);

    private final ResumeStorageService resumeStorageService;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final JobApplicationRepository jobApplicationRepository;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyResumes() {
        if (!resumeStorageService.isSharedStorageEnabled()) {
            return;
        }

        Map<String, String> migratedPaths = resumeStorageService.migrateLocalFilesToSharedStorage();
        if (migratedPaths.isEmpty()) {
            return;
        }

        List<JobSeekerProfile> updatedProfiles = jobSeekerProfileRepository.findAll()
                .stream()
                .filter(profile -> replaceLegacyPath(profile.getResumePath(), migratedPaths)
                        .map(newPath -> {
                            profile.setResumePath(newPath);
                            profile.setUpdatedAt(LocalDateTime.now());
                            return profile;
                        })
                        .isPresent())
                .toList();
        if (!updatedProfiles.isEmpty()) {
            jobSeekerProfileRepository.saveAll(updatedProfiles);
        }

        List<JobApplication> updatedApplications = jobApplicationRepository.findAll()
                .stream()
                .filter(application -> replaceLegacyPath(application.getResumePath(), migratedPaths)
                        .map(newPath -> {
                            application.setResumePath(newPath);
                            application.setUpdatedAt(LocalDateTime.now());
                            return application;
                        })
                        .isPresent())
                .toList();
        if (!updatedApplications.isEmpty()) {
            jobApplicationRepository.saveAll(updatedApplications);
        }

        logger.info(
                "Migrated {} legacy resume file(s) to shared GridFS; updated {} profile(s) and {} application(s)",
                migratedPaths.size(),
                updatedProfiles.size(),
                updatedApplications.size());
    }

    private java.util.Optional<String> replaceLegacyPath(
            String currentPath,
            Map<String, String> migratedPaths) {
        if (currentPath == null || currentPath.isBlank()) {
            return java.util.Optional.empty();
        }

        String normalizedPath = currentPath.trim().replace('\\', '/');
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        return java.util.Optional.ofNullable(migratedPaths.get(normalizedPath));
    }
}
