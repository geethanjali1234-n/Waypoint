package com.waypoint.config;

import com.waypoint.entity.*;
import com.waypoint.enums.*;
import com.waypoint.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "waypoint.startup.database-initializer.enabled", havingValue = "true")
public class DatabaseInitializer implements CommandLineRunner {

    private final UserRepository userRepository;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final JobRepository jobRepository;
    private final JobApplicationRepository jobApplicationRepository;
    private final EventRepository eventRepository;
    private final EventRegistrationRepository eventRegistrationRepository;
    private final NotificationRepository notificationRepository;
    private final OtpVerificationRepository otpVerificationRepository;
    private final ContactSupportRepository contactSupportRepository;

    @Override
    public void run(String... args) {

        LocalDateTime now = LocalDateTime.now();

        String jobSeekerUserId = "setup-job-seeker-user";
        String employerUserId = "setup-employer-user";
        String jobSeekerProfileId = "setup-job-seeker-profile";
        String employerProfileId = "setup-employer-profile";
        String jobId = "setup-job";
        String eventId = "setup-event";

        // 1. JobSeeker user
        if (!userRepository.existsById(jobSeekerUserId)) {
            User jobSeekerUser = User.builder()
                    .id(jobSeekerUserId)
                    .email("setup.job-seeker@waypoint.local")
                    .passwordHash("temporary-password-hash")
                    .role(UserRole.JOB_SEEKER)
                    .isEmailVerified(true)
                    .accountStatus(AccountStatus.ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            userRepository.save(jobSeekerUser);
        }

        // 2. Employer user
        if (!userRepository.existsById(employerUserId)) {
            User employerUser = User.builder()
                    .id(employerUserId)
                    .email("setup.employer@waypoint.local")
                    .passwordHash("temporary-password-hash")
                    .role(UserRole.EMPLOYER)
                    .isEmailVerified(true)
                    .accountStatus(AccountStatus.ACTIVE)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            userRepository.save(employerUser);
        }

        // 3. JobSeeker profile
        if (!jobSeekerProfileRepository.existsById(jobSeekerProfileId)) {
            JobSeekerProfile jobSeekerProfile = JobSeekerProfile.builder()
                    .id(jobSeekerProfileId)
                    .userId(jobSeekerUserId)
                    .firstName("Setup")
                    .lastName("Job Seeker")
                    .phoneNumber("0000000000")
                    .profilePicturePath("/temporary/profile.png")
                    .resumePath("/temporary/resume.pdf")
                    .skills(List.of("Java", "Spring Boot", "React"))
                    .preferredJobRoles(List.of("Java Developer"))
                    .preferredLocations(List.of("Austin, Texas"))
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            jobSeekerProfileRepository.save(jobSeekerProfile);
        }

        // 4. Employer profile
        if (!employerProfileRepository.existsById(employerProfileId)) {
            EmployerProfile employerProfile = EmployerProfile.builder()
                    .id(employerProfileId)
                    .userId(employerUserId)
                    .companyName("Waypoint Setup Company")
                    .companyAddress("Austin, Texas")
                    .companyLogoPath("/temporary/company-logo.png")
                    .phoneNumber("0000000000")
                    .companySize(CompanySize.MEDIUM)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            employerProfileRepository.save(employerProfile);
        } else {
            employerProfileRepository.findById(employerProfileId).ifPresent(employerProfile -> {
                boolean changed = false;
                if (employerProfile.getCompanyName() == null || employerProfile.getCompanyName().isBlank()) {
                    employerProfile.setCompanyName("Waypoint Setup Company");
                    changed = true;
                }
                if (employerProfile.getCompanyAddress() == null || employerProfile.getCompanyAddress().isBlank()) {
                    employerProfile.setCompanyAddress("Austin, Texas");
                    changed = true;
                }
                if (employerProfile.getPhoneNumber() == null || employerProfile.getPhoneNumber().isBlank()) {
                    employerProfile.setPhoneNumber("0000000000");
                    changed = true;
                }
                if (employerProfile.getCompanySize() == null) {
                    employerProfile.setCompanySize(CompanySize.MEDIUM);
                    changed = true;
                }
                if (changed) {
                    employerProfile.setUpdatedAt(now);
                    employerProfileRepository.save(employerProfile);
                }
            });
        }

        // 5. Job
        if (!jobRepository.existsById(jobId)) {
            Job job = Job.builder()
                    .id(jobId)
                    .employerProfileId(employerProfileId)
                    .jobCode("SETUP-JOB-001")
                    .title("Temporary Java Developer Position")
                    .description("Temporary job document used to initialize MongoDB.")
                    .location("Austin, Texas")
                    .workMode(WorkMode.HYBRID)
                    .employmentType(EmploymentType.FULL_TIME)
                    .requiredSkills(List.of("Java", "Spring Boot", "MongoDB"))
                    .salaryMin(new BigDecimal("70000"))
                    .salaryMax(new BigDecimal("90000"))
                    .salaryRange(SalaryRange.RANGE_50K_100K)
                    .applicationDeadline(now.plusDays(30))
                    .status(JobStatus.OPEN)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            jobRepository.save(job);
        }

        // 6. Job application
        if (!jobApplicationRepository.existsById("setup-job-application")) {
            JobApplication jobApplication = JobApplication.builder()
                    .id("setup-job-application")
                    .jobId(jobId)
                    .jobSeekerProfileId(jobSeekerProfileId)
                    .resumePath("/temporary/resume.pdf")
                    .status(ApplicationStatus.APPLIED)
                    .appliedAt(now)
                    .updatedAt(now)
                    .build();

            jobApplicationRepository.save(jobApplication);
        }

        // 7. Event
        if (!eventRepository.existsById(eventId)) {
            Event event = Event.builder()
                    .id(eventId)
                    .employerProfileId(employerProfileId)
                    .eventCode("SETUP-EVENT-001")
                    .title("Temporary WayPoint Career Event")
                    .description("Temporary event document used to initialize MongoDB.")
                    .eventType(EventType.WORKSHOP)
                    .eventMode(EventMode.HYBRID)
                    .venueName("Waypoint Conference Room")
                    .address("Austin, Texas")
                    .latitude(new BigDecimal("30.2672"))
                    .longitude(new BigDecimal("-97.7431"))
                    .meetingLink("https://example.com/temporary-meeting")
                    .startDateTime(now.plusDays(10))
                    .endDateTime(now.plusDays(10).plusHours(2))
                    .registrationDeadline(now.plusDays(9))
                    .capacity(100)
                    .status(EventStatus.PUBLISHED)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            eventRepository.save(event);
        }

        // 7b. Additional sample events in other cities so jobSeeker location search is testable
        if (!eventRepository.existsById("sample-event-sf")) {
            Event sanFranciscoEvent = Event.builder()
                    .id("sample-event-sf")
                    .employerProfileId(employerProfileId)
                    .eventCode("SAMPLE-EVENT-SF-001")
                    .title("San Francisco Tech Career Fair")
                    .description("Sample event used to exercise location search across multiple cities.")
                    .eventType(EventType.JOB_FAIR)
                    .eventMode(EventMode.OFFLINE)
                    .venueName("Moscone Center")
                    .address("San Francisco, California")
                    .latitude(new BigDecimal("37.7749"))
                    .longitude(new BigDecimal("-122.4194"))
                    .startDateTime(now.plusDays(15))
                    .endDateTime(now.plusDays(15).plusHours(3))
                    .registrationDeadline(now.plusDays(14))
                    .capacity(200)
                    .status(EventStatus.PUBLISHED)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            eventRepository.save(sanFranciscoEvent);
        }

        if (!eventRepository.existsById("sample-event-nyc")) {
            Event newYorkEvent = Event.builder()
                    .id("sample-event-nyc")
                    .employerProfileId(employerProfileId)
                    .eventCode("SAMPLE-EVENT-NYC-001")
                    .title("New York Networking Meetup")
                    .description("Sample event used to exercise location search across multiple cities.")
                    .eventType(EventType.NETWORKING)
                    .eventMode(EventMode.HYBRID)
                    .venueName("Javits Center")
                    .address("New York, New York")
                    .latitude(new BigDecimal("40.7128"))
                    .longitude(new BigDecimal("-74.0060"))
                    .meetingLink("https://example.com/nyc-networking-meetup")
                    .startDateTime(now.plusDays(20))
                    .endDateTime(now.plusDays(20).plusHours(2))
                    .registrationDeadline(now.plusDays(19))
                    .capacity(150)
                    .status(EventStatus.PUBLISHED)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            eventRepository.save(newYorkEvent);
        }

        // 8. Event registration
        if (!eventRegistrationRepository.existsById("setup-event-registration")) {
            EventRegistration registration = EventRegistration.builder()
                    .id("setup-event-registration")
                    .eventId(eventId)
                    .jobSeekerProfileId(jobSeekerProfileId)
                    .registrationStatus(RegistrationStatus.REGISTERED)
                    .attendanceStatus(AttendanceStatus.NOT_MARKED)
                    .registeredAt(now)
                    .updatedAt(now)
                    .build();

            eventRegistrationRepository.save(registration);
        }

        // 9. Notification
        if (!notificationRepository.existsById("setup-notification")) {
            Notification notification = Notification.builder()
                    .id("setup-notification")
                    .recipientUserId(jobSeekerUserId)
                    .title("WayPoint database initialized")
                    .message("This is a temporary notification document.")
                    .notificationType(NotificationType.SYSTEM_ANNOUNCEMENT)
                    .relatedEntityType("JOB")
                    .relatedEntityId(jobId)
                    .isRead(false)
                    .createdAt(now)
                    .readAt(null)
                    .build();

            notificationRepository.save(notification);
        }

        // 10. OTP verification
        if (!otpVerificationRepository.existsById("setup-otp-verification")) {
            OtpVerification otpVerification = OtpVerification.builder()
                    .id("setup-otp-verification")
                    .email("setup.job-seeker@waypoint.local")
                    .otpHash("temporary-otp-hash")
                    .purpose(OtpPurpose.EMAIL_VERIFICATION)
                    .expiresAt(now.plusDays(30))
                    .isVerified(false)
                    .createdAt(now)
                    .verifiedAt(null)
                    .build();

            otpVerificationRepository.save(otpVerification);
        }

        // 11. Contact support
        if (!contactSupportRepository.existsById("setup-contact-support")) {
            ContactSupport contactSupport = ContactSupport.builder()
                    .id("setup-contact-support")
                    .userId(jobSeekerUserId)
                    .priority(SupportPriority.LOW)
                    .subject("Temporary support request")
                    .message("Temporary document used to initialize MongoDB.")
                    .status(SupportStatus.OPEN)
                    .createdAt(now)
                    .updatedAt(now)
                    .resolvedAt(null)
                    .build();

            contactSupportRepository.save(contactSupport);
        }

        System.out.println("WayPoint MongoDB collections initialized successfully.");
    }
}
