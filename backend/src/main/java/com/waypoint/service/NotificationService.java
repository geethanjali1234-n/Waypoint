package com.waypoint.service;

import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Orchestrates in-app and email notification delivery for job-seeker-facing
 * business events. Callers (the domain services that own the underlying
 * business operation) are responsible for resolving every recipient
 * identifier, email address, and display value before calling these
 * methods - this service performs no repository lookups of its own.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final String APPLICATIONS_ACTION_URL = "/job-seeker/applications";
    private static final String REGISTRATIONS_ACTION_URL = "/job-seeker/registrations";
    private static final String INVALID_INPUT_REASON = "INVALID_INPUT";
    private static final DateTimeFormatter EVENT_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.US);
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("h:mm a", Locale.US);

    private final InAppNotificationService inAppNotificationService;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplateService;

    public void notifyApplicationSubmitted(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String employerUserId,
            String employerEmail,
            String applicationId,
            String jobId,
            String jobTitle,
            String companyName,
            String applicantName) {

        if (isBlank(jobSeekerUserId) || isBlank(jobSeekerEmail) || isBlank(employerEmail)
                || isBlank(applicationId) || isBlank(jobTitle) || isBlank(companyName)) {
            logInvalidInput("APPLICATION_SUBMITTED");
            return;
        }

        String subjectSafeJobTitle = sanitizeForSubject(jobTitle);

        safelyRun("APPLICATION_SUBMITTED", "IN_APP", () -> inAppNotificationService.createInAppOnlyNotification(
                jobSeekerUserId,
                NotificationType.APPLICATION_SUBMITTED,
                "Application submitted",
                "Your application to " + companyName + " for " + jobTitle + " has been submitted.",
                applicationId,
                APPLICATIONS_ACTION_URL));

        safelyRun("APPLICATION_SUBMITTED", "EMAIL_JOB_SEEKER", () -> emailService.sendHtmlEmail(
                jobSeekerEmail,
                "Your application for " + subjectSafeJobTitle + " has been submitted",
                buildEmail("Application submitted", "Hi,",
                        "Your application to " + companyName + " for " + jobTitle
                                + " has been submitted successfully. Log in to your Waypoint account to track "
                                + "your application status.")));

        safelyRun("APPLICATION_SUBMITTED", "EMAIL_EMPLOYER", () -> emailService.sendHtmlEmail(
                employerEmail,
                "New application received for " + subjectSafeJobTitle,
                buildEmail("New application received", null,
                        "You have received a new job application for " + jobTitle
                                + ". Log in to your Waypoint account to review the applicant and manage "
                                + "the application.")));

        if (isBlank(employerUserId) || isBlank(jobId) || isBlank(applicantName)) {
            logInvalidInput("NEW_APPLICATION_RECEIVED");
            return;
        }

        safelyRun("NEW_APPLICATION_RECEIVED", "IN_APP", () -> inAppNotificationService.createInAppOnlyNotification(
                employerUserId,
                NotificationType.NEW_APPLICATION_RECEIVED,
                "New application received",
                applicantName + " applied for " + jobTitle + ".",
                applicationId,
                "/employer/jobs/" + jobId + "/applicants/" + applicationId));
    }

    public void notifyApplicationStatusUpdated(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String applicationId,
            String jobTitle,
            ApplicationStatus newStatus) {

        if (isBlank(jobSeekerUserId) || isBlank(jobSeekerEmail) || isBlank(applicationId)
                || isBlank(jobTitle) || newStatus == null) {
            logInvalidInput("APPLICATION_STATUS_UPDATE");
            return;
        }

        String formattedStatus = formatStatus(newStatus);
        String message = "Your application for " + jobTitle + " has been updated to " + formattedStatus
                + ". Click to view the latest status.";

        safelyRun("APPLICATION_STATUS_UPDATE", "IN_APP", () -> inAppNotificationService.createInAppOnlyNotification(
                jobSeekerUserId,
                NotificationType.APPLICATION_STATUS_UPDATE,
                "Application status updated",
                message,
                applicationId,
                APPLICATIONS_ACTION_URL));

        safelyRun("APPLICATION_STATUS_UPDATE", "EMAIL_JOB_SEEKER", () -> emailService.sendHtmlEmail(
                jobSeekerEmail,
                "Your application status has been updated",
                buildEmail("Application status updated", null,
                        "Your application for " + jobTitle + " has been updated to "
                                + formattedStatus + ". Log in to your Waypoint account to view the latest "
                                + "application status.")));
    }

    public void notifyEventRegistrationConfirmed(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String employerEmail,
            String registrationId,
            String eventTitle,
            String companyName) {
        notifyEventRegistrationConfirmed(
                jobSeekerUserId, jobSeekerEmail, null, employerEmail, registrationId, null,
                eventTitle, companyName, null, null);
    }

    public void notifyEventRegistrationConfirmed(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String employerUserId,
            String employerEmail,
            String registrationId,
            String eventId,
            String eventTitle,
            String companyName,
            String registrantName) {
        notifyEventRegistrationConfirmed(
                jobSeekerUserId, jobSeekerEmail, employerUserId, employerEmail, registrationId,
                eventId, eventTitle, companyName, registrantName, null);
    }

    public void notifyEventRegistrationConfirmed(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String employerUserId,
            String employerEmail,
            String registrationId,
            String eventId,
            String eventTitle,
            String companyName,
            String registrantName,
            EventEmailDetails eventDetails) {

        if (isBlank(jobSeekerUserId) || isBlank(jobSeekerEmail) || isBlank(employerEmail)
                || isBlank(registrationId) || isBlank(eventTitle) || isBlank(companyName)) {
            logInvalidInput("EVENT_REGISTRATION_CONFIRMED");
            return;
        }

        String subjectSafeEventTitle = sanitizeForSubject(eventTitle);

        safelyRun("EVENT_REGISTRATION_CONFIRMED", "IN_APP", () -> inAppNotificationService.createInAppOnlyNotification(
                jobSeekerUserId,
                NotificationType.EVENT_REGISTRATION_CONFIRMED,
                "Event registration confirmed",
                "You're registered for " + eventTitle + " hosted by " + companyName + ".",
                registrationId,
                REGISTRATIONS_ACTION_URL));

        safelyRun("EVENT_REGISTRATION_CONFIRMED", "EMAIL_JOB_SEEKER", () -> emailService.sendHtmlEmail(
                jobSeekerEmail,
                "You're registered for " + subjectSafeEventTitle,
                buildEventEmail("Event registration confirmed",
                        "You're registered for " + eventTitle + " hosted by " + companyName + ".",
                        eventDetailRows(eventDetails, null, true, false),
                        "Log in to your Waypoint account to view your event registration details.")));

        safelyRun("EVENT_REGISTRATION_CONFIRMED", "EMAIL_EMPLOYER", () -> emailService.sendHtmlEmail(
                employerEmail,
                "New registration for " + subjectSafeEventTitle,
                buildEventEmail("New registration received",
                        "A new job seeker has registered for " + eventTitle + ".",
                        eventDetailRows(eventDetails, registrantName, true, true),
                        "Log in to your Waypoint account to view the registered participant details.")));

        if (isBlank(employerUserId) || isBlank(eventId) || isBlank(registrantName)) {
            logInvalidInput("EVENT_REGISTRATION_RECEIVED");
            return;
        }

        safelyRun("EVENT_REGISTRATION_RECEIVED", "IN_APP", () -> inAppNotificationService.createInAppOnlyNotification(
                employerUserId,
                NotificationType.EVENT_REGISTRATION_CONFIRMED,
                "New event registration",
                registrantName + " registered for " + eventTitle + ".",
                registrationId,
                "/employer/events/" + eventId + "/registrants"));
    }

    public void notifyEventRegistrationCancelled(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String employerUserId,
            String employerEmail,
            String registrationId,
            String eventId,
            String eventTitle,
            String registrantName) {
        notifyEventRegistrationCancelled(
                jobSeekerUserId, jobSeekerEmail, employerUserId, employerEmail, registrationId,
                eventId, eventTitle, registrantName, null);
    }

    public void notifyEventRegistrationCancelled(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String employerUserId,
            String employerEmail,
            String registrationId,
            String eventId,
            String eventTitle,
            String registrantName,
            EventEmailDetails eventDetails) {

        if (isBlank(jobSeekerUserId) || isBlank(jobSeekerEmail) || isBlank(employerUserId)
                || isBlank(employerEmail) || isBlank(registrationId) || isBlank(eventId)
                || isBlank(eventTitle) || isBlank(registrantName)) {
            logInvalidInput("EVENT_REGISTRATION_CANCELLED");
            return;
        }

        safelyRun("EVENT_REGISTRATION_CANCELLED", "IN_APP_JOB_SEEKER", () ->
                inAppNotificationService.createInAppOnlyNotification(
                        jobSeekerUserId,
                        NotificationType.EVENT_UPDATE,
                        "Event registration withdrawn",
                        "You withdrew your registration for " + eventTitle + ".",
                        registrationId,
                        REGISTRATIONS_ACTION_URL));

        safelyRun("EVENT_REGISTRATION_CANCELLED", "IN_APP_EMPLOYER", () ->
                inAppNotificationService.createInAppOnlyNotification(
                        employerUserId,
                        NotificationType.EVENT_UPDATE,
                        "Event registration cancelled",
                        registrantName + " cancelled their registration for " + eventTitle + ".",
                        registrationId,
                        "/employer/events/" + eventId + "/registrants"));

        String subjectSafeEventTitle = sanitizeForSubject(eventTitle);
        safelyRun("EVENT_REGISTRATION_CANCELLED", "EMAIL_JOB_SEEKER", () -> emailService.sendHtmlEmail(
                jobSeekerEmail,
                "Registration withdrawn for " + subjectSafeEventTitle,
                buildEventEmail("Registration withdrawn",
                        "You withdrew your registration for " + eventTitle + ".",
                        eventDetailRows(eventDetails, null, true, false),
                        "Log in to your Waypoint account to view your updated event registrations.")));
        safelyRun("EVENT_REGISTRATION_CANCELLED", "EMAIL_EMPLOYER", () -> emailService.sendHtmlEmail(
                employerEmail,
                "Registration cancelled for " + subjectSafeEventTitle,
                buildEventEmail("Registration cancelled",
                        registrantName + " cancelled their registration for " + eventTitle + ".",
                        eventDetailRows(eventDetails, registrantName, false, true),
                        "Log in to your Waypoint account to view the updated participant list.")));
    }

    public void notifyEventUpdated(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String eventId,
            String eventTitle) {
        notifyEventUpdated(jobSeekerUserId, jobSeekerEmail, eventId, eventTitle, null);
    }

    public void notifyEventUpdated(
            String jobSeekerUserId,
            String jobSeekerEmail,
            String eventId,
            String eventTitle,
            EventEmailDetails eventDetails) {

        if (isBlank(jobSeekerUserId) || isBlank(jobSeekerEmail) || isBlank(eventId) || isBlank(eventTitle)) {
            logInvalidInput("EVENT_UPDATED");
            return;
        }

        safelyRun("EVENT_UPDATED", "IN_APP", () -> inAppNotificationService.createInAppOnlyNotification(
                jobSeekerUserId,
                NotificationType.EVENT_UPDATE,
                "Event details updated",
                "Details for " + eventTitle + " have been updated. Review the latest event information.",
                eventId,
                REGISTRATIONS_ACTION_URL));

        safelyRun("EVENT_UPDATED", "EMAIL_JOB_SEEKER", () -> emailService.sendHtmlEmail(
                jobSeekerEmail,
                "Event details updated for " + sanitizeForSubject(eventTitle),
                buildEventEmail("Event details updated",
                        "The event information has been updated. Review the latest details below.",
                        eventDetailRows(eventDetails, null, true, false),
                        "Log in to your Waypoint account to view the latest event details.")));
    }

    private void safelyRun(String eventType, String channel, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            log.warn(
                    "Notification channel failed [event={}, channel={}, exceptionType={}]",
                    eventType,
                    channel,
                    exception.getClass().getSimpleName());
        }
    }

    private void logInvalidInput(String eventType) {
        log.warn("Notification event skipped [event={}, reason={}]", eventType, INVALID_INPUT_REASON);
    }

    private static boolean isBlank(String value) {
        return !StringUtils.hasText(value);
    }

    private static String formatStatus(ApplicationStatus status) {
        StringBuilder result = new StringBuilder();
        for (String word : status.name().split("_")) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)))
                        .append(word.substring(1).toLowerCase())
                        .append(' ');
            }
        }
        return result.toString().trim();
    }

    private static String sanitizeForSubject(String value) {
        if (value == null) {
            return "";
        }

        return value
                .replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String buildEmail(String title, String greeting, String message) {
        return emailTemplateService.build(EmailTemplateData.builder()
                .preheader(title)
                .title(title)
                .greeting(greeting)
                .message(message)
                .build());
    }

    private String buildEventEmail(
            String title, String message, List<EmailDetailRow> detailRows, String closingMessage) {
        return emailTemplateService.build(EmailTemplateData.builder()
                .preheader(title)
                .title(title)
                .message(message)
                .detailRows(detailRows)
                .secondaryInformation(closingMessage)
                .build());
    }

    private List<EmailDetailRow> eventDetailRows(
            EventEmailDetails details,
            String registrantName,
            boolean includeLocation,
            boolean registrantBeforeMode) {
        if (details == null) {
            return List.of();
        }

        List<EmailDetailRow> rows = new ArrayList<>();
        rows.add(new EmailDetailRow("Event", details.getTitle()));
        if (registrantBeforeMode) {
            appendScheduleRows(rows, details.getStartDateTime(), details.getEndDateTime());
            rows.add(new EmailDetailRow("Registrant", registrantName));
        }
        rows.add(new EmailDetailRow("Event mode", formatEventMode(details.getEventMode())));
        if (!registrantBeforeMode) {
            appendScheduleRows(rows, details.getStartDateTime(), details.getEndDateTime());
        }
        if (includeLocation) {
            appendLocationRows(rows, details);
        }
        return rows;
    }

    private void appendScheduleRows(
            List<EmailDetailRow> rows, LocalDateTime startDateTime, LocalDateTime endDateTime) {
        if (startDateTime != null && endDateTime != null
                && !startDateTime.toLocalDate().equals(endDateTime.toLocalDate())) {
            rows.add(new EmailDetailRow("Start", formatEventDateTime(startDateTime)));
            rows.add(new EmailDetailRow("End", formatEventDateTime(endDateTime)));
            return;
        }
        if (startDateTime != null) {
            rows.add(new EmailDetailRow("Date", EVENT_DATE_FORMATTER.format(startDateTime)));
            String time = EVENT_TIME_FORMATTER.format(startDateTime);
            if (endDateTime != null) {
                time += " – " + EVENT_TIME_FORMATTER.format(endDateTime);
            }
            rows.add(new EmailDetailRow("Time", time));
        } else if (endDateTime != null) {
            rows.add(new EmailDetailRow("End", formatEventDateTime(endDateTime)));
        }
    }

    private void appendLocationRows(List<EmailDetailRow> rows, EventEmailDetails details) {
        EventMode eventMode = details.getEventMode();
        if (eventMode == EventMode.ONLINE) {
            rows.add(new EmailDetailRow("Meeting link", validMeetingLink(details.getMeetingLink())
                    ? details.getMeetingLink()
                    : "Meeting link will be shared by the organizer."));
            return;
        }
        if (eventMode == EventMode.OFFLINE) {
            if (isBlank(details.getVenueName()) && isBlank(details.getAddress())) {
                rows.add(new EmailDetailRow("Location",
                        "Location details will be shared by the organizer."));
                return;
            }
            rows.add(new EmailDetailRow("Venue", details.getVenueName()));
            rows.add(new EmailDetailRow("Address", details.getAddress()));
            return;
        }
        if (eventMode == EventMode.HYBRID) {
            rows.add(new EmailDetailRow("Venue", details.getVenueName()));
            rows.add(new EmailDetailRow("Address", details.getAddress()));
            if (validMeetingLink(details.getMeetingLink())) {
                rows.add(new EmailDetailRow("Meeting link", details.getMeetingLink()));
            }
        }
    }

    private static String formatEventMode(EventMode eventMode) {
        if (eventMode == EventMode.ONLINE) {
            return "Online";
        }
        if (eventMode == EventMode.OFFLINE) {
            return "In person";
        }
        if (eventMode == EventMode.HYBRID) {
            return "Hybrid";
        }
        return null;
    }

    private static String formatEventDateTime(LocalDateTime dateTime) {
        return EVENT_DATE_FORMATTER.format(dateTime) + " at " + EVENT_TIME_FORMATTER.format(dateTime);
    }

    private static boolean validMeetingLink(String value) {
        if (!StringUtils.hasText(value) || !value.equals(value.trim())
                || value.chars().anyMatch(character ->
                Character.isISOControl(character) || Character.isWhitespace(character))) {
            return false;
        }
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            return (scheme.equals("https") || scheme.equals("http"))
                    && uri.isAbsolute()
                    && StringUtils.hasText(uri.getHost())
                    && uri.getUserInfo() == null;
        } catch (URISyntaxException exception) {
            return false;
        }
    }
}
