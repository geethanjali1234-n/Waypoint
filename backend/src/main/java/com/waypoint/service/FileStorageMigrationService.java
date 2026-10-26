package com.waypoint.service;

import com.mongodb.client.gridfs.GridFSBucket;
import com.mongodb.client.gridfs.GridFSBuckets;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Keeps profile pictures and company logos uploaded by the local-file
 * implementation usable after the application moves to shared GridFS
 * storage. The original files are kept in place as a safe fallback; only
 * database paths are updated.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "waypoint.startup.file-storage-migration.enabled", havingValue = "true")
public class FileStorageMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(FileStorageMigrationService.class);

    /**
     * Bucket name a since-removed teammate implementation
     * (ProfilePictureStorageService) used to store jobSeeker profile pictures
     * directly by GridFS object id, before this service's
     * subdirectory/ownerId-tagged "profile-images" bucket replaced it.
     */
    private static final String LEGACY_OBJECT_ID_BUCKET_NAME = "profile-pictures";

    private final FileStorageService fileStorageService;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final MongoTemplate mongoTemplate;

    @EventListener(ApplicationReadyEvent.class)
    public void migrateLegacyFiles() {
        if (!fileStorageService.isSharedStorageEnabled()) {
            return;
        }

        migrateLegacyObjectIdPictures();

        Map<String, String> migratedPaths = fileStorageService.migrateLocalFilesToSharedStorage();
        if (migratedPaths.isEmpty()) {
            return;
        }

        List<JobSeekerProfile> updatedProfiles = jobSeekerProfileRepository.findAll()
                .stream()
                .filter(profile -> replaceLegacyPath(profile.getProfilePicturePath(), migratedPaths)
                        .map(newPath -> {
                            profile.setProfilePicturePath(newPath);
                            profile.setUpdatedAt(LocalDateTime.now());
                            return profile;
                        })
                        .isPresent())
                .toList();
        if (!updatedProfiles.isEmpty()) {
            jobSeekerProfileRepository.saveAll(updatedProfiles);
        }

        List<EmployerProfile> updatedEmployers = employerProfileRepository.findAll()
                .stream()
                .filter(profile -> replaceLegacyPath(profile.getCompanyLogoPath(), migratedPaths)
                        .map(newPath -> {
                            profile.setCompanyLogoPath(newPath);
                            profile.setUpdatedAt(LocalDateTime.now());
                            return profile;
                        })
                        .isPresent())
                .toList();
        if (!updatedEmployers.isEmpty()) {
            employerProfileRepository.saveAll(updatedEmployers);
        }

        logger.info(
                "Migrated {} legacy profile image(s) to shared GridFS; updated {} job seeker profile(s) and {} "
                        + "employer profile(s)",
                migratedPaths.size(),
                updatedProfiles.size(),
                updatedEmployers.size());
    }

    /**
     * Repoints any jobSeeker profile still holding a bare GridFS object id
     * (the retired ProfilePictureStorageService's format) at a copy of that
     * same picture stored under this service's own convention, so profiles
     * migrated by the old implementation don't end up with a broken picture
     * URL now that its serving endpoint no longer exists.
     */
    private void migrateLegacyObjectIdPictures() {
        List<JobSeekerProfile> candidates = jobSeekerProfileRepository.findAll()
                .stream()
                .filter(profile -> isLegacyObjectIdPath(profile.getProfilePicturePath()))
                .toList();
        if (candidates.isEmpty()) {
            return;
        }

        GridFSBucket legacyBucket = GridFSBuckets.create(mongoTemplate.getDb(), LEGACY_OBJECT_ID_BUCKET_NAME);
        List<JobSeekerProfile> updatedProfiles = new ArrayList<>();
        for (JobSeekerProfile profile : candidates) {
            fileStorageService.copyLegacyObjectIdPicture(
                            legacyBucket, profile.getProfilePicturePath(), "profile-pictures", profile.getId())
                    .ifPresent(newPath -> {
                        profile.setProfilePicturePath(newPath);
                        profile.setUpdatedAt(LocalDateTime.now());
                        updatedProfiles.add(profile);
                    });
        }

        if (!updatedProfiles.isEmpty()) {
            jobSeekerProfileRepository.saveAll(updatedProfiles);
            logger.info(
                    "Repointed {} job seeker profile picture(s) from the retired profile-pictures GridFS bucket",
                    updatedProfiles.size());
        }
    }

    private boolean isLegacyObjectIdPath(String path) {
        return path != null && !path.isBlank() && !path.contains("/") && ObjectId.isValid(path);
    }

    private Optional<String> replaceLegacyPath(String currentPath, Map<String, String> migratedPaths) {
        if (currentPath == null || currentPath.isBlank()) {
            return Optional.empty();
        }

        String normalizedPath = currentPath.trim().replace('\\', '/');
        while (normalizedPath.startsWith("/")) {
            normalizedPath = normalizedPath.substring(1);
        }
        return Optional.ofNullable(migratedPaths.get(normalizedPath));
    }
}
