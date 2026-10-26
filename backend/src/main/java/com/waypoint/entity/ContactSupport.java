package com.waypoint.entity;

import com.waypoint.enums.SupportPriority;
import com.waypoint.enums.SupportStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "contact_support")
public class ContactSupport {

    @Id
    private String id;

    @Indexed
    private String userId;

    @Indexed
    private SupportPriority priority;

    private String subject;

    private String message;

    @Indexed
    private SupportStatus status;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime resolvedAt;
}
