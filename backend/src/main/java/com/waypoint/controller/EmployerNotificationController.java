package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.notification.NotificationResponse;
import com.waypoint.dto.notification.UnreadCountResponse;
import com.waypoint.entity.Notification;
import com.waypoint.security.JwtAuthInterceptor;
import com.waypoint.service.InAppNotificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/employer/notifications")
@RequiredArgsConstructor
public class EmployerNotificationController {

    private final InAppNotificationService inAppNotificationService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getMyNotifications(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {

        String userId = authenticatedUserId(request);
        List<NotificationResponse> response = inAppNotificationService.listNotifications(userId, limit).stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(new ApiResponse<>(true, "Notifications retrieved successfully", response));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount(HttpServletRequest request) {
        String userId = authenticatedUserId(request);
        long count = inAppNotificationService.countUnread(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Unread count retrieved successfully",
                UnreadCountResponse.builder().count(count).build()));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            HttpServletRequest request,
            @PathVariable String notificationId) {

        String userId = authenticatedUserId(request);
        Notification notification = inAppNotificationService.markAsRead(userId, notificationId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Notification marked as read", toResponse(notification)));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(HttpServletRequest request) {
        String userId = authenticatedUserId(request);
        inAppNotificationService.markAllAsRead(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "All notifications marked as read", null));
    }

    private String authenticatedUserId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthInterceptor.AUTHENTICATED_USER_ID);
    }

    private NotificationResponse toResponse(Notification notification) {
        return NotificationResponse.builder()
                .id(notification.getId())
                .type(notification.getNotificationType())
                .title(notification.getTitle())
                .message(notification.getMessage())
                .relatedEntityId(notification.getRelatedEntityId())
                .actionUrl(notification.getActionUrl())
                .read(Boolean.TRUE.equals(notification.getIsRead()))
                .createdAt(notification.getCreatedAt())
                .readAt(notification.getReadAt())
                .build();
    }
}
