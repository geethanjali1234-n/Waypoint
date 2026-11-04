package com.waypoint.service.google;

import com.waypoint.entity.User;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.NotificationType;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.UserRepository;
import com.waypoint.service.InAppNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Tells an organization's active Company Admins that its Google connection needs to be reconnected. Reacts to
 * {@link GoogleConnectionReauthRequiredEvent}, which is published once per CONNECTED to REAUTH_REQUIRED transition,
 * so a connection that keeps failing does not produce repeated notifications. Only that organization's admins are
 * notified. The text names no token, account address or Google error detail.
 *
 * <p>A failure here never reaches the email that triggered it: the event is published best effort.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleConnectionProblemNotifier {

    static final String TITLE = "Reconnect your Google email account";
    static final String MESSAGE = "WayPoint can no longer send your organization's email through its connected Google account, "
            + "for example because access was removed in Google. Open Organization Settings and reconnect it. "
            + "Until then, emails are sent from the standard WayPoint sender.";
    static final String ACTION_PATH = "/myhr/admin/organization";

    private final UserRepository userRepository;
    private final InAppNotificationService notifications;

    @EventListener
    public void onReauthRequired(GoogleConnectionReauthRequiredEvent event) {
        if (event == null || !StringUtils.hasText(event.organizationId())) return;
        List<User> admins = userRepository.findByOrganizationIdAndRoleAndAccountStatus(
                event.organizationId(), UserRole.COMPANY_ADMIN, AccountStatus.ACTIVE);
        for (User admin : admins) {
            try {
                // Only users of this organization (the query is scoped to it); the check below is belt and braces.
                if (!event.organizationId().equals(admin.getOrganizationId())) continue;
                notifications.createNotification(event.organizationId(), admin.getId(), NotificationType.ACCOUNT_ACTIVITY,
                        TITLE, MESSAGE, event.organizationId(), ACTION_PATH);
            } catch (RuntimeException failure) {
                log.warn("Google reconnect notification failed [organizationId={}, exceptionType={}]",
                        event.organizationId(), failure.getClass().getSimpleName());
            }
        }
    }
}
