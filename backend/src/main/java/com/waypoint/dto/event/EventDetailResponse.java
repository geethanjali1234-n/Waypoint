package com.waypoint.dto.event;

import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventStatus;
import com.waypoint.enums.EventType;
import com.waypoint.enums.RegistrationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventDetailResponse {

    private String id;
    private String eventCode;
    private String title;
    private String description;
    private EventType eventType;
    private EventMode eventMode;
    private String companyName;
    private String venueName;
    private String address;
    private String meetingLink;
    private LocalDateTime startDateTime;
    private LocalDateTime endDateTime;
    private LocalDateTime registrationDeadline;
    private Integer capacity;
    private long registrationCount;
    private EventStatus status;

    /**
     * The calling job seeker's current registration status for this event.
     * Null when the request has no valid auth token or the caller has never registered.
     */
    private RegistrationStatus callerRegistrationStatus;
}
