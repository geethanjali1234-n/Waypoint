package com.waypoint.service;

import com.waypoint.dto.job.CreateJobRequest;
import com.waypoint.dto.job.EmployerJobResponse;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Job;
import com.waypoint.enums.JobStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static com.waypoint.util.DisplayTitleFormatter.format;

@Service
@RequiredArgsConstructor
public class JobService {

    private final JobRepository jobRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final JobApplicationRepository jobApplicationRepository;

    public List<EmployerJobResponse> getEmployerJobs(String employerUserId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        return jobRepository.findByEmployerProfileIdOrderByCreatedAtDesc(employerProfile.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public EmployerJobResponse getEmployerJob(String employerUserId, String jobId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        return toResponse(findOwnedJob(jobId, employerProfile.getId()));
    }

    public String generateAvailableJobCode(String employerUserId) {
        findEmployerProfile(employerUserId);
        return generateJobCode();
    }

    public EmployerJobResponse createJob(String employerUserId, CreateJobRequest request) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        validateEmployerProfile(employerProfile);
        validateJobRequest(request);

        LocalDateTime now = LocalDateTime.now();
        Job job = Job.builder()
                .employerProfileId(employerProfile.getId())
                .jobCode(resolveJobCode(request.getJobCode()))
                .status(JobStatus.OPEN)
                .createdAt(now)
                .updatedAt(now)
                .build();
        applyRequest(job, request);

        return toResponse(jobRepository.save(job));
    }

    public EmployerJobResponse updateJob(
            String employerUserId, String jobId, CreateJobRequest request) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        Job job = findOwnedJob(jobId, employerProfile.getId());

        if (job.getStatus() != JobStatus.OPEN) {
            throw new BusinessException("Only active job postings can be edited");
        }

        validateJobRequest(request);
        applyRequest(job, request);
        job.setUpdatedAt(LocalDateTime.now());
        return toResponse(jobRepository.save(job));
    }

    public EmployerJobResponse closeJob(String employerUserId, String jobId) {
        return changeStatus(employerUserId, jobId, JobStatus.OPEN, JobStatus.CLOSED);
    }

    public EmployerJobResponse reopenJob(String employerUserId, String jobId) {
        return changeStatus(employerUserId, jobId, JobStatus.CLOSED, JobStatus.OPEN);
    }

    private EmployerProfile findEmployerProfile(String employerUserId) {
        return employerProfileRepository.findByUserId(employerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Employer profile was not found"));
    }

    private Job findOwnedJob(String jobId, String employerProfileId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job posting was not found"));
        if (!employerProfileId.equals(job.getEmployerProfileId())) {
            throw new ResourceNotFoundException("Job posting was not found");
        }
        return job;
    }

    private EmployerJobResponse changeStatus(
            String employerUserId, String jobId, JobStatus requiredStatus, JobStatus newStatus) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        Job job = findOwnedJob(jobId, employerProfile.getId());

        if (job.getStatus() != requiredStatus) {
            throw new BusinessException(requiredStatus == JobStatus.OPEN
                    ? "Only active job postings can be closed"
                    : "Only closed job postings can be reopened");
        }

        job.setStatus(newStatus);
        job.setUpdatedAt(LocalDateTime.now());
        return toResponse(jobRepository.save(job));
    }

    private void validateEmployerProfile(EmployerProfile profile) {
        if (isBlank(profile.getCompanyName())
                || isBlank(profile.getCompanyAddress())
                || isBlank(profile.getPhoneNumber())
                || profile.getCompanySize() == null) {
            throw new BusinessException(
                    "Complete the required employer profile information before creating a job posting");
        }
    }

    private void validateJobRequest(CreateJobRequest request) {
        if (request.getApplicationDeadline() == null) {
            throw new BusinessException("Application deadline is required");
        }
        if (request.getApplicationDeadline().isBefore(LocalDate.now())) {
            throw new BusinessException("Application deadline cannot be in the past");
        }

        if (request.getSalaryMin() != null
                && request.getSalaryMax() != null
                && request.getSalaryMax().compareTo(request.getSalaryMin()) < 0) {
            throw new BusinessException("Maximum salary must be greater than or equal to minimum salary");
        }

        List<String> normalizedSkills = normalizeSkills(request.getRequiredSkills());
        if (normalizedSkills.isEmpty()) {
            throw new BusinessException("At least one required skill is required");
        }
    }

    private void applyRequest(Job job, CreateJobRequest request) {
        job.setTitle(format(request.getTitle()));
        job.setDescription(request.getDescription().trim());
        job.setLocation(request.getLocation().trim());
        job.setWorkMode(request.getWorkMode());
        job.setEmploymentType(request.getEmploymentType());
        job.setExperienceLevel(request.getExperienceLevel());
        job.setRequiredSkills(normalizeSkills(request.getRequiredSkills()));
        job.setSalaryMin(request.getSalaryMin());
        job.setSalaryMax(request.getSalaryMax());
        job.setSalaryRange(request.getSalaryRange());
        job.setApplicationDeadline(request.getApplicationDeadline().atTime(LocalTime.MAX));
        job.setResumeRequired(request.getResumeRequired());
    }

    private List<String> normalizeSkills(List<String> skills) {
        return skills.stream()
                .map(String::trim)
                .filter(skill -> !skill.isBlank())
                .distinct()
                .toList();
    }

    private EmployerJobResponse toResponse(Job job) {
        return EmployerJobResponse.builder()
                .id(job.getId())
                .jobCode(job.getJobCode())
                .title(format(job.getTitle()))
                .department(job.getDepartment())
                .description(job.getDescription())
                .location(job.getLocation())
                .workMode(job.getWorkMode())
                .employmentType(job.getEmploymentType())
                .experienceLevel(job.getExperienceLevel())
                .requiredSkills(job.getRequiredSkills())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .salaryRange(job.getSalaryRange())
                .applicationDeadline(job.getApplicationDeadline())
                .resumeRequired(job.getResumeRequired())
                .status(job.getStatus())
                .applicationCount(job.getId() == null ? 0 : jobApplicationRepository.countByJobId(job.getId()))
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String generateJobCode() {
        String jobCode;
        do {
            jobCode = "JOB-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } while (jobRepository.existsByJobCode(jobCode));
        return jobCode;
    }

    private String resolveJobCode(String requestedJobCode) {
        if (requestedJobCode == null || requestedJobCode.isBlank()) {
            return generateJobCode();
        }

        String normalizedJobCode = requestedJobCode.trim().toUpperCase();
        if (jobRepository.existsByJobCode(normalizedJobCode)) {
            throw new BusinessException("This Job ID is no longer available. Please generate a new one");
        }
        return normalizedJobCode;
    }
}
