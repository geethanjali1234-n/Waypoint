package com.waypoint.dto.event;

import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventSummaryResponse {

    private String id;
    private String title;
    private EventType eventType;
    private EventMode eventMode;
    private String companyName;
    private String address;
    private LocalDateTime startDateTime;
    private BigDecimal latitude;
    private BigDecimal longitude;
}
