package com.waypoint.dto.event;

import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventStatus;
import com.waypoint.enums.EventType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class EmployerEventResponse {

    private String id;

    private String eventCode;

    private String title;

    private String description;

    private EventType eventType;

    private EventMode eventMode;

    private String venueName;

    private String address;

    private String meetingLink;

    private LocalDateTime startDateTime;

    private LocalDateTime endDateTime;

    private LocalDateTime registrationDeadline;

    private Integer capacity;

    private EventStatus status;

    private Long registrationCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
