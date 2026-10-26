package com.waypoint.controller;

import com.waypoint.dto.event.EventDetailResponse;
import com.waypoint.dto.event.EventRegistrationResponse;
import com.waypoint.dto.event.EventSummaryResponse;
import com.waypoint.dto.event.WeatherResponse;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventType;
import com.waypoint.enums.RegistrationStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.security.JwtService;
import com.waypoint.service.EventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(EventController.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EventService eventService;

    @MockitoBean
    private JwtService jwtService;

    private static final String VALID_TOKEN = "Bearer valid-token";

    private EventSummaryResponse sampleSummary() {
        return EventSummaryResponse.builder()
                .id("event-1")
                .title("Career Fair")
                .eventType(EventType.JOB_FAIR)
                .eventMode(EventMode.OFFLINE)
                .companyName("Waypoint Technologies")
                .startDateTime(LocalDateTime.of(2026, 12, 1, 9, 0))
                .build();
    }

    private EventDetailResponse sampleDetail() {
        return EventDetailResponse.builder()
                .id("event-1")
                .eventCode("EVT-event-1")
                .title("Career Fair")
                .description("Description for Career Fair")
                .eventType(EventType.JOB_FAIR)
                .eventMode(EventMode.OFFLINE)
                .companyName("Waypoint Technologies")
                .startDateTime(LocalDateTime.of(2026, 12, 1, 9, 0))
                .registrationCount(5L)
                .build();
    }

    private EventRegistrationResponse sampleRegistration() {
        return EventRegistrationResponse.builder()
                .registrationId("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                .build();
    }

    @Test
    void listEventsReturns200AndExpectedResponse() throws Exception {
        when(eventService.listEvents(null, null, null, null, null, null, null, null)).thenReturn(List.of(sampleSummary()));

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Events retrieved successfully"))
                .andExpect(jsonPath("$.data[0].id").value("event-1"))
                .andExpect(jsonPath("$.data[0].title").value("Career Fair"));
    }

    @Test
    void listEventsBindsAllSupportedQueryParameters() throws Exception {
        when(eventService.listEvents("career", "Austin", "THIS_WEEK", EventType.JOB_FAIR, EventMode.OFFLINE,
                new BigDecimal("30.2672"), new BigDecimal("-97.7431"), 50))
                .thenReturn(List.of(sampleSummary()));

        mockMvc.perform(get("/api/events")
                        .param("keyword", "career")
                        .param("location", "Austin")
                        .param("dateFilter", "THIS_WEEK")
                        .param("eventType", "JOB_FAIR")
                        .param("eventMode", "OFFLINE")
                        .param("lat", "30.2672")
                        .param("lng", "-97.7431")
                        .param("maxDistanceMiles", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(eventService).listEvents("career", "Austin", "THIS_WEEK", EventType.JOB_FAIR, EventMode.OFFLINE,
                new BigDecimal("30.2672"), new BigDecimal("-97.7431"), 50);
    }

    @Test
    void listEventsReturns400ForInvalidEventType() throws Exception {
        mockMvc.perform(get("/api/events").param("eventType", "NOT_A_REAL_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid value for parameter 'eventType'"));

        verifyNoInteractions(eventService);
    }

    @Test
    void listEventsReturnsEmptyListWhenNoEventsMatch() throws Exception {
        when(eventService.listEvents(null, null, null, null, null, null, null, null)).thenReturn(List.of());

        mockMvc.perform(get("/api/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void getEventByIdReturns200AndExpectedResponse() throws Exception {
        when(eventService.getEventById("event-1", null)).thenReturn(sampleDetail());

        mockMvc.perform(get("/api/events/event-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Event retrieved successfully"))
                .andExpect(jsonPath("$.data.id").value("event-1"))
                .andExpect(jsonPath("$.data.companyName").value("Waypoint Technologies"));
    }

    @Test
    void getEventByIdForwardsAuthorizationHeaderWhenPresent() throws Exception {
        when(eventService.getEventById("event-1", VALID_TOKEN)).thenReturn(sampleDetail());

        mockMvc.perform(get("/api/events/event-1").header("Authorization", VALID_TOKEN))
                .andExpect(status().isOk());

        verify(eventService).getEventById("event-1", VALID_TOKEN);
    }

    @Test
    void getEventByIdReturns404WhenEventDoesNotExist() throws Exception {
        when(eventService.getEventById("missing-event", null))
                .thenThrow(new ResourceNotFoundException("Event not found"));

        mockMvc.perform(get("/api/events/missing-event"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Event not found"));
    }

    @Test
    void getEventWeatherReturns200AndExpectedResponse() throws Exception {
        WeatherResponse forecast = WeatherResponse.builder()
                .weather("Clear")
                .description("clear sky")
                .temperature(88.0)
                .feelsLike(90.0)
                .humidity(45)
                .windSpeed(6.5)
                .rainChance(10.0)
                .forecastTime("2026-08-07 15:00:00")
                .build();
        when(eventService.getWeatherForecast("event-1")).thenReturn(forecast);

        mockMvc.perform(get("/api/events/event-1/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Weather forecast retrieved successfully"))
                .andExpect(jsonPath("$.data.weather").value("Clear"))
                .andExpect(jsonPath("$.data.temperature").value(88.0));
    }

    @Test
    void getEventWeatherReturns404WhenEventDoesNotExist() throws Exception {
        when(eventService.getWeatherForecast("missing-event"))
                .thenThrow(new ResourceNotFoundException("Event not found"));

        mockMvc.perform(get("/api/events/missing-event/weather"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Event not found"));
    }

    @Test
    void getEventWeatherReturns400WhenEventIsOnline() throws Exception {
        when(eventService.getWeatherForecast("event-1"))
                .thenThrow(new BusinessException("Weather forecasts are only available for in-person or hybrid events"));

        mockMvc.perform(get("/api/events/event-1/weather"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Weather forecasts are only available for in-person or hybrid events"));
    }

    @Test
    void registerForEventReturns201Created() throws Exception {
        when(eventService.registerForEvent("event-1", VALID_TOKEN)).thenReturn(sampleRegistration());

        mockMvc.perform(post("/api/events/event-1/register").header("Authorization", VALID_TOKEN))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Successfully registered for the event"))
                .andExpect(jsonPath("$.data.registrationId").value("reg-1"))
                .andExpect(jsonPath("$.data.registrationStatus").value("REGISTERED"));

        verify(eventService).registerForEvent("event-1", VALID_TOKEN);
    }

    @Test
    void registerForEventReturns400WhenBusinessExceptionOccurs() throws Exception {
        when(eventService.registerForEvent("event-1", VALID_TOKEN))
                .thenThrow(new BusinessException("You are already registered for this event"));

        mockMvc.perform(post("/api/events/event-1/register").header("Authorization", VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("You are already registered for this event"));
    }

    @Test
    void registerForEventReturns404WhenEventDoesNotExist() throws Exception {
        when(eventService.registerForEvent("missing-event", VALID_TOKEN))
                .thenThrow(new ResourceNotFoundException("Event not found"));

        mockMvc.perform(post("/api/events/missing-event/register").header("Authorization", VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Event not found"));
    }

    @Test
    void cancelRegistrationReturns200AndExpectedResponse() throws Exception {
        EventRegistrationResponse cancelled = EventRegistrationResponse.builder()
                .registrationId("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.CANCELLED)
                .build();
        when(eventService.cancelRegistration("event-1", VALID_TOKEN)).thenReturn(cancelled);

        mockMvc.perform(post("/api/events/event-1/cancel-registration").header("Authorization", VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Registration cancelled successfully"))
                .andExpect(jsonPath("$.data.registrationStatus").value("CANCELLED"));
    }

    @Test
    void cancelRegistrationReturns400WhenBusinessExceptionOccurs() throws Exception {
        when(eventService.cancelRegistration("event-1", VALID_TOKEN))
                .thenThrow(new BusinessException("No active registration found for this event"));

        mockMvc.perform(post("/api/events/event-1/cancel-registration").header("Authorization", VALID_TOKEN))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("No active registration found for this event"));
    }
}
