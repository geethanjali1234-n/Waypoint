package com.waypoint.service;

import com.waypoint.dto.jobseeker.JobSeekerApplicationResponse;
import com.waypoint.dto.jobseeker.JobSeekerJobResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.Job;
import com.waypoint.entity.JobApplication;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.ExperienceLevel;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import com.waypoint.exception.BusinessException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobSeekerJobServiceTest {

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private JobApplicationRepository jobApplicationRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ResumeStorageService resumeStorageService;

    @InjectMocks
    private JobSeekerJobService jobSeekerJobService;

    @Test
    void applyStoresApplicationForTheSelectedJobAndJobSeeker() {
        JobSeekerProfile profile = jobSeekerProfile();
        Job job = job();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobRepository.findById("python-job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByJobIdAndJobSeekerProfileId("python-job-1", "jobSeeker-profile-1"))
                .thenReturn(Optional.empty());
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/alex.pdf")).thenReturn(true);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenAnswer(invocation -> {
            JobApplication application = invocation.getArgument(0);
            application.setId("application-1");
            return application;
        });

        JobSeekerApplicationResponse response = jobSeekerJobService.apply("jobSeeker-user-1", "python-job-1");

        ArgumentCaptor<JobApplication> applicationCaptor = ArgumentCaptor.forClass(JobApplication.class);
        verify(jobApplicationRepository).save(applicationCaptor.capture());
        JobApplication savedApplication = applicationCaptor.getValue();

        assertEquals("application-1", response.getApplicationId());
        assertEquals("Python Developer", response.getJobTitle());
        assertEquals(ApplicationStatus.APPLIED, response.getStatus());
        assertTrue(response.isHasResume());
        assertEquals("python-job-1", savedApplication.getJobId());
        assertEquals("jobSeeker-profile-1", savedApplication.getJobSeekerProfileId());
        assertEquals("resumes/jobSeeker-profile-1/alex.pdf", savedApplication.getResumePath());
        assertEquals(ApplicationStatus.APPLIED, savedApplication.getStatus());
    }

    @Test
    void applyUsesTheSharedGridFsResumePathAcrossComputers() {
        JobSeekerProfile profile = jobSeekerProfile();
        String sharedResumePath = "gridfs/507f1f77bcf86cd799439011/alex.pdf";
        profile.setResumePath(sharedResumePath);
        Job job = job();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobRepository.findById("python-job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByJobIdAndJobSeekerProfileId("python-job-1", "jobSeeker-profile-1"))
                .thenReturn(Optional.empty());
        when(resumeStorageService.isSharedStorageEnabled()).thenReturn(true);
        when(resumeStorageService.exists(sharedResumePath)).thenReturn(true);
        when(resumeStorageService.isSharedStoragePath(sharedResumePath)).thenReturn(true);
        when(jobApplicationRepository.save(any(JobApplication.class))).thenAnswer(invocation -> {
            JobApplication application = invocation.getArgument(0);
            application.setId("application-1");
            return application;
        });

        jobSeekerJobService.apply("jobSeeker-user-1", "python-job-1");

        ArgumentCaptor<JobApplication> applicationCaptor = ArgumentCaptor.forClass(JobApplication.class);
        verify(jobApplicationRepository).save(applicationCaptor.capture());
        assertEquals(sharedResumePath, applicationCaptor.getValue().getResumePath());
    }

    @Test
    void listOpenJobsReturnsApplicationStateForTheAuthenticatedJobSeeker() {
        JobSeekerProfile profile = jobSeekerProfile();
        Job job = job();
        JobApplication application = JobApplication.builder()
                .id("application-1")
                .jobId("python-job-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(LocalDateTime.now().minusMinutes(2))
                .build();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobApplicationRepository.findByJobSeekerProfileId("jobSeeker-profile-1"))
                .thenReturn(List.of(application));
        when(jobRepository.findByStatusOrderByCreatedAtDesc(JobStatus.OPEN)).thenReturn(List.of(job));
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/alex.pdf")).thenReturn(true);

        List<JobSeekerJobResponse> jobs = jobSeekerJobService.listOpenJobs("jobSeeker-user-1");

        assertEquals(1, jobs.size());
        assertEquals("python-job-1", jobs.get(0).getId());
        assertTrue(jobs.get(0).isHasResume());
        assertTrue(jobs.get(0).isHasApplied());
        assertEquals(ApplicationStatus.APPLIED, jobs.get(0).getApplicationStatus());
        assertEquals(SalaryRange.RANGE_50K_100K, jobs.get(0).getSalaryRange());
        assertEquals(job.getCreatedAt(), jobs.get(0).getCreatedAt());
    }

    @Test
    void listOpenJobsMatchesStateAbbreviationsAndFullNames() {
        JobSeekerProfile profile = jobSeekerProfile();
        Job texasFullName = job();
        texasFullName.setId("texas-full-name");
        texasFullName.setLocation("Austin, Texas");
        Job texasAbbreviation = job();
        texasAbbreviation.setId("texas-abbreviation");
        texasAbbreviation.setLocation("Dallas, TX");
        Job californiaFullName = job();
        californiaFullName.setId("california-full-name");
        californiaFullName.setLocation("Los Angeles, California");
        Job californiaAbbreviation = job();
        californiaAbbreviation.setId("california-abbreviation");
        californiaAbbreviation.setLocation("San Diego, CA");

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobApplicationRepository.findByJobSeekerProfileId("jobSeeker-profile-1")).thenReturn(List.of());
        when(jobRepository.findByStatusOrderByCreatedAtDesc(JobStatus.OPEN))
                .thenReturn(List.of(texasFullName, texasAbbreviation, californiaFullName, californiaAbbreviation));
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/alex.pdf")).thenReturn(true);

        assertEquals(List.of("texas-full-name", "texas-abbreviation"),
                jobSeekerJobService.listOpenJobs("jobSeeker-user-1", "TX")
                        .stream()
                        .map(JobSeekerJobResponse::getId)
                        .toList());
        assertEquals(List.of("texas-full-name", "texas-abbreviation"),
                jobSeekerJobService.listOpenJobs("jobSeeker-user-1", "texas")
                        .stream()
                        .map(JobSeekerJobResponse::getId)
                        .toList());
        assertEquals(List.of("california-full-name", "california-abbreviation"),
                jobSeekerJobService.listOpenJobs("jobSeeker-user-1", "CA")
                        .stream()
                        .map(JobSeekerJobResponse::getId)
                        .toList());
        assertEquals(List.of("california-full-name", "california-abbreviation"),
                jobSeekerJobService.listOpenJobs("jobSeeker-user-1", "California")
                        .stream()
                        .map(JobSeekerJobResponse::getId)
                        .toList());
    }

    @Test
    void applyRequiresAStoredResumeWhenTheJobRequiresOne() {
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .build();
        Job job = job();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobRepository.findById("python-job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByJobIdAndJobSeekerProfileId("python-job-1", "jobSeeker-profile-1"))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> jobSeekerJobService.apply("jobSeeker-user-1", "python-job-1"));
    }

    @Test
    void applyRequiresAStoredResumeWhenLegacyJobDoesNotHaveTheFlag() {
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .build();
        Job job = job();
        job.setResumeRequired(null);

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobRepository.findById("python-job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByJobIdAndJobSeekerProfileId("python-job-1", "jobSeeker-profile-1"))
                .thenReturn(Optional.empty());

        assertThrows(BusinessException.class,
                () -> jobSeekerJobService.apply("jobSeeker-user-1", "python-job-1"));
    }

    @Test
    void applyDoesNotUseAnOlderUploadWhenTheRecordedResumeFileIsMissing() {
        JobSeekerProfile profile = jobSeekerProfile();
        Job job = job();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobRepository.findById("python-job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByJobIdAndJobSeekerProfileId("python-job-1", "jobSeeker-profile-1"))
                .thenReturn(Optional.empty());
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/alex.pdf")).thenReturn(false);

        assertThrows(BusinessException.class,
                () -> jobSeekerJobService.apply("jobSeeker-user-1", "python-job-1"));
        org.mockito.Mockito.verify(resumeStorageService, org.mockito.Mockito.never())
                .findLatestStoredPath("jobSeeker-profile-1");
    }

    @Test
    void applyRejectsDuplicateApplication() {
        JobSeekerProfile profile = jobSeekerProfile();
        Job job = job();
        JobApplication existingApplication = JobApplication.builder()
                .id("application-1")
                .jobId("python-job-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .status(ApplicationStatus.APPLIED)
                .build();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(jobRepository.findById("python-job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByJobIdAndJobSeekerProfileId("python-job-1", "jobSeeker-profile-1"))
                .thenReturn(Optional.of(existingApplication));

        assertThrows(BusinessException.class,
                () -> jobSeekerJobService.apply("jobSeeker-user-1", "python-job-1"));
    }

    private JobSeekerProfile jobSeekerProfile() {
        return JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .resumePath("resumes/jobSeeker-profile-1/alex.pdf")
                .build();
    }

    private Job job() {
        return Job.builder()
                .id("python-job-1")
                .title("Python Developer")
                .description("Build Python services.")
                .location("Irving, Texas")
                .workMode(WorkMode.REMOTE)
                .employmentType(EmploymentType.FULL_TIME)
                .experienceLevel(ExperienceLevel.ENTRY_LEVEL)
                .requiredSkills(List.of("Python"))
                .salaryMin(new BigDecimal("56000"))
                .salaryMax(new BigDecimal("90000"))
                .salaryRange(SalaryRange.RANGE_50K_100K)
                .applicationDeadline(LocalDateTime.now().plusDays(10))
                .resumeRequired(true)
                .status(JobStatus.OPEN)
                .createdAt(LocalDateTime.of(2026, 8, 5, 16, 19))
                .build();
    }
}
