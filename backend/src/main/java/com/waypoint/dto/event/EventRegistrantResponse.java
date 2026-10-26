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
public class EventRegistrantResponse {

    private String jobSeekerProfileId;

    private String firstName;

    private String lastName;

    private String email;

    private String city;

    private String state;

    private RegistrationStatus registrationStatus;

    private LocalDateTime registeredAt;
}
