package com.waypoint.dto.event;

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
public class EventRegistrationResponse {

    private String registrationId;
    private String eventId;
    private String jobSeekerProfileId;
    private RegistrationStatus registrationStatus;
    private LocalDateTime registeredAt;
    private LocalDateTime updatedAt;
}
