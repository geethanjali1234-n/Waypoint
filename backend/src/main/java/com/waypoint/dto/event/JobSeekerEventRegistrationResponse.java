package com.waypoint.dto.event;

import com.waypoint.enums.EventMode;
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
public class JobSeekerEventRegistrationResponse {

    private String id;

    private String eventId;

    private String eventTitle;

    private String companyName;

    private EventType eventType;

    private EventMode eventMode;

    private LocalDateTime startDateTime;

    private RegistrationStatus registrationStatus;

    private LocalDateTime registeredAt;
}
