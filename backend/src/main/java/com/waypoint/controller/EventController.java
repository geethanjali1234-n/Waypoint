package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.event.EventDetailResponse;
import com.waypoint.dto.event.EventRegistrationResponse;
import com.waypoint.dto.event.EventSummaryResponse;
import com.waypoint.dto.event.WeatherResponse;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventType;
import com.waypoint.service.EventService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EventSummaryResponse>>> listEvents(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) String dateFilter,
            @RequestParam(required = false) EventType eventType,
            @RequestParam(required = false) EventMode eventMode,
            @RequestParam(required = false) BigDecimal lat,
            @RequestParam(required = false) BigDecimal lng,
            @RequestParam(required = false) Integer maxDistanceMiles) {

        List<EventSummaryResponse> events =
                eventService.listEvents(keyword, location, dateFilter, eventType, eventMode, lat, lng, maxDistanceMiles);
        return ResponseEntity.ok(new ApiResponse<>(true, "Events retrieved successfully", events));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EventDetailResponse>> getEventById(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        EventDetailResponse event = eventService.getEventById(id, authHeader);
        return ResponseEntity.ok(new ApiResponse<>(true, "Event retrieved successfully", event));
    }

    @GetMapping("/{id}/weather")
    public ResponseEntity<ApiResponse<WeatherResponse>> getEventWeather(@PathVariable String id) {
        WeatherResponse weather = eventService.getWeatherForecast(id);
        return ResponseEntity.ok(new ApiResponse<>(true, "Weather forecast retrieved successfully", weather));
    }

    @PostMapping("/{id}/register")
    public ResponseEntity<ApiResponse<EventRegistrationResponse>> registerForEvent(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        EventRegistrationResponse response = eventService.registerForEvent(id, authHeader);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Successfully registered for the event", response));
    }

    @PostMapping("/{id}/cancel-registration")
    public ResponseEntity<ApiResponse<EventRegistrationResponse>> cancelRegistration(
            @PathVariable String id,
            @RequestHeader("Authorization") String authHeader) {

        EventRegistrationResponse response = eventService.cancelRegistration(id, authHeader);
        return ResponseEntity.ok(new ApiResponse<>(true, "Registration cancelled successfully", response));
    }
}
