package com.waypoint.entity;

import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventStatus;
import com.waypoint.enums.EventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "events")
public class Event {

    @Id
    private String id;

    @Indexed
    private String employerProfileId;

    @Indexed(unique = true)
    private String eventCode;

    private String title;

    private String description;

    @Indexed
    private EventType eventType;

    @Indexed
    private EventMode eventMode;

    private String venueName;

    private String address;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private String meetingLink;

    @Indexed(direction = IndexDirection.ASCENDING)
    private LocalDateTime startDateTime;

    private LocalDateTime endDateTime;

    private LocalDateTime registrationDeadline;

    private Integer capacity;

    @Indexed
    private EventStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
