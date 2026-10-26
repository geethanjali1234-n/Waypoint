package com.waypoint.service;

import com.waypoint.dto.jobseeker.JobSeekerResumeUploadResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.JobApplication;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobSeekerResumeServiceTest {

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private JobApplicationRepository jobApplicationRepository;

    @Mock
    private ResumeStorageService resumeStorageService;

    @InjectMocks
    private JobSeekerResumeService jobSeekerResumeService;

    @Test
    void uploadResumeUpdatesJobSeekerProfilePath() {
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "alex.pdf", MediaType.APPLICATION_PDF_VALUE, "resume".getBytes());
        ResumeStorageService.StoredResume storedResume = new ResumeStorageService.StoredResume(
                "resumes/jobSeeker-profile-1/alex.pdf",
                "alex.pdf",
                MediaType.APPLICATION_PDF,
                6);
        JobApplication application = JobApplication.builder()
                .id("application-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .build();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.store(file, "jobSeeker-profile-1")).thenReturn(storedResume);
        when(jobApplicationRepository.findByJobSeekerProfileId("jobSeeker-profile-1"))
                .thenReturn(List.of(application));

        JobSeekerResumeUploadResponse response = jobSeekerResumeService.uploadResume("jobSeeker-user-1", file);

        assertEquals("alex.pdf", response.getFileName());
        assertEquals("resumes/jobSeeker-profile-1/alex.pdf", profile.getResumePath());
        ArgumentCaptor<JobSeekerProfile> profileCaptor = ArgumentCaptor.forClass(JobSeekerProfile.class);
        verify(jobSeekerProfileRepository).save(profileCaptor.capture());
        assertEquals("resumes/jobSeeker-profile-1/alex.pdf", profileCaptor.getValue().getResumePath());
        assertEquals("resumes/jobSeeker-profile-1/alex.pdf", application.getResumePath());
        verify(jobApplicationRepository).saveAll(List.of(application));
    }

    @Test
    void uploadResumeDoesNotReplaceTheResumeAlreadySubmittedWithAnApplication() {
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "jobSeeker_pages.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "new resume".getBytes());
        ResumeStorageService.StoredResume storedResume = new ResumeStorageService.StoredResume(
                "resumes/jobSeeker-profile-1/jobSeeker_pages.docx",
                "jobSeeker_pages.docx",
                MediaType.APPLICATION_OCTET_STREAM,
                10);
        JobApplication application = JobApplication.builder()
                .id("application-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .resumePath("resumes/jobSeeker-profile-1/previous-resume.docx")
                .build();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.store(file, "jobSeeker-profile-1")).thenReturn(storedResume);
        when(jobApplicationRepository.findByJobSeekerProfileId("jobSeeker-profile-1"))
                .thenReturn(List.of(application));
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/previous-resume.docx"))
                .thenReturn(true);

        jobSeekerResumeService.uploadResume("jobSeeker-user-1", file);

        assertEquals("resumes/jobSeeker-profile-1/previous-resume.docx", application.getResumePath());
        verify(jobApplicationRepository, never()).saveAll(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void uploadResumeRepairsAnApplicationWhoseRecordedResumeFileIsMissing() {
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .build();
        MockMultipartFile file = new MockMultipartFile(
                "file", "replacement-resume.docx", MediaType.APPLICATION_OCTET_STREAM_VALUE, "replacement".getBytes());
        ResumeStorageService.StoredResume storedResume = new ResumeStorageService.StoredResume(
                "resumes/jobSeeker-profile-1/replacement-resume.docx",
                "replacement-resume.docx",
                MediaType.APPLICATION_OCTET_STREAM,
                11);
        JobApplication application = JobApplication.builder()
                .id("application-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .resumePath("resumes/jobSeeker-profile-1/missing-resume.docx")
                .build();

        when(jobSeekerProfileRepository.findByUserId("jobSeeker-user-1")).thenReturn(Optional.of(profile));
        when(resumeStorageService.store(file, "jobSeeker-profile-1")).thenReturn(storedResume);
        when(jobApplicationRepository.findByJobSeekerProfileId("jobSeeker-profile-1"))
                .thenReturn(List.of(application));
        when(resumeStorageService.exists("resumes/jobSeeker-profile-1/missing-resume.docx"))
                .thenReturn(false);

        jobSeekerResumeService.uploadResume("jobSeeker-user-1", file);

        assertEquals("resumes/jobSeeker-profile-1/replacement-resume.docx", application.getResumePath());
        verify(jobApplicationRepository).saveAll(List.of(application));
    }
}
