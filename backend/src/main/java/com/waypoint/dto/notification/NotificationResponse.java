package com.waypoint.dto.notification;

import com.waypoint.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationResponse {

    private String id;
    private NotificationType type;
    private String title;
    private String message;
    private String relatedEntityId;
    private String actionUrl;
    private boolean read;
    private LocalDateTime createdAt;
    private LocalDateTime readAt;
}
