package com.waypoint.service;

import com.waypoint.entity.Notification;
import com.waypoint.enums.NotificationType;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class InAppNotificationService {

    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final int MAX_LIST_LIMIT = 100;

    private final NotificationRepository notificationRepository;
    private final InAppNotificationEmailService notificationEmailService;

    public Notification createNotification(
            String recipientUserId,
            NotificationType type,
            String title,
            String message,
            String relatedEntityId,
            String actionUrl) {
        return createNotification(null, recipientUserId, type, title, message, relatedEntityId, actionUrl);
    }

    public Notification createNotification(
            String organizationId,
            String recipientUserId,
            NotificationType type,
            String title,
            String message,
            String relatedEntityId,
            String actionUrl) {

        return create(organizationId, recipientUserId, type, title, message, relatedEntityId, actionUrl, true);
    }

    /** Creates the in-app record without a generic email when the caller sends a specialized email itself. */
    public Notification createInAppOnlyNotification(
            String recipientUserId,
            NotificationType type,
            String title,
            String message,
            String relatedEntityId,
            String actionUrl) {
        return create(null, recipientUserId, type, title, message, relatedEntityId, actionUrl, false);
    }

    /** Tenant-aware variant of {@link #createInAppOnlyNotification(String, NotificationType, String, String, String, String)}. */
    public Notification createInAppOnlyNotification(
            String organizationId,
            String recipientUserId,
            NotificationType type,
            String title,
            String message,
            String relatedEntityId,
            String actionUrl) {
        return create(organizationId, recipientUserId, type, title, message, relatedEntityId, actionUrl, false);
    }

    private Notification create(String organizationId, String recipientUserId, NotificationType type, String title,
                                String message, String relatedEntityId, String actionUrl, boolean emailRecipient) {
        validateCreate(recipientUserId, type, title, message, actionUrl);

        Notification notification = Notification.builder()
                .organizationId(organizationId)
                .recipientUserId(recipientUserId)
                .notificationType(type)
                .title(title)
                .message(message)
                .relatedEntityId(relatedEntityId)
                .actionUrl(actionUrl)
                .isRead(false)
                .createdAt(LocalDateTime.now())
                .readAt(null)
                .build();

        Notification saved = notificationRepository.save(notification);
        if (emailRecipient) notificationEmailService.deliver(saved);
        return saved;
    }

    public List<Notification> listNotifications(String authenticatedUserId, int limit) {
        validateAuthenticatedUserId(authenticatedUserId);

        int cappedLimit = limit <= 0 ? DEFAULT_LIST_LIMIT : Math.min(limit, MAX_LIST_LIMIT);
        List<Notification> notifications = notificationRepository.findByRecipientUserIdOrderByCreatedAtDesc(
                authenticatedUserId, PageRequest.of(0, cappedLimit));
        notifications.stream()
                .filter(notification -> notification.getNotificationType() == NotificationType.UNKNOWN)
                .forEach(notification -> log.warn("Notification has a type this build does not recognise [notificationId={}]",
                        notification.getId()));
        return notifications;
    }

    public long countUnread(String authenticatedUserId) {
        validateAuthenticatedUserId(authenticatedUserId);

        return notificationRepository.countByRecipientUserIdAndIsReadFalse(authenticatedUserId);
    }

    public Notification markAsRead(String authenticatedUserId, String notificationId) {
        validateAuthenticatedUserId(authenticatedUserId);

        if (!StringUtils.hasText(notificationId)) {
            throw new IllegalArgumentException("Notification ID is required");
        }

        Notification notification = notificationRepository
                .findByIdAndRecipientUserId(notificationId, authenticatedUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found"));

        if (Boolean.TRUE.equals(notification.getIsRead())) {
            return notification;
        }

        LocalDateTime readAt = LocalDateTime.now();
        // Targeted $set: re-saving the whole document would replace an unrecognised stored type with UNKNOWN.
        notificationRepository.markReadByIdAndRecipientUserId(notificationId, authenticatedUserId, readAt);
        notification.setIsRead(true);
        notification.setReadAt(readAt);
        return notification;
    }

    public void markAllAsRead(String authenticatedUserId) {
        validateAuthenticatedUserId(authenticatedUserId);

        notificationRepository.markAllReadByRecipientUserId(authenticatedUserId, LocalDateTime.now());
    }

    private void validateAuthenticatedUserId(String authenticatedUserId) {
        if (!StringUtils.hasText(authenticatedUserId)) {
            throw new IllegalArgumentException("Authenticated user ID is required");
        }
    }

    private void validateCreate(
            String recipientUserId, NotificationType type, String title, String message, String actionUrl) {

        if (!StringUtils.hasText(recipientUserId)) {
            throw new IllegalArgumentException("Recipient user id is required");
        }
        if (type == null || type == NotificationType.UNKNOWN) {
            throw new IllegalArgumentException("Notification type is required");
        }
        if (!StringUtils.hasText(title)) {
            throw new IllegalArgumentException("Notification title is required");
        }
        if (!StringUtils.hasText(message)) {
            throw new IllegalArgumentException("Notification message is required");
        }
        if (!StringUtils.hasText(actionUrl)) {
            throw new IllegalArgumentException("Notification action URL is required");
        }
    }
}
