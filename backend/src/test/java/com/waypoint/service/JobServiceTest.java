package com.waypoint.service;

import com.waypoint.dto.job.CreateJobRequest;
import com.waypoint.dto.job.EmployerJobResponse;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Job;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.ExperienceLevel;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private JobApplicationRepository jobApplicationRepository;

    @InjectMocks
    private JobService jobService;

    @Test
    void createJobMapsEmployerAndSavesOpenPosting() {
        EmployerProfile employerProfile = completeEmployerProfile();
        CreateJobRequest request = validRequest();
        request.setJobCode("JOB-ABCDEF12");

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(employerProfile));
        when(jobRepository.save(any(Job.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployerJobResponse savedJob = jobService.createJob("employer-user-1", request);

        assertEquals("Frontend Engineer", savedJob.getTitle());
        assertEquals("Chicago, Illinois", savedJob.getLocation());
        assertEquals(ExperienceLevel.SENIOR_LEVEL, savedJob.getExperienceLevel());
        assertEquals(JobStatus.OPEN, savedJob.getStatus());
        assertEquals(List.of("React", "REST APIs"), savedJob.getRequiredSkills());
        assertEquals("JOB-ABCDEF12", savedJob.getJobCode());
        assertEquals(SalaryRange.RANGE_50K_100K, savedJob.getSalaryRange());
        assertNotNull(savedJob.getCreatedAt());
        verify(jobRepository).save(any(Job.class));
    }

    @Test
    void createJobRejectsSalaryMaximumBelowMinimum() {
        EmployerProfile employerProfile = completeEmployerProfile();
        CreateJobRequest request = validRequest();
        request.setSalaryMin(new BigDecimal("90000"));
        request.setSalaryMax(new BigDecimal("70000"));

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(employerProfile));

        assertThrows(BusinessException.class,
                () -> jobService.createJob("employer-user-1", request));
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void createJobRequiresCompleteEmployerProfile() {
        EmployerProfile incompleteProfile = EmployerProfile.builder()
                .id("employer-profile-1")
                .userId("employer-user-1")
                .companyName("Waypoint Labs")
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(incompleteProfile));

        assertThrows(BusinessException.class,
                () -> jobService.createJob("employer-user-1", validRequest()));
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void updateJobRejectsClosedPosting() {
        EmployerProfile employerProfile = completeEmployerProfile();
        Job closedJob = Job.builder()
                .id("job-1")
                .employerProfileId(employerProfile.getId())
                .status(JobStatus.CLOSED)
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(employerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(closedJob));

        assertThrows(BusinessException.class,
                () -> jobService.updateJob("employer-user-1", "job-1", validRequest()));
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void closeJobRejectsPostingOwnedByAnotherEmployer() {
        EmployerProfile employerProfile = completeEmployerProfile();
        Job anotherEmployersJob = Job.builder()
                .id("job-1")
                .employerProfileId("another-employer-profile")
                .status(JobStatus.OPEN)
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(employerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(anotherEmployersJob));

        assertThrows(RuntimeException.class,
                () -> jobService.closeJob("employer-user-1", "job-1"));
        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    void closeAndReopenJobFollowLifecycle() {
        EmployerProfile employerProfile = completeEmployerProfile();
        Job job = Job.builder()
                .id("job-1")
                .jobCode("JOB-1001")
                .employerProfileId(employerProfile.getId())
                .status(JobStatus.OPEN)
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(employerProfile));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        EmployerJobResponse closed = jobService.closeJob("employer-user-1", "job-1");
        assertEquals(JobStatus.CLOSED, closed.getStatus());

        EmployerJobResponse reopened = jobService.reopenJob("employer-user-1", "job-1");
        assertEquals(JobStatus.OPEN, reopened.getStatus());
    }

    private CreateJobRequest validRequest() {
        CreateJobRequest request = new CreateJobRequest();
        request.setTitle("Frontend Engineer");
        request.setWorkMode(WorkMode.HYBRID);
        request.setLocation("Chicago, Illinois");
        request.setEmploymentType(EmploymentType.FULL_TIME);
        request.setExperienceLevel(ExperienceLevel.SENIOR_LEVEL);
        request.setRequiredSkills(List.of("React", "REST APIs"));
        request.setSalaryMin(new BigDecimal("70000"));
        request.setSalaryMax(new BigDecimal("95000"));
        request.setSalaryRange(SalaryRange.RANGE_50K_100K);
        request.setDescription("Build accessible logistics products with our platform team.");
        request.setApplicationDeadline(LocalDate.now().plusDays(30));
        request.setResumeRequired(true);
        return request;
    }

    private EmployerProfile completeEmployerProfile() {
        return EmployerProfile.builder()
                .id("employer-profile-1")
                .userId("employer-user-1")
                .companyName("Waypoint Labs")
                .companyAddress("100 Main Street")
                .phoneNumber("555-0100")
                .companySize(CompanySize.MEDIUM)
                .build();
    }
}
