package com.waypoint.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Notifies a user with richer, event-specific email wording. It persists the in-app notification with
 * createInAppOnlyNotification (so the generic email is not also sent) and, unless the event is flagged email=false
 * because a dedicated email already covers it, sends one best-effort email through
 * {@link InAppNotificationEmailService}. An email failure or a missing address never removes the in-app
 * notification and never surfaces to the caller. Mail delivery is governed by the existing mail configuration.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationOrchestrator {

    private final InAppNotificationService inAppNotifications;
    private final InAppNotificationEmailService emailDelivery;

    /** Notifies each distinct recipient once (de-duplicated by user id). */
    public void notifyAll(Collection<NotificationEvent> events) {
        Set<String> seen = new HashSet<>();
        for (NotificationEvent event : events) {
            if (event != null && seen.add(event.getRecipientUserId())) notify(event);
        }
    }

    public void notify(NotificationEvent event) {
        try {
            inAppNotifications.createInAppOnlyNotification(event.getOrganizationId(), event.getRecipientUserId(), event.getType(),
                    event.getTitle(), event.getMessage(), event.getRelatedEntityId(), event.getActionPath());
        } catch (RuntimeException failure) {
            log.warn("In-app notification failed [type={}, exceptionType={}]", event.getType(), failure.getClass().getSimpleName());
        }
        if (event.isEmail()) emailDelivery.deliverEvent(event);
    }
}
