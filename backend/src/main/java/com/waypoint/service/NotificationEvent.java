package com.waypoint.service;

import com.waypoint.enums.NotificationType;
import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * One notification for one recipient. The in-app part is always created; the email part is only
 * attempted when {@link #email} is true. Links are app-relative paths.
 */
@Getter
@Builder
public class NotificationEvent {

    private final String organizationId;
    private final String recipientUserId;
    private final NotificationType type;
    private final String title;
    private final String message;
    private final String relatedEntityId;
    private final String actionPath;

    /** Whether an email is also sent (important events only). */
    private final boolean email;
    /** Optional email overrides; the in-app title/message are used when absent. */
    private final String emailSubject;
    private final String emailMessage;
    private final List<EmailDetailRow> detailRows;
    private final String actionLabel;
}
