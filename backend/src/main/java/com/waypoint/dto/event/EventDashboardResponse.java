package com.waypoint.dto.event;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class EventDashboardResponse {

    private long totalEvents;

    private long upcomingEvents;

    private long completedEvents;
}
