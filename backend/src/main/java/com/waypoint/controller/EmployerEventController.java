package com.waypoint.controller;

import com.waypoint.common.ApiResponse;
import com.waypoint.dto.event.CreateEventRequest;
import com.waypoint.dto.event.EmployerEventResponse;
import com.waypoint.dto.event.EventDashboardResponse;
import com.waypoint.dto.event.EventRegistrantResponse;
import com.waypoint.dto.event.WeatherResponse;
import com.waypoint.security.JwtAuthInterceptor;
import com.waypoint.service.EmployerEventService;
import com.waypoint.service.WeatherService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/employer/events")
@RequiredArgsConstructor
public class EmployerEventController {

    private final EmployerEventService employerEventService;
    private final WeatherService weatherService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmployerEventResponse>>> getEmployerEvents(HttpServletRequest request) {
        List<EmployerEventResponse> events = employerEventService.getEmployerEvents(authenticatedUserId(request));
        return ResponseEntity.ok(new ApiResponse<>(true, "Employer events retrieved successfully", events));
    }

    @GetMapping("/dashboard")
    public ResponseEntity<ApiResponse<EventDashboardResponse>> getDashboard(HttpServletRequest request) {
        EventDashboardResponse dashboard = employerEventService.getDashboard(authenticatedUserId(request));
        return ResponseEntity.ok(new ApiResponse<>(true, "Event dashboard retrieved successfully", dashboard));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<ApiResponse<EmployerEventResponse>> getEmployerEvent(
            HttpServletRequest request, @PathVariable String eventId) {
        EmployerEventResponse event = employerEventService.getEmployerEvent(authenticatedUserId(request), eventId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Employer event retrieved successfully", event));
    }

    @GetMapping("/{eventId}/registrants")
    public ResponseEntity<ApiResponse<List<EventRegistrantResponse>>> getEventRegistrants(
            HttpServletRequest request, @PathVariable String eventId) {

        List<EventRegistrantResponse> registrants =
                employerEventService.getEventRegistrants(authenticatedUserId(request), eventId);

        return ResponseEntity.ok(new ApiResponse<>(true, "Event registrants retrieved successfully", registrants));
    }

    @GetMapping("/new-code")
    public ResponseEntity<ApiResponse<String>> getNewEventCode(HttpServletRequest request) {
        String eventCode = employerEventService.generateAvailableEventCode(authenticatedUserId(request));
        return ResponseEntity.ok(new ApiResponse<>(true, "Event ID generated successfully", eventCode));
    }

    @GetMapping("/weather")
    public ResponseEntity<ApiResponse<WeatherResponse>> getWeather(
            HttpServletRequest request,
            @RequestParam String city,
            @RequestParam(required = false) String state,
            @RequestParam(defaultValue = "US") String country,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime eventDateTime) {

        authenticatedUserId(request);

        WeatherResponse weather = weatherService.getForecast(city, state, country, eventDateTime);
        return ResponseEntity.ok(new ApiResponse<>(true, "Weather forecast retrieved successfully", weather));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EmployerEventResponse>> createEvent(
            HttpServletRequest request, @Valid @RequestBody CreateEventRequest createEventRequest) {

        EmployerEventResponse event =
                employerEventService.createEvent(authenticatedUserId(request), createEventRequest);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(true, "Event created successfully", event));
    }

    @PutMapping("/{eventId}")
    public ResponseEntity<ApiResponse<EmployerEventResponse>> updateEvent(
            HttpServletRequest request,
            @PathVariable String eventId,
            @Valid @RequestBody CreateEventRequest createEventRequest) {
        EmployerEventResponse event = employerEventService.updateEvent(
                authenticatedUserId(request), eventId, createEventRequest);
        return ResponseEntity.ok(new ApiResponse<>(true, "Event updated successfully", event));
    }

    @PatchMapping("/{eventId}/close")
    public ResponseEntity<ApiResponse<EmployerEventResponse>> closeEvent(
            HttpServletRequest request, @PathVariable String eventId) {
        EmployerEventResponse event = employerEventService.closeEvent(authenticatedUserId(request), eventId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Event closed successfully", event));
    }

    @PatchMapping("/{eventId}/reopen")
    public ResponseEntity<ApiResponse<EmployerEventResponse>> reopenEvent(
            HttpServletRequest request, @PathVariable String eventId) {
        EmployerEventResponse event = employerEventService.reopenEvent(authenticatedUserId(request), eventId);
        return ResponseEntity.ok(new ApiResponse<>(true, "Event reopened successfully", event));
    }

    private String authenticatedUserId(HttpServletRequest request) {
        return (String) request.getAttribute(JwtAuthInterceptor.AUTHENTICATED_USER_ID);
    }
}
