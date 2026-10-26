package com.waypoint.service;

import com.waypoint.dto.employer.EmployerDashboardResponse;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Job;
import com.waypoint.entity.User;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.JobStatus;
import com.waypoint.repository.EventRegistrationRepository;
import com.waypoint.repository.EventRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import com.waypoint.repository.NotificationRepository;
import com.waypoint.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployerDashboardServiceTest {

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private JobApplicationRepository jobApplicationRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventRegistrationRepository eventRegistrationRepository;

    @Mock
    private NotificationRepository notificationRepository;

    @InjectMocks
    private EmployerDashboardService employerDashboardService;

    @Test
    void dashboardReturnsCountsFromOwnedJobsAndApplications() {
        EmployerProfile profile = EmployerProfile.builder()
                .id("employer-profile-1")
                .userId("employer-user-1")
                .companyName("Waypoint Labs")
                .companySize(CompanySize.MEDIUM)
                .build();
        Job openJob = Job.builder()
                .id("job-1")
                .title("Python Developer")
                .employerProfileId("employer-profile-1")
                .status(JobStatus.OPEN)
                .build();
        Job closedJob = Job.builder()
                .id("job-2")
                .title("Java Developer")
                .employerProfileId("employer-profile-1")
                .status(JobStatus.CLOSED)
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.of(profile));
        when(userRepository.findById("employer-user-1")).thenReturn(Optional.of(User.builder()
                .id("employer-user-1")
                .email("employer@example.com")
                .build()));
        when(jobRepository.findByEmployerProfileIdOrderByCreatedAtDesc("employer-profile-1"))
                .thenReturn(List.of(openJob, closedJob));
        when(jobApplicationRepository.countByJobId("job-1")).thenReturn(2L);
        when(jobApplicationRepository.countByJobId("job-2")).thenReturn(1L);
        when(eventRepository.findByEmployerProfileIdOrderByStartDateTimeAsc("employer-profile-1"))
                .thenReturn(List.of());
        when(notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(any(), any()))
                .thenReturn(List.of());

        EmployerDashboardResponse response = employerDashboardService
                .getDashboard("employer-user-1");

        assertEquals(1, response.getOpenJobsCount());
        assertEquals(3, response.getTotalApplicationsCount());
        assertEquals(List.of("Python Developer", "Java Developer"), response.getRecentJobs());
    }
}
