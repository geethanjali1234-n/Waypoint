package com.waypoint.dto.event;

import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateEventRequest {

    @NotBlank
    private String title;

    @NotBlank
    private String description;

    @NotNull
    private EventType eventType;

    @NotNull
    private EventMode eventMode;

    private String venueName;

    private String address;

    private String meetingLink;

    @NotNull
    private LocalDateTime startDateTime;

    @NotNull
    private LocalDateTime endDateTime;

    @NotNull
    private LocalDateTime registrationDeadline;

    @NotNull
    @Positive
    private Integer capacity;
}
