package com.waypoint.service;

import com.waypoint.dto.employer.EmployerApplicantDetailResponse;
import com.waypoint.dto.employer.EmployerApplicantSummaryResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Job;
import com.waypoint.entity.JobApplication;
import com.waypoint.entity.User;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.waypoint.util.DisplayTitleFormatter.format;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmployerApplicantService {

    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final ResumeStorageService resumeStorageService;
    private final NotificationService notificationService;

    public List<EmployerApplicantSummaryResponse> listApplicants(String employerUserId, String jobId) {
        Job job = findOwnedJob(employerUserId, jobId);

        return jobApplicationRepository.findByJobIdOrderByAppliedAtDesc(job.getId())
                .stream()
                .map(this::toSummary)
                .toList();
    }

    public EmployerApplicantDetailResponse getApplicant(
            String employerUserId, String jobId, String applicationId) {

        Job job = findOwnedJob(employerUserId, jobId);
        JobApplication application = findApplication(job, applicationId);

        JobSeekerProfile profile = findJobSeekerProfile(application.getJobSeekerProfileId());
        User user = findUser(profile.getUserId());
        ResumeResolution resume = resolveResume(application, profile);

        return EmployerApplicantDetailResponse.builder()
                .applicationId(application.getId())
                .jobSeekerProfileId(profile.getId())
                .fullName(fullName(profile))
                .email(user.getEmail())
                .phoneNumber(profile.getPhoneNumber())
                .address(profile.getAddress())
                .skills(profile.getSkills())
                .preferredJobRoles(profile.getPreferredJobRoles())
                .preferredLocations(profile.getPreferredLocations())
                .expectedSalaryMin(profile.getExpectedSalaryMin())
                .expectedSalaryMax(profile.getExpectedSalaryMax())
                .preferredEmploymentType(profile.getPreferredEmploymentType())
                .resumeFileName(resumeFileName(resume))
                .hasResume(resume.hasResume())
                .resumeRecordExists(resume.hasRecord())
                .status(application.getStatus())
                .appliedAt(application.getAppliedAt())
                .updatedAt(application.getUpdatedAt())
                .build();
    }

    public EmployerApplicantDetailResponse updateApplicationStatus(
            String employerUserId,
            String jobId,
            String applicationId,
            ApplicationStatus newStatus) {

        Job job = findOwnedJob(employerUserId, jobId);
        JobApplication application = findApplication(job, applicationId);
        ApplicationStatus previousStatus = application.getStatus();
        application.setStatus(newStatus);
        application.setUpdatedAt(LocalDateTime.now());
        JobApplication savedApplication = jobApplicationRepository.save(application);

        if (previousStatus != newStatus) {
            sendApplicationStatusUpdatedNotification(job, savedApplication);
        }

        return getApplicant(employerUserId, jobId, applicationId);
    }

    private void sendApplicationStatusUpdatedNotification(Job job, JobApplication savedApplication) {
        try {
            JobSeekerProfile profile = findJobSeekerProfile(savedApplication.getJobSeekerProfileId());
            User user = findUser(profile.getUserId());

            notificationService.notifyApplicationStatusUpdated(
                    profile.getUserId(),
                    user.getEmail(),
                    savedApplication.getId(),
                    format(job.getTitle()),
                    savedApplication.getStatus());
        } catch (RuntimeException exception) {
            log.warn("Failed to prepare application-status-updated notification [exceptionType={}]",
                    exception.getClass().getSimpleName());
        }
    }

    public ResumeStorageService.ResumeDocument getApplicantResume(
            String employerUserId, String jobId, String applicationId) {

        Job job = findOwnedJob(employerUserId, jobId);
        JobApplication application = findApplication(job, applicationId);
        JobSeekerProfile profile = findJobSeekerProfile(application.getJobSeekerProfileId());

        return resumeStorageService.load(resolveResume(application, profile).availablePath());
    }

    private EmployerApplicantSummaryResponse toSummary(JobApplication application) {
        JobSeekerProfile profile = findJobSeekerProfile(application.getJobSeekerProfileId());
        User user = findUser(profile.getUserId());
        ResumeResolution resume = resolveResume(application, profile);

        return EmployerApplicantSummaryResponse.builder()
                .applicationId(application.getId())
                .jobSeekerProfileId(profile.getId())
                .fullName(fullName(profile))
                .email(user.getEmail())
                .hasResume(resume.hasResume())
                .resumeRecordExists(resume.hasRecord())
                .status(application.getStatus())
                .appliedAt(application.getAppliedAt())
                .build();
    }

    private Job findOwnedJob(String employerUserId, String jobId) {
        EmployerProfile employerProfile = employerProfileRepository.findByUserId(employerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Employer profile was not found"));

        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job posting was not found"));

        if (!Objects.equals(employerProfile.getId(), job.getEmployerProfileId())) {
            throw new ResourceNotFoundException("Job posting was not found");
        }

        return job;
    }

    private JobSeekerProfile findJobSeekerProfile(String jobSeekerProfileId) {
        return jobSeekerProfileRepository.findById(jobSeekerProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Applicant profile was not found"));
    }

    private JobApplication findApplication(Job job, String applicationId) {
        return jobApplicationRepository.findByIdAndJobId(applicationId, job.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Applicant application was not found"));
    }

    private User findUser(String userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Applicant account was not found"));
    }

    private String fullName(JobSeekerProfile profile) {
        return Stream.of(profile.getFirstName(), profile.getLastName())
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.joining(" "));
    }

    private String resumeFileName(ResumeResolution resume) {
        String resumePath = resume.hasResume() ? resume.availablePath() : resume.recordedPath();

        if (isBlank(resumePath)) {
            return null;
        }

        String normalizedPath = resumePath.replace('\\', '/');
        int queryStart = normalizedPath.indexOf('?');
        if (queryStart >= 0) {
            normalizedPath = normalizedPath.substring(0, queryStart);
        }

        int lastSlash = normalizedPath.lastIndexOf('/');
        String fileName = lastSlash >= 0 ? normalizedPath.substring(lastSlash + 1) : normalizedPath;
        return isBlank(fileName) ? "Resume" : fileName;
    }

    private ResumeResolution resolveResume(JobApplication application, JobSeekerProfile profile) {
        // An application must use the resume that was recorded when that
        // application was submitted. Never replace a missing application
        // resume with another file found in the job seeker's directory: that
        // can show an unrelated older upload to the employer.
        if (!isBlank(application.getResumePath())) {
            if (resumeStorageService.exists(application.getResumePath())) {
                return new ResumeResolution(application.getResumePath(), application.getResumePath());
            }

            String recordedFileName = resumeFileName(new ResumeResolution(null, application.getResumePath()));
            Optional<String> repairedPath = resumeStorageService.findStoredPathByFileName(
                    profile.getId(), recordedFileName);
            if (repairedPath.isPresent()) {
                application.setResumePath(repairedPath.get());
                application.setUpdatedAt(LocalDateTime.now());
                jobApplicationRepository.save(application);
                return new ResumeResolution(repairedPath.get(), repairedPath.get());
            }

            return new ResumeResolution(null, application.getResumePath());
        }

        if (!isBlank(profile.getResumePath())) {
            if (resumeStorageService.exists(profile.getResumePath())) {
                return new ResumeResolution(profile.getResumePath(), profile.getResumePath());
            }

            return new ResumeResolution(null, profile.getResumePath());
        }

        Optional<String> latestResume = resumeStorageService.findLatestStoredPath(profile.getId());
        if (latestResume.isEmpty() && !isBlank(profile.getUserId())) {
            latestResume = resumeStorageService.findLatestStoredPath(profile.getUserId());
        }

        if (latestResume.isPresent()) {
            return new ResumeResolution(latestResume.get(), latestResume.get());
        }

        return new ResumeResolution(null, null);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record ResumeResolution(String availablePath, String recordedPath) {

        private boolean hasResume() {
            return availablePath != null && !availablePath.isBlank();
        }

        private boolean hasRecord() {
            return hasResume() || (recordedPath != null && !recordedPath.isBlank());
        }
    }
}
