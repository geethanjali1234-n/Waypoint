package com.waypoint.entity;

import com.waypoint.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "notifications")
@CompoundIndex(name = "recipientUserId_isRead", def = "{'recipientUserId': 1, 'isRead': 1}")
public class Notification {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String recipientUserId;

    private String title;

    private String message;

    private NotificationType notificationType;

    private String relatedEntityType;

    private String relatedEntityId;

    private String actionUrl;

    private Boolean isRead;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime createdAt;

    private LocalDateTime readAt;
}
