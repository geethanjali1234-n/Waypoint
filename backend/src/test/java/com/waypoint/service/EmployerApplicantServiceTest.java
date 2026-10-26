package com.waypoint.service;

import com.waypoint.dto.employer.EmployerApplicantDetailResponse;
import com.waypoint.dto.employer.EmployerApplicantSummaryResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Job;
import com.waypoint.entity.JobApplication;
import com.waypoint.entity.User;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.CompanySize;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployerApplicantServiceTest {

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private JobApplicationRepository jobApplicationRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ResumeStorageService resumeStorageService;

    @Mock
    private NotificationService notificationService;

    @InjectMocks
    private EmployerApplicantService employerApplicantService;

    @Test
    void listApplicantsReturnsApplicantSummariesForOwnedJob() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByJobIdOrderByAppliedAtDesc("job-1"))
                .thenReturn(List.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(true);

        List<EmployerApplicantSummaryResponse> applicants = employerApplicantService
                .listApplicants("employer-user-1", "job-1");

        assertEquals(1, applicants.size());
        assertEquals("Alex Applicant", applicants.get(0).getFullName());
        assertEquals("alex@example.com", applicants.get(0).getEmail());
        assertTrue(applicants.get(0).isHasResume());
    }

    @Test
    void getApplicantReturnsProfileAndApplicationDetails() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(true);

        EmployerApplicantDetailResponse applicant = employerApplicantService.getApplicant(
                "employer-user-1", "job-1", "application-1");

        assertEquals("Alex Applicant", applicant.getFullName());
        assertEquals("alex@example.com", applicant.getEmail());
        assertEquals("555-0100", applicant.getPhoneNumber());
        assertEquals("alex.pdf", applicant.getResumeFileName());
        assertEquals(ApplicationStatus.APPLIED, applicant.getStatus());
        assertTrue(applicant.isHasResume());
        assertTrue(applicant.isResumeRecordExists());
    }

    @Test
    void employerCanUpdateApplicantStatusForOwnedJob() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobApplicationRepository.save(application)).thenReturn(application);
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(true);

        EmployerApplicantDetailResponse applicant = employerApplicantService.updateApplicationStatus(
                "employer-user-1", "job-1", "application-1", ApplicationStatus.REJECTED);

        assertEquals(ApplicationStatus.REJECTED, applicant.getStatus());
        assertEquals(ApplicationStatus.REJECTED, application.getStatus());
        verify(jobApplicationRepository).save(application);
    }

    @Test
    void getApplicantUsesTheResumeRecordedOnTheApplication() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        application.setResumePath("resumes/jobSeeker-profile-1/jobSeeker_pages.docx");
        JobSeekerProfile profile = jobSeekerProfile();
        profile.setResumePath("resumes/jobSeeker-profile-1/newer-resume.docx");
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/jobSeeker_pages.docx"))
                .thenReturn(true);

        EmployerApplicantDetailResponse applicant = employerApplicantService.getApplicant(
                "employer-user-1", "job-1", "application-1");

        assertEquals("jobSeeker_pages.docx", applicant.getResumeFileName());
        assertTrue(applicant.isHasResume());
        verify(resumeStorageService, never())
                .exists("resumes/jobSeeker-profile-1/newer-resume.docx");
    }

    @Test
    void getApplicantResumeFallsBackToCurrentProfileResumeWhenApplicationPathIsMissing() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        application.setResumePath(null);
        JobSeekerProfile profile = jobSeekerProfile();
        profile.setResumePath("resumes/jobSeeker-profile-1/current-resume.pdf");
        ResumeStorageService.ResumeDocument document = org.mockito.Mockito.mock(ResumeStorageService.ResumeDocument.class);

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/current-resume.pdf")).thenReturn(true);
        when(resumeStorageService.load("resumes/jobSeeker-profile-1/current-resume.pdf"))
                .thenReturn(document);

        assertEquals(document, employerApplicantService.getApplicantResume(
                "employer-user-1", "job-1", "application-1"));
        verify(resumeStorageService).load("resumes/jobSeeker-profile-1/current-resume.pdf");
    }

    @Test
    void employerCanLoadAResumeFromSharedGridFsForAnOwnedJob() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        String sharedResumePath = "gridfs/507f1f77bcf86cd799439011/alex.pdf";
        application.setResumePath(sharedResumePath);
        JobSeekerProfile profile = jobSeekerProfile();
        ResumeStorageService.ResumeDocument document = org.mockito.Mockito.mock(ResumeStorageService.ResumeDocument.class);

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.exists(sharedResumePath)).thenReturn(true);
        when(resumeStorageService.load(sharedResumePath)).thenReturn(document);

        assertEquals(document, employerApplicantService.getApplicantResume(
                "employer-user-1", "job-1", "application-1"));
        verify(resumeStorageService).load(sharedResumePath);
    }

    @Test
    void getApplicantFindsProfileScopedResumeWhenDatabasePathsAreMissing() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        application.setResumePath(null);
        JobSeekerProfile profile = jobSeekerProfile();
        profile.setResumePath(null);
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.findLatestStoredPath("jobSeeker-profile-1"))
                .thenReturn(Optional.of("resumes/jobSeeker-profile-1/XSD_.docx"));

        EmployerApplicantDetailResponse applicant = employerApplicantService.getApplicant(
                "employer-user-1", "job-1", "application-1");

        assertTrue(applicant.isHasResume());
        assertEquals("XSD_.docx", applicant.getResumeFileName());
        assertTrue(applicant.isResumeRecordExists());
    }

    @Test
    void doesNotSubstituteAnUnrelatedStoredResumeWhenRecordedApplicationFileIsMissing() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(false);

        EmployerApplicantDetailResponse applicant = employerApplicantService.getApplicant(
                "employer-user-1", "job-1", "application-1");

        assertEquals("alex.pdf", applicant.getResumeFileName());
        org.junit.jupiter.api.Assertions.assertFalse(applicant.isHasResume());
        assertTrue(applicant.isResumeRecordExists());
        verify(resumeStorageService, never()).findLatestStoredPath("jobSeeker-profile-1");
        verify(resumeStorageService, never()).findLatestStoredPath("jobSeeker-user-1");
    }

    @Test
    void distinguishesMissingResumeFileFromNoResumeRecord() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(false);

        EmployerApplicantDetailResponse applicant = employerApplicantService.getApplicant(
                "employer-user-1", "job-1", "application-1");

        assertEquals("alex.pdf", applicant.getResumeFileName());
        org.junit.jupiter.api.Assertions.assertFalse(applicant.isHasResume());
        assertTrue(applicant.isResumeRecordExists());
    }

    @Test
    void listApplicantsHidesJobOwnedByAnotherEmployer() {
        EmployerProfile employer = employerProfile();
        Job anotherEmployersJob = Job.builder()
                .id("job-1")
                .employerProfileId("another-employer-profile")
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(anotherEmployersJob));

        assertThrows(ResourceNotFoundException.class,
                () -> employerApplicantService.listApplicants("employer-user-1", "job-1"));
        verify(jobApplicationRepository, never()).findByJobIdOrderByAppliedAtDesc(anyString());
    }

    @Test
    void employerCannotDownloadAResumeForAJobOwnedByAnotherEmployer() {
        EmployerProfile employer = employerProfile();
        Job anotherEmployersJob = Job.builder()
                .id("job-1")
                .employerProfileId("another-employer-profile")
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(anotherEmployersJob));

        assertThrows(ResourceNotFoundException.class,
                () -> employerApplicantService.getApplicantResume(
                        "employer-user-1", "job-1", "application-1"));
        verify(resumeStorageService, never()).load(anyString());
    }

    @Test
    void updateApplicationStatus_notifiesJobSeekerExactlyOnceWithExpectedFields() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).title("Backend Engineer").build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobApplicationRepository.save(application)).thenReturn(application);
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(true);

        EmployerApplicantDetailResponse applicant = employerApplicantService.updateApplicationStatus(
                "employer-user-1", "job-1", "application-1", ApplicationStatus.REJECTED);

        assertEquals(ApplicationStatus.REJECTED, applicant.getStatus());
        verify(notificationService, times(1)).notifyApplicationStatusUpdated(
                "jobSeeker-user-1", "alex@example.com", "application-1", "Backend Engineer",
                ApplicationStatus.REJECTED);
    }

    @Test
    void updateApplicationStatusForJobNotOwnedByEmployer_doesNotNotify() {
        EmployerProfile employer = employerProfile();
        Job anotherEmployersJob = Job.builder().id("job-1").employerProfileId("another-employer-profile").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(anotherEmployersJob));

        assertThrows(ResourceNotFoundException.class, () -> employerApplicantService.updateApplicationStatus(
                "employer-user-1", "job-1", "application-1", ApplicationStatus.REJECTED));

        verify(jobApplicationRepository, never()).save(any());
        verify(notificationService, never()).notifyApplicationStatusUpdated(any(), any(), any(), any(), any());
    }

    @Test
    void updateApplicationStatus_sameStatus_savesAndReturnsButDoesNotNotify() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).title("Backend Engineer").build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobApplicationRepository.save(application)).thenReturn(application);
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(true);

        EmployerApplicantDetailResponse applicant = employerApplicantService.updateApplicationStatus(
                "employer-user-1", "job-1", "application-1", ApplicationStatus.APPLIED);

        assertEquals(ApplicationStatus.APPLIED, applicant.getStatus());
        verify(jobApplicationRepository).save(application);
        verify(notificationService, never()).notifyApplicationStatusUpdated(any(), any(), any(), any(), any());
    }

    @Test
    void notificationPreparationFailure_doesNotFailUpdateApplicationStatus() {
        EmployerProfile employer = employerProfile();
        Job job = Job.builder().id("job-1").employerProfileId(employer.getId()).title("Backend Engineer").build();
        JobApplication application = application();
        JobSeekerProfile profile = jobSeekerProfile();
        User user = User.builder().id("jobSeeker-user-1").email("alex@example.com").build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(employer));
        when(jobRepository.findById("job-1")).thenReturn(Optional.of(job));
        when(jobApplicationRepository.findByIdAndJobId("application-1", "job-1"))
                .thenReturn(Optional.of(application));
        when(jobApplicationRepository.save(application)).thenReturn(application);
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1"))
                .thenThrow(new RuntimeException("boom"))
                .thenReturn(Optional.of(profile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(resumeStorageService.exists("/resume/alex.pdf")).thenReturn(true);

        EmployerApplicantDetailResponse applicant = employerApplicantService.updateApplicationStatus(
                "employer-user-1", "job-1", "application-1", ApplicationStatus.REJECTED);

        assertEquals(ApplicationStatus.REJECTED, applicant.getStatus());
        verify(notificationService, never()).notifyApplicationStatusUpdated(any(), any(), any(), any(), any());
    }

    private EmployerProfile employerProfile() {
        return EmployerProfile.builder()
                .id("employer-profile-1")
                .userId("employer-user-1")
                .companyName("Waypoint Labs")
                .companyAddress("100 Main Street")
                .phoneNumber("555-0100")
                .companySize(CompanySize.MEDIUM)
                .build();
    }

    private JobApplication application() {
        LocalDateTime appliedAt = LocalDateTime.of(2026, 7, 30, 10, 0);
        return JobApplication.builder()
                .id("application-1")
                .jobId("job-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .resumePath("/resume/alex.pdf")
                .status(ApplicationStatus.APPLIED)
                .appliedAt(appliedAt)
                .updatedAt(appliedAt)
                .build();
    }

    private JobSeekerProfile jobSeekerProfile() {
        return JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .firstName("Alex")
                .lastName("Applicant")
                .phoneNumber("555-0100")
                .address("100 Main Street")
                .resumePath("/resume/alex.pdf")
                .skills(List.of("Java", "React"))
                .preferredJobRoles(List.of("Full Stack Developer"))
                .preferredLocations(List.of("Austin, Texas"))
                .build();
    }
}
