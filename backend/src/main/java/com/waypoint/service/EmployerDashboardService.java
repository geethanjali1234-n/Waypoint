package com.waypoint.service;

import com.waypoint.dto.employer.EmployerDashboardResponse;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Event;
import com.waypoint.entity.Job;
import com.waypoint.entity.User;
import com.waypoint.enums.EventStatus;
import com.waypoint.enums.JobStatus;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.EventRegistrationRepository;
import com.waypoint.repository.EventRepository;
import com.waypoint.repository.JobApplicationRepository;
import com.waypoint.repository.JobRepository;
import com.waypoint.repository.NotificationRepository;
import com.waypoint.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class EmployerDashboardService {

    private final EmployerProfileRepository employerProfileRepository;
    private final UserRepository userRepository;
    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final EventRepository eventRepository;
    private final EventRegistrationRepository eventRegistrationRepository;
    private final NotificationRepository notificationRepository;

    public EmployerDashboardResponse getDashboard(String authenticatedUserId) {
        EmployerProfile employerProfile = employerProfileRepository.findByUserId(authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Employer profile not found"));

        String email = userRepository.findById(authenticatedUserId)
                .map(User::getEmail)
                .orElse(null);

        List<Job> jobs = jobRepository
                .findByEmployerProfileIdOrderByCreatedAtDesc(employerProfile.getId());
        List<Event> events = eventRepository
                .findByEmployerProfileIdOrderByStartDateTimeAsc(employerProfile.getId());

        int openJobsCount = Math.toIntExact(jobs.stream()
                .filter(job -> job.getStatus() == JobStatus.OPEN)
                .count());
        int totalApplicationsCount = Math.toIntExact(jobs.stream()
                .mapToLong(job -> jobApplicationRepository.countByJobId(job.getId()))
                .sum());
        LocalDateTime now = LocalDateTime.now();
        int upcomingEventsCount = Math.toIntExact(events.stream()
                .filter(event -> event.getStatus() == EventStatus.PUBLISHED)
                .filter(event -> event.getStartDateTime() != null && event.getStartDateTime().isAfter(now))
                .count());
        int totalRegistrationsCount = Math.toIntExact(events.stream()
                .mapToLong(event -> eventRegistrationRepository.countByEventId(event.getId()))
                .sum());

        return EmployerDashboardResponse.builder()
                .companyName(employerProfile.getCompanyName())
                .companyAddress(employerProfile.getCompanyAddress())
                .companyLogoPath(employerProfile.getCompanyLogoPath())
                .phoneNumber(employerProfile.getPhoneNumber())
                .companySize(employerProfile.getCompanySize())
                .email(email)
                .openJobsCount(openJobsCount)
                .totalApplicationsCount(totalApplicationsCount)
                .upcomingEventsCount(upcomingEventsCount)
                .totalEventsCount(events.size())
                .totalRegistrationsCount(totalRegistrationsCount)
                .recentJobs(jobs.stream().limit(3).map(Job::getTitle).toList())
                .recentEvents(events.stream()
                        .sorted(Comparator.comparing(
                                Event::getCreatedAt,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                        .limit(3)
                        .map(Event::getTitle)
                        .toList())
                .recentNotifications(notificationRepository
                        .findByRecipientUserIdOrderByCreatedAtDesc(authenticatedUserId, PageRequest.of(0, 3))
                        .stream()
                        .map(notification -> notification.getTitle())
                        .toList())
                .build();
    }
}
