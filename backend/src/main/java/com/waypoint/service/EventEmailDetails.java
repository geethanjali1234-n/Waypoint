package com.waypoint.service;

import com.waypoint.entity.Event;
import com.waypoint.enums.EventMode;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

import static com.waypoint.util.DisplayTitleFormatter.format;

/** Event values needed to present schedule and location details in email. */
@Value
@Builder
public class EventEmailDetails {
    String title;
    EventMode eventMode;
    String venueName;
    String address;
    String meetingLink;
    LocalDateTime startDateTime;
    LocalDateTime endDateTime;

    public static EventEmailDetails from(Event event) {
        if (event == null) {
            return null;
        }
        return EventEmailDetails.builder()
                .title(format(event.getTitle()))
                .eventMode(event.getEventMode())
                .venueName(event.getVenueName())
                .address(event.getAddress())
                .meetingLink(event.getMeetingLink())
                .startDateTime(event.getStartDateTime())
                .endDateTime(event.getEndDateTime())
                .build();
    }
}
