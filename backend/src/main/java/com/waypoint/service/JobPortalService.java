package com.waypoint.service;

import com.waypoint.dto.job.JobSeekerApplicationResponse;
import com.waypoint.dto.job.JobApplicationResponse;
import com.waypoint.dto.job.JobDetailResponse;
import com.waypoint.dto.job.JobListResponse;
import com.waypoint.dto.job.JobSummaryResponse;
import com.waypoint.common.LocationSearchNormalizer;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Job;
import com.waypoint.entity.JobApplication;
import com.waypoint.entity.User;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import com.waypoint.exception
        .BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.service.geocoding.GeoCoordinates;
import com.waypoint.service.geocoding.GeocodingService;
import com.waypoint.util.GeoDistanceUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.waypoint.util.DisplayTitleFormatter.format;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobPortalService {

    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final ResumeStorageService resumeStorageService;
    private final GeocodingService geocodingService;

    public JobListResponse getOpenJobs(
            String title,
            String location,
            EmploymentType employmentType,
            WorkMode workMode,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            LocalDate postedAfter,
            BigDecimal lat,
            BigDecimal lng,
            Integer maxDistanceMiles,
            int page,
            int size,
            String userId) {

        return getOpenJobs(title, location, employmentType, workMode, salaryMin, salaryMax,
                null, postedAfter, lat, lng, maxDistanceMiles, page, size, userId);
    }

    public JobListResponse getOpenJobs(
            String title,
            String location,
            EmploymentType employmentType,
            WorkMode workMode,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            SalaryRange salaryRange,
            LocalDate postedAfter,
            int page,
            int size,
            String userId) {

        return getOpenJobs(title, location, employmentType, workMode, salaryMin, salaryMax,
                salaryRange, postedAfter, null, null, null, page, size, userId);
    }

    private JobListResponse getOpenJobs(
            String title,
            String location,
            EmploymentType employmentType,
            WorkMode workMode,
            BigDecimal salaryMin,
            BigDecimal salaryMax,
            SalaryRange salaryRange,
            LocalDate postedAfter,
            BigDecimal lat,
            BigDecimal lng,
            Integer maxDistanceMiles,
            int page,
            int size,
            String userId) {

        if (page < 0) {
            throw new BusinessException("Page must be 0 or greater");
        }

        if (size < 1 || size > 50) {
            throw new BusinessException("Size must be between 1 and 50");
        }

        if (salaryMin != null && salaryMin.signum() < 0) {
            throw new BusinessException("Minimum salary cannot be negative");
        }

        if (salaryMax != null && salaryMax.signum() < 0) {
            throw new BusinessException("Maximum salary cannot be negative");
        }

        if (salaryMin != null && salaryMax != null && salaryMin.compareTo(salaryMax) > 0) {
            throw new BusinessException("Minimum salary cannot be greater than maximum salary");
        }

        LocalDateTime postedAfterCutoff = postedAfter != null ? postedAfter.atStartOfDay() : null;
        String normalizedLocation = LocationSearchNormalizer.normalize(location);
        DistanceContext distanceContext = resolveDistanceContext(location, lat, lng, maxDistanceMiles);

        List<Job> filteredJobs = jobRepository.findByStatus(JobStatus.OPEN).stream()
                .filter(job -> title == null || title.isBlank()
                        || (job.getTitle() != null && job.getTitle().toLowerCase().contains(title.toLowerCase())))
                .filter(job -> distanceContext.skipLocationTextFilter() || normalizedLocation.isBlank()
                        || (job.getLocation() != null
                        && LocationSearchNormalizer.normalize(job.getLocation()).contains(normalizedLocation)))
                .filter(job -> employmentType == null || employmentType == job.getEmploymentType())
                .filter(job -> workMode == null || workMode == job.getWorkMode())
                .filter(job -> salaryMin == null || job.getSalaryMax() == null || job.getSalaryMax().compareTo(salaryMin) >= 0)
                .filter(job -> salaryMax == null || job.getSalaryMin() == null || job.getSalaryMin().compareTo(salaryMax) <= 0)
                .filter(job -> salaryRange == null || salaryRange == job.getSalaryRange())
                .filter(job -> postedAfterCutoff == null
                        || (job.getCreatedAt() != null && !job.getCreatedAt().isBefore(postedAfterCutoff)))
                .filter(job -> isWithinDistance(job, distanceContext.lat(), distanceContext.lng(), maxDistanceMiles))
                .sorted(Comparator.comparing(Job::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        filteredJobs = applyClosestFirstOrdering(filteredJobs, distanceContext.lat(), distanceContext.lng());

        // Jobs the jobSeeker has already applied to are excluded from browsing -
        // they're surfaced instead on the dedicated "My applications" page.
        // Filtered out before pagination so totalElements/totalPages stay accurate.
        Set<String> appliedJobIds = resolveAppliedJobIds(
                userId, filteredJobs.stream().map(Job::getId).collect(Collectors.toList()));
        filteredJobs.removeIf(job -> appliedJobIds.contains(job.getId()));

        long totalElements = filteredJobs.size();
        int totalPages = totalElements == 0 ? 0 : (int) Math.ceil((double) totalElements / size);

        int fromIndex = Math.min(page * size, filteredJobs.size());
        int toIndex = Math.min(fromIndex + size, filteredJobs.size());

        List<Job> pageOfJobs = filteredJobs.subList(fromIndex, toIndex);

        List<JobSummaryResponse> content = pageOfJobs.stream()
                .map(job -> toSummaryResponse(job, false))
                .collect(Collectors.toList());

        return JobListResponse.builder()
                .content(content)
                .page(page)
                .size(size)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .build();
    }

    /**
     * No distance filter is active unless lat/lng/maxDistanceMiles are all
     * supplied (mirrors the frontend's "Anywhere"/no-selection guard) - in
     * that case every job passes through untouched. When active, remote jobs
     * always pass (no commute to measure), jobs without coordinates are
     * excluded, and everything else is measured against maxDistanceMiles.
     */
    private boolean isWithinDistance(Job job, BigDecimal lat, BigDecimal lng, Integer maxDistanceMiles) {
        if (lat == null || lng == null || maxDistanceMiles == null) {
            return true;
        }
        if (job.getWorkMode() == WorkMode.REMOTE) {
            return true;
        }
        if (job.getLatitude() == null || job.getLongitude() == null) {
            return false;
        }
        return GeoDistanceUtils.haversineMiles(lat, lng, job.getLatitude(), job.getLongitude()) <= maxDistanceMiles;
    }

    /**
     * Reorders jobs with a known distance (on-site/hybrid, geocoded) closest
     * first, while remote jobs and any without coordinates keep their
     * original date-descending position instead of being pulled to either
     * end - they're simply not distance-sortable, so they stay wherever the
     * date-based query already put them. Mirrors EventService's identically
     * named method.
     */
    private List<Job> applyClosestFirstOrdering(List<Job> jobs, BigDecimal centerLat, BigDecimal centerLng) {
        if (centerLat == null || centerLng == null) {
            return jobs;
        }

        List<Integer> sortableIndices = new ArrayList<>();
        List<Job> sortableJobs = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            Job job = jobs.get(i);
            if (job.getWorkMode() != WorkMode.REMOTE
                    && job.getLatitude() != null && job.getLongitude() != null) {
                sortableIndices.add(i);
                sortableJobs.add(job);
            }
        }

        if (sortableJobs.isEmpty()) {
            return jobs;
        }

        sortableJobs.sort(Comparator.comparingDouble(job ->
                GeoDistanceUtils.haversineMiles(centerLat, centerLng, job.getLatitude(), job.getLongitude())));

        List<Job> result = new ArrayList<>(jobs);
        for (int i = 0; i < sortableIndices.size(); i++) {
            result.set(sortableIndices.get(i), sortableJobs.get(i));
        }
        return result;
    }

    /**
     * When a distance filter and a city are both supplied, the city becomes
     * the radius center and fully replaces the plain-text location filter -
     * a job's location no longer needs to literally contain the searched
     * city text, it only needs to be within range of it (e.g. "Irving" no
     * longer wrongly excludes a nearby Plano job). Falls back to the
     * job seeker's GPS coordinates, with the original text filter still
     * active, whenever no city is given or the given city can't be
     * geocoded.
     */
    private DistanceContext resolveDistanceContext(
            String location, BigDecimal lat, BigDecimal lng, Integer maxDistanceMiles) {
        if (maxDistanceMiles != null && location != null && !location.isBlank()) {
            Optional<GeoCoordinates> cityCoordinates = geocodingService.geocode(location);
            if (cityCoordinates.isPresent()) {
                return new DistanceContext(cityCoordinates.get().latitude(), cityCoordinates.get().longitude(), true);
            }
        }
        return new DistanceContext(lat, lng, false);
    }

    private record DistanceContext(BigDecimal lat, BigDecimal lng, boolean skipLocationTextFilter) {
    }

    public JobDetailResponse getJobById(String jobId, String userId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found"));

        if (job.getStatus() != JobStatus.OPEN) {
            throw new ResourceNotFoundException("Job not found");
        }

        EmployerProfile employerProfile = employerProfileRepository.findById(job.getEmployerProfileId())
                .orElseThrow(() -> new ResourceNotFoundException("Employer profile not found"));

        boolean hasApplied = resolveAppliedJobIds(userId, List.of(jobId)).contains(jobId);

        return toDetailResponse(job, employerProfile.getCompanyName(), hasApplied);
    }

    /**
     * Anonymous browsing (no/invalid token) always resolves to "not applied" -
     * only an authenticated jobSeeker with a profile can have applications.
     */
    private Set<String> resolveAppliedJobIds(String userId, List<String> jobIds) {
        if (userId == null || jobIds.isEmpty()) {
            return Collections.emptySet();
        }

        return jobSeekerProfileRepository.findByUserId(userId)
                .map(profile -> jobApplicationRepository
                        .findByJobSeekerProfileIdAndJobIdIn(profile.getId(), jobIds).stream()
                        .map(JobApplication::getJobId)
                        .collect(Collectors.toSet()))
                .orElse(Collections.emptySet());
    }

    public JobApplicationResponse applyForJob(String jobId, String userId) {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("JobSeeker profile not found"));

        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found"));

        if (job.getStatus() != JobStatus.OPEN) {
            throw new BusinessException("This job is no longer accepting applications");
        }

        LocalDateTime now = LocalDateTime.now();

        if (job.getApplicationDeadline() != null && job.getApplicationDeadline().isBefore(now)) {
            throw new BusinessException("The application deadline for this job has passed");
        }

        String resumePath = storedResumePath(jobSeekerProfile);
        if (resumePath == null || resumePath.isBlank()) {
            throw new BusinessException("Please upload a resume to your profile before applying");
        }

        if (!resumePath.equals(jobSeekerProfile.getResumePath())) {
            jobSeekerProfile.setResumePath(resumePath);
            jobSeekerProfile.setUpdatedAt(now);
            jobSeekerProfileRepository.save(jobSeekerProfile);
        }

        if (jobApplicationRepository.existsByJobIdAndJobSeekerProfileId(jobId, jobSeekerProfile.getId())) {
            throw new BusinessException("You have already applied to this job");
        }

        JobApplication jobApplication = JobApplication.builder()
                .jobId(jobId)
                .jobSeekerProfileId(jobSeekerProfile.getId())
                .resumePath(resumePath)
                .status(ApplicationStatus.APPLIED)
                .appliedAt(now)
                .updatedAt(now)
                .build();

        JobApplication savedApplication;
        try {
            savedApplication = jobApplicationRepository.save(jobApplication);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException("You have already applied to this job");
        }

        sendApplicationSubmittedNotification(jobSeekerProfile, job, savedApplication);

        return toApplicationResponse(savedApplication);
    }

    private String storedResumePath(JobSeekerProfile profile) {
        String recordedPath = profile.getResumePath();
        if (recordedPath != null && !recordedPath.isBlank()) {
            if (isUsableApplicationResume(recordedPath)) {
                return recordedPath;
            }

            Optional<String> repairedPath = resumeStorageService.findStoredPathByFileName(
                    profile.getId(), fileNameOf(recordedPath));
            return repairedPath == null
                    ? null
                    : repairedPath.filter(this::isUsableApplicationResume).orElse(null);
        }

        Optional<String> latestResume = resumeStorageService.findLatestStoredPath(profile.getId());
        if (latestResume != null && latestResume.isPresent()) {
            String latestPath = latestResume.get();
            if (isUsableApplicationResume(latestPath)) {
                return latestPath;
            }
        }

        if (profile.getUserId() == null || profile.getUserId().isBlank()) {
            return null;
        }

        Optional<String> latestUserResume = resumeStorageService.findLatestStoredPath(profile.getUserId());
        if (latestUserResume == null || latestUserResume.isEmpty()) {
            return null;
        }

        String latestPath = latestUserResume.get();
        return isUsableApplicationResume(latestPath) ? latestPath : null;
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

    private void sendApplicationSubmittedNotification(
            JobSeekerProfile jobSeekerProfile, Job job, JobApplication savedApplication) {
        try {
            String jobSeekerEmail = userRepository.findById(jobSeekerProfile.getUserId())
                    .map(User::getEmail)
                    .orElse(null);

            EmployerProfile employerProfile = employerProfileRepository.findById(job.getEmployerProfileId())
                    .orElse(null);
            String employerUserId = employerProfile != null ? employerProfile.getUserId() : null;
            String employerEmail = employerProfile != null
                    ? userRepository.findById(employerProfile.getUserId()).map(User::getEmail).orElse(null)
                    : null;
            String companyName = employerProfile != null ? employerProfile.getCompanyName() : null;

            notificationService.notifyApplicationSubmitted(
                    jobSeekerProfile.getUserId(),
                    jobSeekerEmail,
                    employerUserId,
                    employerEmail,
                    savedApplication.getId(),
                    job.getId(),
                    format(job.getTitle()),
                    companyName,
                    applicantFullName(jobSeekerProfile));
        } catch (RuntimeException exception) {
            log.warn("Failed to prepare application-submitted notification [exceptionType={}]",
                    exception.getClass().getSimpleName());
        }
    }

    private String applicantFullName(JobSeekerProfile jobSeekerProfile) {
        String first = jobSeekerProfile.getFirstName() != null ? jobSeekerProfile.getFirstName().trim() : "";
        String last = jobSeekerProfile.getLastName() != null ? jobSeekerProfile.getLastName().trim() : "";
        return (first + " " + last).trim();
    }

    public List<JobSeekerApplicationResponse> getMyApplications(String userId) {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("JobSeeker profile not found"));

        List<JobApplication> applications =
                jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc(jobSeekerProfile.getId());

        List<String> jobIds = applications.stream().map(JobApplication::getJobId).distinct().toList();
        Map<String, Job> jobsById = jobRepository.findAllById(jobIds).stream()
                .collect(Collectors.toMap(Job::getId, job -> job));

        List<String> employerProfileIds = jobsById.values().stream()
                .map(Job::getEmployerProfileId).distinct().toList();
        Map<String, String> companyNamesByEmployerProfileId = employerProfileRepository.findAllById(employerProfileIds).stream()
                .collect(Collectors.toMap(EmployerProfile::getId, EmployerProfile::getCompanyName));

        return applications.stream()
                .map(application -> {
                    Job job = jobsById.get(application.getJobId());
                    String companyName = job != null
                            ? companyNamesByEmployerProfileId.get(job.getEmployerProfileId())
                            : null;
                    return toJobSeekerApplicationResponse(application, job, companyName);
                })
                .toList();
    }

    private JobSeekerApplicationResponse toJobSeekerApplicationResponse(
            JobApplication application, Job job, String companyName) {
        return JobSeekerApplicationResponse.builder()
                .id(application.getId())
                .jobId(application.getJobId())
                .jobTitle(job != null ? format(job.getTitle()) : null)
                .companyName(companyName)
                .location(job != null ? job.getLocation() : null)
                .status(application.getStatus())
                .appliedAt(application.getAppliedAt())
                .build();
    }

    private JobSummaryResponse toSummaryResponse(Job job, boolean hasApplied) {
        return JobSummaryResponse.builder()
                .id(job.getId())
                .jobCode(job.getJobCode())
                .title(format(job.getTitle()))
                .location(job.getLocation())
                .workMode(job.getWorkMode())
                .employmentType(job.getEmploymentType())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .salaryRange(job.getSalaryRange())
                .applicationDeadline(job.getApplicationDeadline())
                .createdAt(job.getCreatedAt())
                .requiredSkills(job.getRequiredSkills())
                .hasApplied(hasApplied)
                .build();
    }

    private JobDetailResponse toDetailResponse(Job job, String companyName, boolean hasApplied) {
        return JobDetailResponse.builder()
                .id(job.getId())
                .jobCode(job.getJobCode())
                .title(format(job.getTitle()))
                .description(job.getDescription())
                .location(job.getLocation())
                .workMode(job.getWorkMode())
                .employmentType(job.getEmploymentType())
                .requiredSkills(job.getRequiredSkills())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .salaryRange(job.getSalaryRange())
                .applicationDeadline(job.getApplicationDeadline())
                .status(job.getStatus())
                .createdAt(job.getCreatedAt())
                .companyName(companyName)
                .hasApplied(hasApplied)
                .build();
    }

    private JobApplicationResponse toApplicationResponse(JobApplication jobApplication) {
        return JobApplicationResponse.builder()
                .id(jobApplication.getId())
                .jobId(jobApplication.getJobId())
                .jobSeekerProfileId(jobApplication.getJobSeekerProfileId())
                .status(jobApplication.getStatus())
                .appliedAt(jobApplication.getAppliedAt())
                .build();
    }
}
