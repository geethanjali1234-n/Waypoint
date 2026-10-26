package com.waypoint.service;

import com.waypoint.dto.profile.JobSeekerProfileResponse;
import com.waypoint.dto.profile.JobSeekerProfileUpdateRequest;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EventRegistration;
import com.waypoint.entity.JobApplication;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.RegistrationStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EventRegistrationRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobSeekerProfileServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private JobApplicationRepository jobApplicationRepository;

    @Mock
    private EventRegistrationRepository eventRegistrationRepository;

    @Mock
    private FileStorageService fileStorageService;

    @Mock
    private ResumeStorageService resumeStorageService;

    @InjectMocks
    private JobSeekerProfileService jobSeekerProfileService;

    @Test
    void profileDoesNotReportAResumeWhenTheRecordedFileIsUnavailable() {
        User user = User.builder()
                .id("jobSeeker-user-1")
                .email("jobSeeker@example.com")
                .build();
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .resumePath("gridfs/missing-resume/resume.pdf")
                .build();

        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.exists(profile.getResumePath())).thenReturn(false);
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc(profile.getId()))
                .thenReturn(List.of());
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                profile.getId(), RegistrationStatus.REGISTERED))
                .thenReturn(List.of());

        assertNull(jobSeekerProfileService.getProfile("jobSeeker-user-1").getResumeFileName());
    }

    private User user(String id, String email) {
        return User.builder()
                .id(id)
                .email(email)
                .passwordHash("hash")
                .role(UserRole.JOB_SEEKER)
                .isEmailVerified(true)
                .accountStatus(AccountStatus.ACTIVE)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private JobSeekerProfile.JobSeekerProfileBuilder baseProfile(String id, String userId) {
        return JobSeekerProfile.builder()
                .id(id)
                .userId(userId)
                .firstName("Jordan")
                .lastName("Rivera")
                .address("Austin, Texas")
                .phoneNumber("+15551234567")
                .skills(List.of("Java"))
                .preferredJobRoles(List.of("Backend Developer"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now());
    }

    private JobSeekerProfileUpdateRequest updateRequest() {
        JobSeekerProfileUpdateRequest request = new JobSeekerProfileUpdateRequest();
        request.setFirstName("Jordan");
        request.setLastName("Rivera");
        request.setPhoneNumber("+15551234567");
        request.setSkills(List.of("Java", "Spring"));
        request.setPreferredJobRoles(List.of("Backend Developer"));
        request.setPreferredEmploymentType(List.of(EmploymentType.FULL_TIME));
        request.setExpectedSalaryMin(new BigDecimal("70000"));
        request.setExpectedSalaryMax(new BigDecimal("90000"));
        return request;
    }

    // --- getProfile ---

    @Test
    void throwsResourceNotFoundExceptionWhenUserDoesNotExist() {
        when(userRepository.findById("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobSeekerProfileService.getProfile("user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found");

        verify(jobSeekerProfileRepository, never()).findByUserId(anyString());
    }

    @Test
    void throwsResourceNotFoundExceptionWhenProfileDoesNotExist() {
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user("user-1", "jordan@example.com")));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobSeekerProfileService.getProfile("user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("JobSeeker profile not found");
    }

    @Test
    void returnsProfileWithDerivedApplicationAndRegistrationStats() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = baseProfile("profile-1", "user-1")
                .profilePicturePath("profile-pictures/profile-1/avatar.png")
                .resumePath("resumes/profile-1/resume.pdf")
                .expectedSalaryMin(new BigDecimal("70000"))
                .expectedSalaryMax(new BigDecimal("90000"))
                .preferredEmploymentType(List.of(EmploymentType.FULL_TIME))
                .build();

        JobApplication underReview = JobApplication.builder().id("app-1").status(ApplicationStatus.UNDER_REVIEW).build();
        JobApplication shortlisted = JobApplication.builder().id("app-2").status(ApplicationStatus.SHORTLISTED).build();
        JobApplication applied = JobApplication.builder().id("app-3").status(ApplicationStatus.APPLIED).build();

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.exists("resumes/profile-1/resume.pdf")).thenReturn(true);
        when(fileStorageService.getMetadata("resumes/profile-1/resume.pdf")).thenReturn(
                new FileStorageService.FileMetadata("resume.pdf", 1024L, LocalDateTime.now()));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("profile-1"))
                .thenReturn(List.of(underReview, shortlisted, applied));
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                "profile-1", RegistrationStatus.REGISTERED))
                .thenReturn(List.of(EventRegistration.builder().id("reg-1").build()));

        JobSeekerProfileResponse response = jobSeekerProfileService.getProfile("user-1");

        assertThat(response.getUserId()).isEqualTo("user-1");
        assertThat(response.getJobSeekerProfileId()).isEqualTo("profile-1");
        assertThat(response.getEmail()).isEqualTo("jordan@example.com");
        assertThat(response.getProfilePictureUrl()).isEqualTo("/uploads/profile-pictures/profile-1/avatar.png");
        assertThat(response.getResumeFileName()).isEqualTo("resume.pdf");
        assertThat(response.getResumeSizeBytes()).isEqualTo(1024L);
        assertThat(response.getJobsApplied()).isEqualTo(3);
        assertThat(response.getInReview()).isEqualTo(1);
        assertThat(response.getShortlisted()).isEqualTo(1);
        assertThat(response.getEventsRegistered()).isEqualTo(1);
        assertThat(response.getCompletionPercentage()).isEqualTo(100);
    }

    @Test
    void fallsBackToPathFileNameWhenResumeMetadataIsUnavailable() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = baseProfile("profile-1", "user-1")
                .resumePath("resumes/profile-1/resume.pdf")
                .build();

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.exists("resumes/profile-1/resume.pdf")).thenReturn(true);
        when(fileStorageService.getMetadata("resumes/profile-1/resume.pdf")).thenReturn(null);
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("profile-1"))
                .thenReturn(List.of());
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                "profile-1", RegistrationStatus.REGISTERED))
                .thenReturn(List.of());

        JobSeekerProfileResponse response = jobSeekerProfileService.getProfile("user-1");

        assertThat(response.getResumeFileName()).isEqualTo("resume.pdf");
        assertThat(response.getResumeUploadedAt()).isNull();
        assertThat(response.getResumeSizeBytes()).isNull();
    }

    @Test
    void computesZeroCompletionPercentageForEmptyProfile() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("profile-1")
                .userId("user-1")
                .build();

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("profile-1"))
                .thenReturn(List.of());
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                "profile-1", RegistrationStatus.REGISTERED))
                .thenReturn(List.of());

        JobSeekerProfileResponse response = jobSeekerProfileService.getProfile("user-1");

        assertThat(response.getCompletionPercentage()).isEqualTo(0);
        assertThat(response.getProfilePictureUrl()).isNull();
    }

    // --- updateProfile ---

    @Test
    void throwsBusinessExceptionWhenSalaryMinExceedsSalaryMax() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = baseProfile("profile-1", "user-1").build();

        JobSeekerProfileUpdateRequest request = updateRequest();
        request.setExpectedSalaryMin(new BigDecimal("90000"));
        request.setExpectedSalaryMax(new BigDecimal("70000"));

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> jobSeekerProfileService.updateProfile("user-1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Salary min must not be greater than salary max");

        verify(jobSeekerProfileRepository, never()).save(any());
    }

    @Test
    void updatesProfileFieldsAndPersists() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = baseProfile("profile-1", "user-1").build();
        JobSeekerProfileUpdateRequest request = updateRequest();

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(jobSeekerProfileRepository.save(any(JobSeekerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("profile-1")).thenReturn(List.of());
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                "profile-1", RegistrationStatus.REGISTERED)).thenReturn(List.of());

        ArgumentCaptor<JobSeekerProfile> captor = ArgumentCaptor.forClass(JobSeekerProfile.class);

        JobSeekerProfileResponse response = jobSeekerProfileService.updateProfile("user-1", request);

        verify(jobSeekerProfileRepository).save(captor.capture());
        JobSeekerProfile saved = captor.getValue();

        assertThat(saved.getFirstName()).isEqualTo("Jordan");
        assertThat(saved.getSkills()).containsExactly("Java", "Spring");
        assertThat(saved.getPreferredEmploymentType()).containsExactly(EmploymentType.FULL_TIME);
        assertThat(saved.getExpectedSalaryMin()).isEqualByComparingTo("70000");
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(response.getFirstName()).isEqualTo("Jordan");

        verify(fileStorageService, never()).remove(anyString(), anyString());
    }

    @Test
    void removesProfilePictureWhenRequestedAndPicturePresent() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = baseProfile("profile-1", "user-1")
                .profilePicturePath("profile-pictures/profile-1/avatar.png")
                .build();
        JobSeekerProfileUpdateRequest request = updateRequest();
        request.setRemoveProfilePicture(true);

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(jobSeekerProfileRepository.save(any(JobSeekerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("profile-1")).thenReturn(List.of());
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                "profile-1", RegistrationStatus.REGISTERED)).thenReturn(List.of());

        JobSeekerProfileResponse response = jobSeekerProfileService.updateProfile("user-1", request);

        verify(fileStorageService).remove("profile-pictures", "profile-1");
        assertThat(response.getProfilePictureUrl()).isNull();
    }

    @Test
    void doesNotAttemptToRemoveProfilePictureWhenNoneExists() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = baseProfile("profile-1", "user-1").build();
        JobSeekerProfileUpdateRequest request = updateRequest();
        request.setRemoveProfilePicture(true);

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(jobSeekerProfileRepository.save(any(JobSeekerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("profile-1")).thenReturn(List.of());
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                "profile-1", RegistrationStatus.REGISTERED)).thenReturn(List.of());

        jobSeekerProfileService.updateProfile("user-1", request);

        verify(fileStorageService, never()).remove(anyString(), anyString());
    }

    // --- updateProfilePhoto ---

    @Test
    void updatesProfilePhotoAndPersistsPath() {
        User user = user("user-1", "jordan@example.com");
        JobSeekerProfile profile = baseProfile("profile-1", "user-1").build();
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", "content".getBytes());

        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));
        when(fileStorageService.store(file, "profile-pictures", "profile-1"))
                .thenReturn("profile-pictures/profile-1/avatar.png");
        when(jobSeekerProfileRepository.save(any(JobSeekerProfile.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jobApplicationRepository.findByJobSeekerProfileIdOrderByAppliedAtDesc("profile-1")).thenReturn(List.of());
        when(eventRegistrationRepository.findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                "profile-1", RegistrationStatus.REGISTERED)).thenReturn(List.of());

        JobSeekerProfileResponse response = jobSeekerProfileService.updateProfilePhoto("user-1", file);

        assertThat(response.getProfilePictureUrl()).isEqualTo("/uploads/profile-pictures/profile-1/avatar.png");
        assertThat(profile.getProfilePicturePath()).isEqualTo("profile-pictures/profile-1/avatar.png");
    }
}
