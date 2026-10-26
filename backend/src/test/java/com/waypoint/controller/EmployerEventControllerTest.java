package com.waypoint.controller;

import com.waypoint.dto.event.CreateEventRequest;
import com.waypoint.dto.event.EmployerEventResponse;
import com.waypoint.dto.event.EventDashboardResponse;
import com.waypoint.dto.event.EventRegistrantResponse;
import com.waypoint.dto.event.WeatherResponse;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventStatus;
import com.waypoint.enums.EventType;
import com.waypoint.enums.RegistrationStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.security.JwtService;
import com.waypoint.service.EmployerEventService;
import com.waypoint.service.WeatherService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(EmployerEventController.class)
class EmployerEventControllerTest {

    private static final String VALID_TOKEN = "valid-token";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private EmployerEventService employerEventService;

    @MockitoBean
    private WeatherService weatherService;

    @MockitoBean
    private JwtService jwtService;

    private EmployerEventResponse sampleEventResponse() {
        return EmployerEventResponse.builder()
                .id("event-1")
                .eventCode("EVT-ABC12345")
                .title("Career Fair")
                .description("Meet employers")
                .eventType(EventType.WORKSHOP)
                .eventMode(EventMode.ONLINE)
                .meetingLink("https://example.com/meeting")
                .startDateTime(LocalDateTime.now().plusDays(10))
                .endDateTime(LocalDateTime.now().plusDays(10).plusHours(2))
                .registrationDeadline(LocalDateTime.now().plusDays(9))
                .capacity(50)
                .status(EventStatus.PUBLISHED)
                .registrationCount(0L)
                .build();
    }

    private void mockValidEmployerToken() {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("employer-user-1");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("EMPLOYER");
    }

    @Test
    void getEmployerEvents_validEmployerToken_returnsEvents() throws Exception {
        mockValidEmployerToken();
        when(employerEventService.getEmployerEvents("employer-user-1"))
                .thenReturn(List.of(sampleEventResponse()));

        mockMvc.perform(get("/api/employer/events")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].id").value("event-1"));
    }

    @Test
    void getEmployerEvents_missingAuthorizationHeader_returns401() throws Exception {
        mockMvc.perform(get("/api/employer/events"))
                .andExpect(status().isUnauthorized());

        verify(employerEventService, never()).getEmployerEvents(any());
    }

    @Test
    void getEmployerEvents_jobSeekerToken_returns403() throws Exception {
        when(jwtService.extractUserId(VALID_TOKEN)).thenReturn("some-user");
        when(jwtService.extractRole(VALID_TOKEN)).thenReturn("JOB_SEEKER");

        mockMvc.perform(get("/api/employer/events")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isForbidden());

        verify(employerEventService, never()).getEmployerEvents(any());
    }

    @Test
    void getDashboard_validEmployerToken_returnsDashboard() throws Exception {
        mockValidEmployerToken();
        when(employerEventService.getDashboard("employer-user-1"))
                .thenReturn(EventDashboardResponse.builder()
                        .totalEvents(5)
                        .upcomingEvents(2)
                        .completedEvents(1)
                        .build());

        mockMvc.perform(get("/api/employer/events/dashboard")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalEvents").value(5))
                .andExpect(jsonPath("$.data.upcomingEvents").value(2))
                .andExpect(jsonPath("$.data.completedEvents").value(1));
    }

    @Test
    void getEventRegistrants_validEmployerToken_returnsRegistrants() throws Exception {
        mockValidEmployerToken();
        EventRegistrantResponse registrant = EventRegistrantResponse.builder()
                .jobSeekerProfileId("jobSeeker-profile-1")
                .firstName("Jordan")
                .lastName("Rivera")
                .email("jordan@example.com")
                .city("Austin")
                .state("TX")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .build();

        when(employerEventService.getEventRegistrants("employer-user-1", "event-1"))
                .thenReturn(List.of(registrant));

        mockMvc.perform(get("/api/employer/events/event-1/registrants")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].jobSeekerProfileId").value("jobSeeker-profile-1"))
                .andExpect(jsonPath("$.data[0].email").value("jordan@example.com"));
    }

    @Test
    void getEventRegistrants_eventNotFoundOrNotOwned_returns404() throws Exception {
        mockValidEmployerToken();
        when(employerEventService.getEventRegistrants("employer-user-1", "event-1"))
                .thenThrow(new ResourceNotFoundException("Event was not found"));

        mockMvc.perform(get("/api/employer/events/event-1/registrants")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Event was not found"));
    }

    @Test
    void getNewEventCode_validEmployerToken_returnsCode() throws Exception {
        mockValidEmployerToken();
        when(employerEventService.generateAvailableEventCode("employer-user-1")).thenReturn("EVT-ABC12345");

        mockMvc.perform(get("/api/employer/events/new-code")
                        .header("Authorization", "Bearer " + VALID_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value("EVT-ABC12345"));
    }

    @Test
    void createEvent_validRequest_returns201() throws Exception {
        mockValidEmployerToken();
        when(employerEventService.createEvent(eq("employer-user-1"), any(CreateEventRequest.class)))
                .thenReturn(sampleEventResponse());

        CreateEventRequest request = new CreateEventRequest();
        request.setTitle("Career Fair");
        request.setDescription("Meet employers");
        request.setEventType(EventType.WORKSHOP);
        request.setEventMode(EventMode.ONLINE);
        request.setMeetingLink("https://example.com/meeting");
        request.setStartDateTime(LocalDateTime.now().plusDays(10));
        request.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(2));
        request.setRegistrationDeadline(LocalDateTime.now().plusDays(9));
        request.setCapacity(50);

        mockMvc.perform(post("/api/employer/events")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value("event-1"));
    }

    @Test
    void createEvent_blankTitle_returns400() throws Exception {
        mockValidEmployerToken();

        CreateEventRequest request = new CreateEventRequest();
        request.setTitle("");
        request.setDescription("Meet employers");
        request.setEventType(EventType.WORKSHOP);
        request.setEventMode(EventMode.ONLINE);
        request.setMeetingLink("https://example.com/meeting");
        request.setStartDateTime(LocalDateTime.now().plusDays(10));
        request.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(2));
        request.setRegistrationDeadline(LocalDateTime.now().plusDays(9));
        request.setCapacity(50);

        mockMvc.perform(post("/api/employer/events")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(employerEventService, never()).createEvent(anyString(), any());
    }

    @Test
    void createEvent_serviceRejectsIncompleteEmployerProfile_returns400() throws Exception {
        mockValidEmployerToken();
        when(employerEventService.createEvent(eq("employer-user-1"), any(CreateEventRequest.class)))
                .thenThrow(new BusinessException(
                        "Complete the required employer profile information before creating an event"));

        CreateEventRequest request = new CreateEventRequest();
        request.setTitle("Career Fair");
        request.setDescription("Meet employers");
        request.setEventType(EventType.WORKSHOP);
        request.setEventMode(EventMode.ONLINE);
        request.setMeetingLink("https://example.com/meeting");
        request.setStartDateTime(LocalDateTime.now().plusDays(10));
        request.setEndDateTime(LocalDateTime.now().plusDays(10).plusHours(2));
        request.setRegistrationDeadline(LocalDateTime.now().plusDays(9));
        request.setCapacity(50);

        mockMvc.perform(post("/api/employer/events")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Complete the required employer profile information before creating an event"));
    }

    @Test
    void getWeather_validEmployerToken_returnsForecast() throws Exception {
        mockValidEmployerToken();
        when(weatherService.getForecast(eq("Austin"), eq("TX"), eq("US"), any(LocalDateTime.class)))
                .thenReturn(WeatherResponse.builder()
                        .city("Austin")
                        .country("US")
                        .weather("Clear")
                        .description("clear sky")
                        .temperature(95.5)
                        .feelsLike(99.1)
                        .humidity(40)
                        .windSpeed(8.5)
                        .rainChance(10.0)
                        .forecastTime("2026-08-14 12:00:00")
                        .build());

        mockMvc.perform(get("/api/employer/events/weather")
                        .header("Authorization", "Bearer " + VALID_TOKEN)
                        .param("city", "Austin")
                        .param("state", "TX")
                        .param("country", "US")
                        .param("eventDateTime", "2026-08-14T12:00:00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.city").value("Austin"))
                .andExpect(jsonPath("$.data.weather").value("Clear"));
    }
}
