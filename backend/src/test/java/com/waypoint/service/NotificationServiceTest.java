package com.waypoint.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.NotificationType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    private InAppNotificationService inAppNotificationService;

    @Mock
    private EmailService emailService;

    private NotificationService notificationService;
    private EmailTemplateService emailTemplateService;

    private Logger notificationServiceLogger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        emailTemplateService = new EmailTemplateService();
        notificationService = new NotificationService(
                inAppNotificationService, emailService, emailTemplateService);

        notificationServiceLogger = (Logger) LoggerFactory.getLogger(NotificationService.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        notificationServiceLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        notificationServiceLogger.detachAppender(logAppender);
    }

    @Test
    void notifyApplicationSubmitted_createsInAppNotificationWithExpectedFields() {
        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera");

        verify(inAppNotificationService).createInAppOnlyNotification(
                eq("user-1"),
                eq(NotificationType.APPLICATION_SUBMITTED),
                eq("Application submitted"),
                eq("Your application to Acme Corp for Backend Engineer has been submitted."),
                eq("application-1"),
                eq("/job-seeker/applications"));
    }

    @Test
    void notifyApplicationSubmitted_sendsJobSeekerEmailWithExpectedSubjectAndRecipient() {
        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(
                eq("jobSeeker@example.com"),
                eq("Your application for Backend Engineer has been submitted"),
                bodyCaptor.capture());

        assertThat(bodyCaptor.getValue()).contains("Backend Engineer").contains("Acme Corp");
    }

    @Test
    void notifyApplicationSubmitted_sendsEmployerEmailWithExpectedSubjectAndRecipient() {
        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(
                eq("employer@example.com"),
                eq("New application received for Backend Engineer"),
                bodyCaptor.capture());

        assertThat(bodyCaptor.getValue()).contains("Backend Engineer");
    }

    @Test
    void notifyApplicationSubmitted_createsEmployerInAppNotificationWithExpectedFields() {
        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera");

        verify(inAppNotificationService).createInAppOnlyNotification(
                eq("employer-user-1"),
                eq(NotificationType.NEW_APPLICATION_RECEIVED),
                eq("New application received"),
                eq("Jordan Rivera applied for Backend Engineer."),
                eq("application-1"),
                eq("/employer/jobs/job-1/applicants/application-1"));
    }

    @Test
    void notifyApplicationSubmitted_blankEmployerFields_skipsEmployerNotificationButStillNotifiesJobSeekerAndEmails() {
        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", null, "employer@example.com",
                "application-1", null, "Backend Engineer", "Acme Corp", null);

        verify(inAppNotificationService, times(1)).createInAppOnlyNotification(any(), any(), any(), any(), any(), any());
        verify(inAppNotificationService, never()).createInAppOnlyNotification(
                any(), eq(NotificationType.NEW_APPLICATION_RECEIVED), any(), any(), any(), any());
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("employer@example.com"), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(ApplicationStatus.class)
    void notifyApplicationStatusUpdated_reusesApplicationStatusUpdateType_forEveryStatus(ApplicationStatus status) {
        notificationService.notifyApplicationStatusUpdated(
                "user-1", "jobSeeker@example.com", "application-1", "Backend Engineer", status);

        verify(inAppNotificationService).createInAppOnlyNotification(
                eq("user-1"),
                eq(NotificationType.APPLICATION_STATUS_UPDATE),
                anyString(), anyString(), eq("application-1"), eq("/job-seeker/applications"));
    }

    @Test
    void notifyApplicationStatusUpdated_messageIncludesJobTitleAndStatus() {
        notificationService.notifyApplicationStatusUpdated(
                "user-1", "jobSeeker@example.com", "application-1", "Backend Engineer",
                ApplicationStatus.SHORTLISTED);

        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(inAppNotificationService).createInAppOnlyNotification(
                anyString(), any(), anyString(), messageCaptor.capture(), anyString(), anyString());

        assertThat(messageCaptor.getValue())
                .contains("Backend Engineer")
                .containsIgnoringCase("shortlisted");
    }

    @Test
    void notifyApplicationStatusUpdated_sendsOnlyJobSeekerEmail_noEmployerEmail() {
        notificationService.notifyApplicationStatusUpdated(
                "user-1", "jobSeeker@example.com", "application-1", "Backend Engineer",
                ApplicationStatus.REJECTED);

        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), anyString());
        verify(emailService, times(1)).sendHtmlEmail(anyString(), anyString(), anyString());
    }

    @Test
    void nullNewStatus_doesNotEscapeNotifyApplicationStatusUpdated() {
        assertThatCode(() -> notificationService.notifyApplicationStatusUpdated(
                "user-1", "jobSeeker@example.com", "application-1", "Backend Engineer", null))
                .doesNotThrowAnyException();

        verifyNoInteractions(inAppNotificationService, emailService);
    }

    @Test
    void notifyEventRegistrationConfirmed_createsInAppNotificationWithExpectedFields() {
        notificationService.notifyEventRegistrationConfirmed(
                "user-1", "jobSeeker@example.com", "employer@example.com",
                "registration-1", "Career Fair", "Acme Corp");

        verify(inAppNotificationService).createInAppOnlyNotification(
                eq("user-1"),
                eq(NotificationType.EVENT_REGISTRATION_CONFIRMED),
                eq("Event registration confirmed"),
                eq("You're registered for Career Fair hosted by Acme Corp."),
                eq("registration-1"),
                eq("/job-seeker/registrations"));
    }

    @Test
    void notifyEventRegistrationConfirmed_sendsJobSeekerAndEmployerEmails() {
        notificationService.notifyEventRegistrationConfirmed(
                "user-1", "jobSeeker@example.com", "employer@example.com",
                "registration-1", "Career Fair", "Acme Corp");

        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), eq("You're registered for Career Fair"), anyString());
        verify(emailService).sendHtmlEmail(eq("employer@example.com"), eq("New registration for Career Fair"), anyString());
    }

    @Test
    void applicationSubmittedEmailBodies_escapeDynamicValues() {
        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "<script>alert(1)</script>", "Acme & Co", "Jordan Rivera");

        ArgumentCaptor<String> bodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), bodyCaptor.capture());

        assertThat(bodyCaptor.getValue())
                .doesNotContain("<script>")
                .contains("&lt;script&gt;")
                .contains("Acme &amp; Co");
    }

    @Test
    void eventRegistrationEmailBodies_escapeDynamicValues() {
        notificationService.notifyEventRegistrationConfirmed(
                "user-1", "jobSeeker@example.com", "employer@example.com",
                "registration-1", "<b>Career</b> Fair", "Acme & Co");

        ArgumentCaptor<String> jobSeekerBodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), jobSeekerBodyCaptor.capture());
        assertThat(jobSeekerBodyCaptor.getValue())
                .doesNotContain("<b>Career</b>")
                .contains("&lt;b&gt;Career&lt;/b&gt;")
                .contains("Acme &amp; Co");

        ArgumentCaptor<String> employerBodyCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("employer@example.com"), anyString(), employerBodyCaptor.capture());
        assertThat(employerBodyCaptor.getValue()).contains("&lt;b&gt;Career&lt;/b&gt;");
    }

    @Test
    void jobTitleContainingCrlf_isSanitizedInEmailSubject() {
        String maliciousJobTitle = "Backend Engineer\r\nBcc: attacker@evil.com";

        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", maliciousJobTitle, "Acme Corp", "Jordan Rivera");

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), subjectCaptor.capture(), anyString());

        assertThat(subjectCaptor.getValue())
                .isEqualTo("Your application for Backend Engineer Bcc: attacker@evil.com has been submitted");
    }

    @Test
    void eventTitleContainingCrlf_isSanitizedInEmailSubject() {
        String maliciousEventTitle = "Career Fair\r\nBcc: attacker@evil.com";

        notificationService.notifyEventRegistrationConfirmed(
                "user-1", "jobSeeker@example.com", "employer@example.com",
                "registration-1", maliciousEventTitle, "Acme Corp");

        ArgumentCaptor<String> subjectCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), subjectCaptor.capture(), anyString());

        assertThat(subjectCaptor.getValue())
                .isEqualTo("You're registered for Career Fair Bcc: attacker@evil.com");
    }

    @Test
    void inAppFailure_doesNotPreventJobSeekerOrEmployerEmailAttempts() {
        doThrow(new IllegalArgumentException("boom")).when(inAppNotificationService)
                .createInAppOnlyNotification(any(), any(), any(), any(), any(), any());

        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera");

        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("employer@example.com"), anyString(), anyString());
    }

    @Test
    void jobSeekerEmailFailure_doesNotPreventEmployerEmailAttempt() {
        doThrow(new RuntimeException("smtp down")).when(emailService)
                .sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), anyString());

        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera");

        verify(emailService).sendHtmlEmail(eq("employer@example.com"), anyString(), anyString());
    }

    @Test
    void employerEmailFailure_doesNotFailOrchestrationMethod() {
        doThrow(new RuntimeException("smtp down")).when(emailService)
                .sendHtmlEmail(eq("employer@example.com"), anyString(), anyString());

        assertThatCode(() -> notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera"))
                .doesNotThrowAnyException();
    }

    @Test
    void allChannelsFailing_stillAttemptsAllThreeChannels_andDoesNotThrow() {
        doThrow(new IllegalArgumentException("boom")).when(inAppNotificationService)
                .createInAppOnlyNotification(any(), any(), any(), any(), any(), any());
        doThrow(new RuntimeException("smtp down")).when(emailService)
                .sendHtmlEmail(anyString(), anyString(), anyString());

        assertThatCode(() -> notificationService.notifyEventRegistrationConfirmed(
                "user-1", "jobSeeker@example.com", "employer@example.com",
                "registration-1", "Career Fair", "Acme Corp"))
                .doesNotThrowAnyException();

        verify(inAppNotificationService).createInAppOnlyNotification(any(), any(), any(), any(), any(), any());
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), anyString());
        verify(emailService).sendHtmlEmail(eq("employer@example.com"), anyString(), anyString());
    }

    @Test
    void channelFailureLog_neverContainsDynamicSensitiveValues() {
        String sensitiveJobTitle = "Ultra-Secret-Project-Codename-9000";
        doThrow(new RuntimeException("smtp rejected jobSeeker@example.com")).when(emailService)
                .sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), anyString());

        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", sensitiveJobTitle, "Acme Corp", "Jordan Rivera");

        List<String> logMessages = logAppender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .toList();

        assertThat(logMessages).isNotEmpty();
        assertThat(logMessages).noneMatch(message -> message.contains(sensitiveJobTitle));
        assertThat(logMessages).noneMatch(message -> message.contains("jobSeeker@example.com"));
        assertThat(logMessages).noneMatch(message -> message.contains("smtp rejected"));
        assertThat(logMessages).anyMatch(message -> message.contains("RuntimeException"));
        assertThat(logMessages).anyMatch(message -> message.contains("APPLICATION_SUBMITTED"));
        assertThat(logMessages).anyMatch(message -> message.contains("EMAIL_JOB_SEEKER"));
    }

    @Test
    void eventRegistrationConfirmed_createsEmployerInAppNotification() {
        notificationService.notifyEventRegistrationConfirmed(
                "jobSeeker-user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "registration-1", "event-1", "Career Fair", "Acme Corp", "Jordan Rivera");

        verify(inAppNotificationService).createInAppOnlyNotification(
                "employer-user-1",
                NotificationType.EVENT_REGISTRATION_CONFIRMED,
                "New event registration",
                "Jordan Rivera registered for Career Fair.",
                "registration-1",
                "/employer/events/event-1/registrants");
    }

    @Test
    void eventRegistrationCancelled_notifiesJobSeekerAndEmployerInApp() {
        notificationService.notifyEventRegistrationCancelled(
                "jobSeeker-user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "registration-1", "event-1", "Career Fair", "Jordan Rivera");

        verify(inAppNotificationService).createInAppOnlyNotification(
                "jobSeeker-user-1", NotificationType.EVENT_UPDATE, "Event registration withdrawn",
                "You withdrew your registration for Career Fair.", "registration-1", "/job-seeker/registrations");
        verify(inAppNotificationService).createInAppOnlyNotification(
                "employer-user-1", NotificationType.EVENT_UPDATE, "Event registration cancelled",
                "Jordan Rivera cancelled their registration for Career Fair.", "registration-1",
                "/employer/events/event-1/registrants");
    }

    @Test
    void eventUpdated_reusesEventUpdateType() {
        notificationService.notifyEventUpdated(
                "jobSeeker-user-1", "jobSeeker@example.com", "event-1", "Career Fair");

        verify(inAppNotificationService).createInAppOnlyNotification(
                "jobSeeker-user-1", NotificationType.EVENT_UPDATE, "Event details updated",
                "Details for Career Fair have been updated. Review the latest event information.",
                "event-1", "/job-seeker/registrations");
    }

    @Test
    void onlineRegistrationEmail_showsScheduleAndMeetingLinkButNotPhysicalLocation() {
        EventEmailDetails details = eventDetails(EventMode.ONLINE)
                .venueName("Hidden Venue")
                .address("Hidden Address")
                .meetingLink("https://meet.example.com/room?a=1&b=2")
                .build();

        notificationService.notifyEventRegistrationConfirmed(
                "jobSeeker-user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "registration-1", "event-1", "Career Fair", "Acme Corp", "Jordan Rivera", details);

        ArgumentCaptor<String> jobSeekerBody = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), jobSeekerBody.capture());
        assertThat(jobSeekerBody.getValue())
                .contains("Online")
                .contains("August 19, 2026")
                .contains("9:00 AM – 11:00 AM")
                .contains("https://meet.example.com/room?a=1&amp;b=2")
                .doesNotContain("Hidden Venue")
                .doesNotContain("Hidden Address");

        ArgumentCaptor<String> employerBody = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("employer@example.com"), anyString(), employerBody.capture());
        assertThat(employerBody.getValue()).contains("Jordan Rivera");
    }

    @Test
    void offlineRegistrationEmail_showsVenueAndAddressButNotMeetingLink() {
        EventEmailDetails details = eventDetails(EventMode.OFFLINE)
                .venueName("Wipro Conference Center")
                .address("123 Main Street, Austin, TX 78701")
                .meetingLink("https://hidden.example.com/room")
                .build();

        notificationService.notifyEventRegistrationConfirmed(
                "jobSeeker-user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "registration-1", "event-1", "Career Fair", "Acme Corp", "Jordan Rivera", details);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("In person")
                .contains("Wipro Conference Center")
                .contains("123 Main Street, Austin, TX 78701")
                .doesNotContain("https://hidden.example.com/room");
    }

    @Test
    void hybridEventUpdatedEmail_showsLatestPhysicalAndOnlineDetails() {
        EventEmailDetails details = eventDetails(EventMode.HYBRID)
                .title("Updated Career Fair")
                .venueName("New Conference Center")
                .address("500 Updated Avenue")
                .meetingLink("https://meet.example.com/latest")
                .build();

        notificationService.notifyEventUpdated(
                "jobSeeker-user-1", "jobSeeker@example.com", "event-1", "Updated Career Fair", details);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("The event information has been updated. Review the latest details below.")
                .contains("Updated Career Fair")
                .contains("Hybrid")
                .contains("New Conference Center")
                .contains("500 Updated Avenue")
                .contains("https://meet.example.com/latest");
    }

    @Test
    void crossDayEvent_usesSeparateStartAndEndRows() {
        EventEmailDetails details = EventEmailDetails.builder()
                .title("Two-day Summit")
                .eventMode(EventMode.ONLINE)
                .meetingLink("https://meet.example.com/summit")
                .startDateTime(LocalDateTime.of(2026, 8, 19, 9, 0))
                .endDateTime(LocalDateTime.of(2026, 8, 20, 11, 0))
                .build();

        notificationService.notifyEventUpdated(
                "jobSeeker-user-1", "jobSeeker@example.com", "event-1", "Two-day Summit", details);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("Start")
                .contains("August 19, 2026 at 9:00 AM")
                .contains("End")
                .contains("August 20, 2026 at 11:00 AM");
    }

    @Test
    void onlineEventWithoutValidMeetingLink_showsOrganizerFallback() {
        EventEmailDetails details = eventDetails(EventMode.ONLINE)
                .meetingLink("javascript:alert(1)")
                .build();

        notificationService.notifyEventUpdated(
                "jobSeeker-user-1", "jobSeeker@example.com", "event-1", "Career Fair", details);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("Meeting link will be shared by the organizer.")
                .doesNotContain("javascript:alert(1)");
    }

    @Test
    void offlineEventWithoutVenueOrAddress_showsOrganizerFallback() {
        EventEmailDetails details = eventDetails(EventMode.OFFLINE).build();

        notificationService.notifyEventUpdated(
                "jobSeeker-user-1", "jobSeeker@example.com", "event-1", "Career Fair", details);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), body.capture());
        assertThat(body.getValue()).contains("Location details will be shared by the organizer.");
    }

    @Test
    void eventDetailValues_areHtmlEscaped() {
        EventEmailDetails details = eventDetails(EventMode.HYBRID)
                .title("<b>Career & Fair</b>")
                .venueName("<script>Venue</script>")
                .address("One & Two")
                .meetingLink("https://meet.example.com/room?a=1&b=2")
                .build();

        notificationService.notifyEventUpdated(
                "jobSeeker-user-1", "jobSeeker@example.com", "event-1", "Career Fair", details);

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), body.capture());
        assertThat(body.getValue())
                .contains("&lt;b&gt;Career &amp; Fair&lt;/b&gt;")
                .contains("&lt;script&gt;Venue&lt;/script&gt;")
                .contains("One &amp; Two")
                .contains("https://meet.example.com/room?a=1&amp;b=2")
                .doesNotContain("<script>Venue</script>");
    }

    @Test
    void registrationCancellationEmails_includeIdentifyingScheduleDetails() {
        EventEmailDetails details = eventDetails(EventMode.OFFLINE)
                .venueName("Wipro Conference Center")
                .address("123 Main Street")
                .build();

        notificationService.notifyEventRegistrationCancelled(
                "jobSeeker-user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "registration-1", "event-1", "Career Fair", "Jordan Rivera", details);

        ArgumentCaptor<String> jobSeekerBody = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), jobSeekerBody.capture());
        assertThat(jobSeekerBody.getValue())
                .contains("Career Fair")
                .contains("August 19, 2026")
                .contains("9:00 AM – 11:00 AM")
                .contains("Wipro Conference Center")
                .contains("123 Main Street");

        ArgumentCaptor<String> employerBody = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("employer@example.com"), anyString(), employerBody.capture());
        assertThat(employerBody.getValue())
                .contains("Career Fair")
                .contains("Jordan Rivera")
                .contains("In person")
                .doesNotContain("Wipro Conference Center")
                .doesNotContain("123 Main Street");
    }

    @Test
    void jobEmail_doesNotRenderEventDetailsSection() {
        notificationService.notifyApplicationSubmitted(
                "user-1", "jobSeeker@example.com", "employer-user-1", "employer@example.com",
                "application-1", "job-1", "Backend Engineer", "Acme Corp", "Jordan Rivera");

        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendHtmlEmail(eq("jobSeeker@example.com"), anyString(), body.capture());
        assertThat(body.getValue()).doesNotContain("Event details");
    }

    private EventEmailDetails.EventEmailDetailsBuilder eventDetails(EventMode eventMode) {
        return EventEmailDetails.builder()
                .title("Career Fair")
                .eventMode(eventMode)
                .startDateTime(LocalDateTime.of(2026, 8, 19, 9, 0))
                .endDateTime(LocalDateTime.of(2026, 8, 19, 11, 0));
    }
}
