package com.waypoint.service;

import com.waypoint.entity.EmployeeProfile;
import com.waypoint.entity.Organization;
import com.waypoint.entity.Notification;
import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.EmployeeProfileRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.repository.UserRepository;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The single path that emails a notification's recipient. {@link InAppNotificationService#createNotification} calls
 * {@link #deliver(Notification)} so every in-app notification is also emailed; {@link NotificationOrchestrator} calls
 * {@link #deliverEvent(NotificationEvent)} (richer wording) after persisting in-app only, so an event never takes both
 * paths. Flows that already send their own dedicated email persist with createInAppOnlyNotification. Recipient: the profile's personal email, then the account email
 * (never the work email), except a Company Admin, whose address is the Organization Settings Admin Email then the account email; inactive users and users outside the notification's organization get no email.
 * Delivery is best effort and never throws.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InAppNotificationEmailService {

    private static final Pattern EMAIL_ADDRESS = Pattern.compile("^[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+$");

    private final UserRepository userRepository;
    private final EmployeeProfileRepository profileRepository;
    private final OrganizationRepository organizationRepository;
    private final EmailService emailService;
    private final EmailTemplateService emailTemplates;

    @Value("${waypoint.app.frontend-base-url:http://localhost:5173}")
    private String frontendBaseUrl = "http://localhost:5173";

    /** Emails a persisted notification using the default subject and wording. */
    public void deliver(Notification notification) {
        if (notification == null) return;
        send(notification.getOrganizationId(), notification.getRecipientUserId(), notification.getNotificationType(),
                notification.getTitle(), notification.getMessage(), notification.getActionUrl(),
                subject(notification), null, null, true);
    }

    /** Emails an orchestrated event, honouring its optional subject, email wording, detail rows and button label. */
    public void deliverEvent(NotificationEvent event) {
        if (event == null) return;
        String subject = StringUtils.hasText(event.getEmailSubject()) ? event.getEmailSubject() : event.getTitle();
        send(event.getOrganizationId(), event.getRecipientUserId(), event.getType(), event.getTitle(),
                StringUtils.hasText(event.getEmailMessage()) ? event.getEmailMessage() : event.getMessage(),
                event.getActionPath(), subject, event.getDetailRows(), event.getActionLabel(), false);
    }

    private void send(String organizationId, String recipientUserId, NotificationType type, String title, String message,
                      String actionPath, String subject, java.util.List<EmailDetailRow> detailRows, String actionLabel,
                      boolean defaultWording) {
        try {
            String address = resolveEmail(organizationId, recipientUserId);
            if (address == null) return;
            String actionUrl = absoluteActionUrl(actionPath);
            EmailTemplateData.EmailTemplateDataBuilder template = EmailTemplateData.builder()
                    .preheader(oneLine(title))
                    .title(oneLine(title))
                    .message(message)
                    .detailRows(detailRows);
            if (defaultWording) template.secondaryInformation("This email was sent because you have a new notification in Waypoint.");
            if (actionUrl != null) {
                template.actionLabel(StringUtils.hasText(actionLabel) ? actionLabel : actionLabel(type)).actionUrl(actionUrl);
            }
            emailService.sendHtmlEmail(address, subject, emailTemplates.build(template.build()));
        } catch (RuntimeException failure) {
            log.warn("Notification email delivery failed [type={}, exceptionType={}]", type, failure.getClass().getSimpleName());
        }
    }

    /** Active, same-organization recipient only: profile personal email, then account email; never the work email. */
    String resolveEmail(String organizationId, String userId) {
        if (!StringUtils.hasText(userId)) return null;
        User user = userRepository.findById(userId).orElse(null);
        if (user == null || user.getAccountStatus() != AccountStatus.ACTIVE) return null;
        if (StringUtils.hasText(organizationId) && !organizationId.equals(user.getOrganizationId())) {
            log.warn("Notification email skipped [reason=ORGANIZATION_MISMATCH]");
            return null;
        }
        String accountEmail = validEmail(user.getEmail());
        if (user.getRole() == UserRole.COMPANY_ADMIN) {
            // A Company Admin has no employee profile: the Admin Email saved in Organization Settings is their
            // notification address (shown as "Personal Email" on their profile), then the login account email.
            String configured = organizationRepository.findById(user.getOrganizationId())
                    .map(Organization::getAdminEmail).orElse(null);
            String adminEmail = validEmail(configured);
            if (adminEmail != null) return adminEmail;
            log.warn("Company Admin notification email using the account email [reason={}]",
                    StringUtils.hasText(configured) ? "ADMIN_EMAIL_INVALID" : "ADMIN_EMAIL_MISSING");
            return orSkipped(accountEmail);
        }
        EmployeeProfile profile = profileRepository.findByUserId(userId).orElse(null);
        if (profile != null && (!StringUtils.hasText(organizationId) || organizationId.equals(profile.getOrganizationId()))) {
            String personalEmail = validEmail(profile.getPersonalEmail());
            if (personalEmail != null) return personalEmail;
        }
        return orSkipped(accountEmail);
    }

    private String orSkipped(String address) {
        if (address == null) log.warn("Notification email skipped [reason=NO_VALID_RECIPIENT]");
        return address;
    }

    /** The trimmed address when it is a single well-formed email address, otherwise null. Never sends to a malformed value. */
    static String validEmail(String value) {
        if (!StringUtils.hasText(value)) return null;
        String address = value.trim();
        if (address.length() > 254 || !EMAIL_ADDRESS.matcher(address).matches()) return null;
        try {
            new InternetAddress(address, true).validate();
        } catch (AddressException invalid) {
            return null;
        }
        return address;
    }

    private String subject(Notification notification) {
        String title = oneLine(notification.getTitle());
        return title.length() > 180 ? "Waypoint: " + title.substring(0, 180).trim() : "Waypoint: " + title;
    }

    private String actionLabel(NotificationType type) {
        if (type == null) return "View notification";
        return switch (type) {
            case DOCUMENT_REQUEST_CREATED, DOCUMENT_REQUEST_FULFILLED, DOCUMENT_REQUEST_REJECTED, DOCUMENT_REQUEST_RETURNED,
                 OFFER_LETTER_APPROVAL_REQUESTED, OFFER_LETTER_APPROVED, OFFER_LETTER_REJECTED -> "View document request";
            case TIMESHEET_SUBMITTED, TIMESHEET_APPROVED, TIMESHEET_REJECTED -> "View timesheet";
            case PAYROLL_TIMESHEET_ACTION_REQUIRED, PAYROLL_TIMESHEET_FOLLOW_UP_REQUIRED -> "Open payroll readiness";
            case PROFILE_CHANGE_SUBMITTED, PROFILE_CHANGE_APPROVED, PROFILE_CHANGE_REJECTED -> "View profile change";
            case APPLICATION_SUBMITTED, APPLICATION_STATUS_UPDATE, NEW_APPLICATION_RECEIVED -> "View application";
            case EVENT_UPDATE, EVENT_REGISTRATION_CONFIRMED -> "View event details";
            case EMPLOYEE_ASSIGNED, EMPLOYEE_REASSIGNED, REPORTING_MANAGER_UPDATED, ACCOUNT_STATUS_UPDATED -> "View employee details";
            default -> "View notification";
        };
    }

    private String absoluteActionUrl(String actionUrl) {
        if (!StringUtils.hasText(actionUrl) || !actionUrl.startsWith("/") || actionUrl.startsWith("//")
                || actionUrl.chars().anyMatch(Character::isISOControl) || actionUrl.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        try {
            URI base = new URI(frontendBaseUrl == null ? "" : frontendBaseUrl.trim());
            String scheme = base.getScheme() == null ? "" : base.getScheme().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("http") || scheme.equals("https")) || !base.isAbsolute() || !StringUtils.hasText(base.getHost())
                    || base.getUserInfo() != null) return null;
            return base.resolve(actionUrl).toASCIIString();
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    private String oneLine(String value) {
        String normalized = value == null ? "" : value.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("\\s+", " ").trim();
        return StringUtils.hasText(normalized) ? normalized : "Notification";
    }
}
