package com.waypoint.service;

import com.waypoint.common.LocationSearchNormalizer;
import com.waypoint.dto.jobseeker.JobSeekerApplicationResponse;
import com.waypoint.dto.jobseeker.JobSeekerJobResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.Job;
import com.waypoint.entity.JobApplication;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.JobStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.waypoint.util.DisplayTitleFormatter.format;

@Service
@RequiredArgsConstructor
public class JobSeekerJobService {

    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final JobRepository jobRepository;
    private final ResumeStorageService resumeStorageService;

    public List<JobSeekerJobResponse> listOpenJobs(String jobSeekerUserId) {
        return listOpenJobs(jobSeekerUserId, null);
    }

    public List<JobSeekerJobResponse> listOpenJobs(String jobSeekerUserId, String search) {
        JobSeekerProfile profile = findJobSeekerProfile(jobSeekerUserId);
        boolean hasResume = !isBlank(storedResumePath(profile));
        Map<String, JobApplication> applicationsByJobId = jobApplicationRepository
                .findByJobSeekerProfileId(profile.getId())
                .stream()
                .collect(Collectors.toMap(JobApplication::getJobId, Function.identity(), (first, ignored) -> first));

        return jobRepository.findByStatusOrderByCreatedAtDesc(JobStatus.OPEN)
                .stream()
                .filter(job -> matchesSearch(job, search))
                .map(job -> toJobResponse(job, applicationsByJobId.get(job.getId()), hasResume))
                .toList();
    }

    public JobSeekerJobResponse getJob(String jobSeekerUserId, String jobId) {
        JobSeekerProfile profile = findJobSeekerProfile(jobSeekerUserId);
        Job job = findJob(jobId);
        JobApplication application = jobApplicationRepository
                .findByJobIdAndJobSeekerProfileId(job.getId(), profile.getId())
                .orElse(null);

        return toJobResponse(job, application, !isBlank(storedResumePath(profile)));
    }

    public JobSeekerApplicationResponse apply(String jobSeekerUserId, String jobId) {
        JobSeekerProfile profile = findJobSeekerProfile(jobSeekerUserId);
        Job job = findJob(jobId);

        if (job.getStatus() != JobStatus.OPEN) {
            throw new BusinessException("This job is no longer accepting applications");
        }

        if (job.getApplicationDeadline() != null
                && job.getApplicationDeadline().isBefore(LocalDateTime.now())) {
            throw new BusinessException("The application deadline for this job has passed");
        }

        if (jobApplicationRepository.findByJobIdAndJobSeekerProfileId(job.getId(), profile.getId()).isPresent()) {
            throw new BusinessException("You have already applied to this job");
        }

        String resumePath = storedResumePath(profile);
        boolean hasResume = !isBlank(resumePath);
        if (!Boolean.FALSE.equals(job.getResumeRequired()) && !hasResume) {
            throw new BusinessException("Upload your resume before applying to this job");
        }

        if (hasResume && !resumePath.equals(profile.getResumePath())) {
            profile.setResumePath(resumePath);
            profile.setUpdatedAt(LocalDateTime.now());
            jobSeekerProfileRepository.save(profile);
        }

        LocalDateTime now = LocalDateTime.now();
        JobApplication application = JobApplication.builder()
                .jobId(job.getId())
                .jobSeekerProfileId(profile.getId())
                .resumePath(hasResume ? resumePath : null)
                .status(ApplicationStatus.APPLIED)
                .appliedAt(now)
                .updatedAt(now)
                .build();

        JobApplication savedApplication = jobApplicationRepository.save(application);
        return JobSeekerApplicationResponse.builder()
                .applicationId(savedApplication.getId())
                .jobId(job.getId())
                .jobTitle(format(job.getTitle()))
                .status(savedApplication.getStatus())
                .appliedAt(savedApplication.getAppliedAt())
                .hasResume(hasResume)
                .build();
    }

    private JobSeekerJobResponse toJobResponse(Job job, JobApplication application, boolean hasResume) {
        return JobSeekerJobResponse.builder()
                .id(job.getId())
                .title(format(job.getTitle()))
                .description(job.getDescription())
                .location(job.getLocation())
                .workMode(job.getWorkMode())
                .employmentType(job.getEmploymentType())
                .experienceLevel(job.getExperienceLevel())
                .requiredSkills(job.getRequiredSkills())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .salaryRange(job.getSalaryRange())
                .createdAt(job.getCreatedAt())
                .applicationDeadline(job.getApplicationDeadline())
                .resumeRequired(job.getResumeRequired())
                .status(job.getStatus())
                .hasResume(hasResume)
                .hasApplied(application != null)
                .applicationStatus(application == null ? null : application.getStatus())
                .appliedAt(application == null ? null : application.getAppliedAt())
                .build();
    }

    private JobSeekerProfile findJobSeekerProfile(String jobSeekerUserId) {
        return jobSeekerProfileRepository.findByUserId(jobSeekerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("JobSeeker profile was not found"));
    }

    private Job findJob(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job posting was not found"));
    }

    private String storedResumePath(JobSeekerProfile profile) {
        String resumePath = profile.getResumePath();
        if (!isBlank(resumePath)) {
            // Prefer the recorded file, but repair it from the same job seeker's
            // exact shared file name when a legacy path is stale.
            if (isUsableApplicationResume(resumePath)) {
                return resumePath;
            }

            Optional<String> repairedPath = resumeStorageService.findStoredPathByFileName(
                    profile.getId(), fileNameOf(resumePath));
            return repairedPath == null
                    ? null
                    : repairedPath.filter(this::isUsableApplicationResume).orElse(null);
        }

        // Only use the legacy directory scan for records created before a
        // resume path was persisted at all.
        Optional<String> latestProfileResume = resumeStorageService.findLatestStoredPath(profile.getId());
        if (latestProfileResume != null && latestProfileResume.isPresent()
                && isUsableApplicationResume(latestProfileResume.get())) {
            return latestProfileResume.get();
        }

        if (isBlank(profile.getUserId())) {
            return null;
        }

        Optional<String> latestUserResume = resumeStorageService.findLatestStoredPath(profile.getUserId());
        return latestUserResume != null && latestUserResume.isPresent()
                && isUsableApplicationResume(latestUserResume.get())
                ? latestUserResume.get()
                : null;
    }

    private boolean isUsableApplicationResume(String storedPath) {
        if (!resumeStorageService.exists(storedPath)) {
            return false;
        }

        return !resumeStorageService.isSharedStorageEnabled()
                || resumeStorageService.isSharedStoragePath(storedPath);
    }

    private String fileNameOf(String storedPath) {
        String normalizedPath = storedPath.replace('\\', '/');
        int lastSlash = normalizedPath.lastIndexOf('/');
        return lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
    }

    private boolean matchesSearch(Job job, String search) {
        if (search == null || search.isBlank()) {
            return true;
        }

        String rawQuery = search.trim().toLowerCase(Locale.ROOT);
        String normalizedQuery = LocationSearchNormalizer.normalize(search);

        boolean matchesJobText = contains(job.getTitle(), rawQuery)
                || contains(job.getDescription(), rawQuery)
                || contains(job.getLocation(), rawQuery)
                || (job.getRequiredSkills() != null
                && job.getRequiredSkills().stream().anyMatch(skill -> contains(skill, rawQuery)));

        boolean matchesNormalizedLocation = !normalizedQuery.isBlank()
                && LocationSearchNormalizer.normalize(job.getLocation()).contains(normalizedQuery);

        return matchesJobText || matchesNormalizedLocation;
    }

    private boolean contains(String value, String query) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(query);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
