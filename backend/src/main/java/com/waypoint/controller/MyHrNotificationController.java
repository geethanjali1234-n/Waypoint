package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.notification.NotificationResponse;
import com.waypoint.dto.notification.UnreadCountResponse;
import com.waypoint.entity.Notification;
import com.waypoint.enums.UserRole;
import com.waypoint.security.CurrentUserResolver;
import com.waypoint.security.MyHrAuthenticatedUserService;
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
import java.util.Set;

@RestController
@RequestMapping("/api/myhr/notifications")
@RequiredArgsConstructor
public class MyHrNotificationController {

    private static final Set<UserRole> MY_HR_ROLES =
            Set.of(UserRole.COMPANY_ADMIN, UserRole.HR, UserRole.EMPLOYEE);

    private final InAppNotificationService inAppNotificationService;
    private final CurrentUserResolver currentUserResolver;
    private final MyHrAuthenticatedUserService authenticatedUserService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<NotificationResponse>>> getMyNotifications(
            HttpServletRequest request,
            @RequestParam(defaultValue = "50") int limit) {

        String userId = resolveMyHrUserId(request);
        List<NotificationResponse> response = inAppNotificationService.listNotifications(userId, limit).stream()
                .map(this::toResponse)
                .toList();

        return ResponseEntity.ok(new ApiResponse<>(true, "Notifications retrieved successfully", response));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<ApiResponse<UnreadCountResponse>> getUnreadCount(HttpServletRequest request) {
        String userId = resolveMyHrUserId(request);
        long count = inAppNotificationService.countUnread(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Unread count retrieved successfully",
                UnreadCountResponse.builder().count(count).build()));
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<ApiResponse<NotificationResponse>> markAsRead(
            HttpServletRequest request,
            @PathVariable String notificationId) {

        String userId = resolveMyHrUserId(request);
        Notification notification = inAppNotificationService.markAsRead(userId, notificationId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Notification marked as read", toResponse(notification)));
    }

    @PatchMapping("/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(HttpServletRequest request) {
        String userId = resolveMyHrUserId(request);
        inAppNotificationService.markAllAsRead(userId);

        return ResponseEntity.ok(new ApiResponse<>(true, "All notifications marked as read", null));
    }

    private String resolveMyHrUserId(HttpServletRequest request) {
        String userId = currentUserResolver.resolveUserId(request);
        authenticatedUserService.require(userId, MY_HR_ROLES, false);
        return userId;
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
