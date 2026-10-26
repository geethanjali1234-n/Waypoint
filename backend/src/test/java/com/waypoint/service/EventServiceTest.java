package com.waypoint.service;

import com.waypoint.dto.event.JobSeekerEventRegistrationResponse;
import com.waypoint.dto.event.EventDetailResponse;
import com.waypoint.dto.event.EventRegistrationResponse;
import com.waypoint.dto.event.EventSummaryResponse;
import com.waypoint.dto.event.WeatherResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Event;
import com.waypoint.entity.EventRegistration;
import com.waypoint.entity.User;
import com.waypoint.enums.AttendanceStatus;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventStatus;
import com.waypoint.enums.EventType;
import com.waypoint.enums.RegistrationStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.EventRegistrationRepository;
import com.waypoint.repository.EventRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.security.JwtService;
import com.waypoint.service.geocoding.GeoCoordinates;
import com.waypoint.service.geocoding.GeocodingService;
import io.jsonwebtoken.JwtException;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceTest {

    private static final String AUTH_HEADER = "Bearer valid-token";

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventRegistrationRepository eventRegistrationRepository;

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private JwtService jwtService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private WeatherService weatherService;

    @Mock
    private GeocodingService geocodingService;

    @InjectMocks
    private EventService eventService;

    private Event publishedEvent() {
        return Event.builder()
                .id("event-1")
                .employerProfileId("employer-1")
                .eventCode("EVT-1")
                .title("Career Fair")
                .status(EventStatus.PUBLISHED)
                .build();
    }

    private JobSeekerProfile jobSeekerProfile() {
        return JobSeekerProfile.builder().id("jobSeeker-1").userId("user-1").build();
    }

    private EmployerProfile employerProfile() {
        return EmployerProfile.builder().id("employer-1").userId("employer-user-1").companyName("Acme Corp").build();
    }

    private Event event(String id, String title, EventType eventType, EventMode eventMode,
                         LocalDateTime startDateTime) {
        return Event.builder()
                .id(id)
                .employerProfileId("employer-1")
                .eventCode("EVT-" + id)
                .title(title)
                .description("Description for " + title)
                .eventType(eventType)
                .eventMode(eventMode)
                .venueName("HQ")
                .address("Austin, Texas")
                .startDateTime(startDateTime)
                .endDateTime(startDateTime.plusHours(2))
                .registrationDeadline(startDateTime.minusDays(1))
                .capacity(100)
                .status(EventStatus.PUBLISHED)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private EmployerProfile employerProfile(String id, String companyName) {
        return EmployerProfile.builder()
                .id(id)
                .userId("employer-user-1")
                .companyName(companyName)
                .companyAddress("Austin, Texas")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    private JobSeekerProfile jobSeekerProfile(String id, String userId) {
        return JobSeekerProfile.builder()
                .id(id)
                .userId(userId)
                .firstName("Jordan")
                .lastName("Rivera")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    // ---------- listEvents ----------

    @Test
    void listEventsReturnsMappedSummariesWithCompanyName() {
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));

        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of(event));
        when(employerProfileRepository.findAllById(List.of("employer-1")))
                .thenReturn(List.of(employerProfile("employer-1", "Waypoint Technologies")));

        List<EventSummaryResponse> response = eventService.listEvents(null, null, null, null, null, null, null, null);

        assertThat(response).hasSize(1);
        EventSummaryResponse summary = response.get(0);
        assertThat(summary.getId()).isEqualTo("event-1");
        assertThat(summary.getTitle()).isEqualTo("Career Fair");
        assertThat(summary.getEventType()).isEqualTo(EventType.JOB_FAIR);
        assertThat(summary.getEventMode()).isEqualTo(EventMode.OFFLINE);
        assertThat(summary.getCompanyName()).isEqualTo("Waypoint Technologies");
    }

    @Test
    void listEventsUsesUnknownCompanyNameWhenEmployerProfileMissing() {
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));

        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of(event));
        when(employerProfileRepository.findAllById(List.of("employer-1"))).thenReturn(List.of());

        List<EventSummaryResponse> response = eventService.listEvents(null, null, null, null, null, null, null, null);

        assertThat(response.get(0).getCompanyName()).isEqualTo("Unknown");
    }

    @Test
    void listEventsReturnsEmptyListWhenNoEventsMatch() {
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        List<EventSummaryResponse> response = eventService.listEvents("nothing", null, null, null, null, null, null, null);

        assertThat(response).isEmpty();
    }

    // ---------- listEvents: distance filter/sort ----------

    @Test
    void listEventsFiltersOutInPersonEventsBeyondMaxDistance() {
        Event nearby = event("event-near", "Nearby Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        nearby.setLatitude(new BigDecimal("32.8140"));
        nearby.setLongitude(new BigDecimal("-96.9489"));

        Event faraway = event("event-far", "Faraway Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        faraway.setLatitude(new BigDecimal("40.7128"));
        faraway.setLongitude(new BigDecimal("-74.0060"));

        Event virtual = event("event-virtual", "Virtual Fair", EventType.JOB_FAIR, EventMode.ONLINE,
                LocalDateTime.now().plusDays(3));

        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of(nearby, faraway, virtual));
        when(employerProfileRepository.findAllById(any())).thenReturn(List.of());

        List<EventSummaryResponse> response = eventService.listEvents(
                null, null, null, null, null, new BigDecimal("32.8140"), new BigDecimal("-96.9489"), 50);

        assertThat(response).extracting(EventSummaryResponse::getId)
                .containsExactlyInAnyOrder("event-near", "event-virtual");
    }

    @Test
    void listEventsCityBecomesDistanceCenterAndReplacesAddressTextFilter() {
        // Doesn't contain "Irving" in its address text, but is within range
        // of Irving's geocoded coordinates - must appear only if the text
        // filter was truly replaced, not just ANDed with distance.
        Event nearbyPlano = event("event-plano", "Job Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        nearbyPlano.setAddress("Plano, TX");
        nearbyPlano.setLatitude(new BigDecimal("33.0198"));
        nearbyPlano.setLongitude(new BigDecimal("-96.6989"));

        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of(nearbyPlano));
        when(employerProfileRepository.findAllById(any())).thenReturn(List.of());
        when(geocodingService.geocode("Irving"))
                .thenReturn(Optional.of(new GeoCoordinates(new BigDecimal("32.8140"), new BigDecimal("-96.9489"))));

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);

        List<EventSummaryResponse> response = eventService.listEvents(
                null, "Irving", null, null, null, null, null, 50);

        assertThat(response).extracting(EventSummaryResponse::getId).containsExactly("event-plano");
        verify(geocodingService).geocode("Irving");
        verify(mongoTemplate).find(captor.capture(), eq(Event.class));
        assertThat(andClauses(captor.getValue())).noneMatch(d -> d.containsKey("address"));
    }

    @Test
    void listEventsFallsBackToGpsWhenCityCannotBeGeocoded() {
        Event event = event("event-1", "Job Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        event.setAddress("Somewhere unmatched");
        event.setLatitude(new BigDecimal("32.8140"));
        event.setLongitude(new BigDecimal("-96.9489"));

        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of(event));
        when(employerProfileRepository.findAllById(any())).thenReturn(List.of());
        when(geocodingService.geocode("Nowhereville")).thenReturn(Optional.empty());

        List<EventSummaryResponse> response = eventService.listEvents(
                null, "Nowhereville", null, null, null, new BigDecimal("32.8140"), new BigDecimal("-96.9489"), 50);

        assertThat(response).extracting(EventSummaryResponse::getId).containsExactly("event-1");
    }

    @Test
    void listEventsOrdersInPersonEventsClosestFirstWhenCoordinatesResolvedWithoutMaxDistance() {
        Event nearby = event("event-near", "Nearby Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        nearby.setLatitude(new BigDecimal("32.8140"));
        nearby.setLongitude(new BigDecimal("-96.9489"));

        Event faraway = event("event-far", "Faraway Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(1));
        faraway.setLatitude(new BigDecimal("40.7128"));
        faraway.setLongitude(new BigDecimal("-74.0060"));

        // mongoTemplate already sorts by startDateTime ascending, so the far
        // (earlier-dated) event comes back first from the query - proving the
        // reorder is driven by distance, not left as the date-sorted order.
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of(faraway, nearby));
        when(employerProfileRepository.findAllById(any())).thenReturn(List.of());

        List<EventSummaryResponse> response = eventService.listEvents(
                null, null, null, null, null, new BigDecimal("32.8140"), new BigDecimal("-96.9489"), null);

        assertThat(response).extracting(EventSummaryResponse::getId)
                .containsExactly("event-near", "event-far");
    }

    @Test
    void listEventsKeepsVirtualAndUncoordinatedEventsInDateOrderWhenSorting() {
        Event virtualFirst = event("event-virtual", "Virtual Fair", EventType.JOB_FAIR, EventMode.ONLINE,
                LocalDateTime.now().plusDays(1));
        Event nearby = event("event-near", "Nearby Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(2));
        nearby.setLatitude(new BigDecimal("32.8140"));
        nearby.setLongitude(new BigDecimal("-96.9489"));
        Event faraway = event("event-far", "Faraway Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        faraway.setLatitude(new BigDecimal("40.7128"));
        faraway.setLongitude(new BigDecimal("-74.0060"));

        // Date order from the query: virtual, faraway, nearby. Only the two
        // in-person slots should reorder (nearby before faraway) - the
        // virtual event must stay pinned at index 0, not pulled to the top
        // or bottom of the whole list.
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of(virtualFirst, faraway, nearby));
        when(employerProfileRepository.findAllById(any())).thenReturn(List.of());

        List<EventSummaryResponse> response = eventService.listEvents(
                null, null, null, null, null, new BigDecimal("32.8140"), new BigDecimal("-96.9489"), null);

        assertThat(response).extracting(EventSummaryResponse::getId)
                .containsExactly("event-virtual", "event-near", "event-far");
    }

    @Test
    void listEventsIncludesKeywordFilterInQuery() {
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);

        eventService.listEvents("career", null, null, null, null, null, null, null);

        verify(mongoTemplate).find(captor.capture(), eq(Event.class));
        List<Document> andClauses = andClauses(captor.getValue());
        assertThat(andClauses).anyMatch(d -> d.containsKey("title"));
    }

    @Test
    void listEventsIncludesEventTypeFilterInQuery() {
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);

        eventService.listEvents(null, null, null, EventType.WORKSHOP, null, null, null, null);

        verify(mongoTemplate).find(captor.capture(), eq(Event.class));
        List<Document> andClauses = andClauses(captor.getValue());
        assertThat(andClauses).anyMatch(d -> EventType.WORKSHOP.equals(d.get("eventType")));
    }

    @Test
    void listEventsIncludesEventModeFilterInQuery() {
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);

        eventService.listEvents(null, null, null, null, EventMode.ONLINE, null, null, null);

        verify(mongoTemplate).find(captor.capture(), eq(Event.class));
        List<Document> andClauses = andClauses(captor.getValue());
        assertThat(andClauses).anyMatch(d -> EventMode.ONLINE.equals(d.get("eventMode")));
    }

    @Test
    void listEventsIncludesThisWeekDateFilterInQuery() {
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);

        eventService.listEvents(null, null, "THIS_WEEK", null, null, null, null, null);

        verify(mongoTemplate).find(captor.capture(), eq(Event.class));
        List<Document> andClauses = andClauses(captor.getValue());
        long startDateTimeClauses = andClauses.stream().filter(d -> d.containsKey("startDateTime")).count();
        assertThat(startDateTimeClauses).isEqualTo(2);
    }

    @Test
    void listEventsIncludesThisMonthDateFilterInQuery() {
        when(mongoTemplate.find(any(Query.class), eq(Event.class))).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        ArgumentCaptor<Query> captor = ArgumentCaptor.forClass(Query.class);

        eventService.listEvents(null, null, "THIS_MONTH", null, null, null, null, null);

        verify(mongoTemplate).find(captor.capture(), eq(Event.class));
        List<Document> andClauses = andClauses(captor.getValue());
        long startDateTimeClauses = andClauses.stream().filter(d -> d.containsKey("startDateTime")).count();
        assertThat(startDateTimeClauses).isEqualTo(2);
    }

    @SuppressWarnings("unchecked")
    private List<Document> andClauses(Query query) {
        return (List<Document>) query.getQueryObject().get("$and");
    }

    // ---------- getEventById ----------

    @Test
    void getEventByIdReturnsDetailWithCompanyNameAndRegistrationCount() {
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        EmployerProfile employer = employerProfile("employer-1", "Waypoint Technologies");

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(event));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.of(employer));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(5L);

        EventDetailResponse response = eventService.getEventById("event-1", null);

        assertThat(response.getId()).isEqualTo("event-1");
        assertThat(response.getTitle()).isEqualTo("Career Fair");
        assertThat(response.getCompanyName()).isEqualTo("Waypoint Technologies");
        assertThat(response.getRegistrationCount()).isEqualTo(5L);
        assertThat(response.getCallerRegistrationStatus()).isNull();
    }

    @Test
    void getEventByIdThrowsResourceNotFoundExceptionWhenEventMissing() {
        when(eventRepository.findByIdAndStatus("missing", EventStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getEventById("missing", null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Event not found");
    }

    @Test
    void getEventByIdUsesUnknownCompanyNameWhenEmployerProfileMissing() {
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(event));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.empty());
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);

        EventDetailResponse response = eventService.getEventById("event-1", null);

        assertThat(response.getCompanyName()).isEqualTo("Unknown");
    }

    @Test
    void getEventByIdSetsCallerRegistrationStatusWhenValidAuthHeaderAndRegistrationExists() {
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        JobSeekerProfile jobSeekerProfile = jobSeekerProfile("jobSeeker-1", "user-1");
        EventRegistration registration = EventRegistration.builder()
                .id("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .attendanceStatus(AttendanceStatus.NOT_MARKED)
                .registeredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(event));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.empty());
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(1L);
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.of(registration));

        EventDetailResponse response = eventService.getEventById("event-1", "Bearer valid-token");

        assertThat(response.getCallerRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
    }

    @Test
    void getEventByIdLeavesCallerRegistrationStatusNullWhenNoAuthHeader() {
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(event));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.empty());
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);

        EventDetailResponse response = eventService.getEventById("event-1", null);

        assertThat(response.getCallerRegistrationStatus()).isNull();
        verify(jwtService, never()).extractUserId(any());
    }

    @Test
    void getEventByIdLeavesCallerRegistrationStatusNullWhenTokenInvalid() {
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(event));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.empty());
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);
        when(jwtService.extractUserId("bad-token")).thenThrow(new JwtException("invalid"));

        EventDetailResponse response = eventService.getEventById("event-1", "Bearer bad-token");

        assertThat(response.getCallerRegistrationStatus()).isNull();
    }

    // ---------- getWeatherForecast ----------

    @Test
    void getWeatherForecastReturnsForecastForOfflineEventWithCoordinates() {
        Event offlineEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        offlineEvent.setLatitude(new BigDecimal("30.2672"));
        offlineEvent.setLongitude(new BigDecimal("-97.7431"));

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

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(offlineEvent));
        when(weatherService.getForecastByCoordinates(30.2672, -97.7431, offlineEvent.getStartDateTime()))
                .thenReturn(forecast);

        WeatherResponse response = eventService.getWeatherForecast("event-1");

        assertThat(response).isSameAs(forecast);
    }

    @Test
    void getWeatherForecastReturnsForecastForHybridEventWithCoordinates() {
        Event hybridEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.HYBRID,
                LocalDateTime.now().plusDays(3));
        hybridEvent.setLatitude(new BigDecimal("40.7128"));
        hybridEvent.setLongitude(new BigDecimal("-74.0060"));

        WeatherResponse forecast = WeatherResponse.builder().weather("Clouds").build();

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(hybridEvent));
        when(weatherService.getForecastByCoordinates(40.7128, -74.0060, hybridEvent.getStartDateTime()))
                .thenReturn(forecast);

        WeatherResponse response = eventService.getWeatherForecast("event-1");

        assertThat(response).isSameAs(forecast);
    }

    @Test
    void getWeatherForecastThrowsResourceNotFoundExceptionWhenEventMissing() {
        when(eventRepository.findByIdAndStatus("missing-event", EventStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getWeatherForecast("missing-event"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Event not found");

        verify(weatherService, never()).getForecastByCoordinates(anyDouble(), anyDouble(), any());
    }

    @Test
    void getWeatherForecastThrowsBusinessExceptionWhenEventModeIsOnline() {
        Event onlineEvent = event("event-1", "Webinar", EventType.WEBINAR, EventMode.ONLINE,
                LocalDateTime.now().plusDays(3));
        onlineEvent.setLatitude(new BigDecimal("30.2672"));
        onlineEvent.setLongitude(new BigDecimal("-97.7431"));

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(onlineEvent));

        assertThatThrownBy(() -> eventService.getWeatherForecast("event-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Weather forecasts are only available for in-person or hybrid events");

        verify(weatherService, never()).getForecastByCoordinates(anyDouble(), anyDouble(), any());
    }

    @Test
    void getWeatherForecastThrowsBusinessExceptionWhenLatitudeMissing() {
        Event offlineEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        offlineEvent.setLongitude(new BigDecimal("-97.7431"));

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(offlineEvent));

        assertThatThrownBy(() -> eventService.getWeatherForecast("event-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This event does not have a location to check the weather for");

        verify(weatherService, never()).getForecastByCoordinates(anyDouble(), anyDouble(), any());
    }

    @Test
    void getWeatherForecastThrowsBusinessExceptionWhenLongitudeMissing() {
        Event offlineEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        offlineEvent.setLatitude(new BigDecimal("30.2672"));

        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(offlineEvent));

        assertThatThrownBy(() -> eventService.getWeatherForecast("event-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This event does not have a location to check the weather for");

        verify(weatherService, never()).getForecastByCoordinates(anyDouble(), anyDouble(), any());
    }

    // ---------- registerForEvent ----------

    @Test
    void registerForEvent_newRegistration_notifiesJobSeekerAndEmployer() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile()));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED))
                .thenReturn(Optional.of(publishedEvent()));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.empty());
        when(eventRegistrationRepository.save(any(EventRegistration.class)))
                .thenAnswer(invocation -> {
                    EventRegistration registration = invocation.getArgument(0);
                    registration.setId("registration-1");
                    return registration;
                });
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(User.builder().id("user-1").email("jobSeeker@example.com").build()));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.of(employerProfile()));
        when(userRepository.findById("employer-user-1"))
                .thenReturn(Optional.of(User.builder().id("employer-user-1").email("employer@example.com").build()));

        eventService.registerForEvent("event-1", AUTH_HEADER);

        verify(notificationService, times(1)).notifyEventRegistrationConfirmed(
                eq("user-1"), eq("jobSeeker@example.com"), eq("employer-user-1"), eq("employer@example.com"),
                eq("registration-1"), eq("event-1"), eq("Career Fair"), eq("Acme Corp"), eq(""),
                any(EventEmailDetails.class));
    }

    @Test
    void registerForEvent_reactivatesCancelledRegistration_notifies() {
        EventRegistration cancelled = EventRegistration.builder()
                .id("registration-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.CANCELLED)
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile()));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED))
                .thenReturn(Optional.of(publishedEvent()));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.of(cancelled));
        when(eventRegistrationRepository.save(cancelled)).thenReturn(cancelled);
        when(userRepository.findById("user-1"))
                .thenReturn(Optional.of(User.builder().id("user-1").email("jobSeeker@example.com").build()));
        when(employerProfileRepository.findById("employer-1")).thenReturn(Optional.of(employerProfile()));
        when(userRepository.findById("employer-user-1"))
                .thenReturn(Optional.of(User.builder().id("employer-user-1").email("employer@example.com").build()));

        eventService.registerForEvent("event-1", AUTH_HEADER);

        verify(notificationService, times(1)).notifyEventRegistrationConfirmed(
                eq("user-1"), eq("jobSeeker@example.com"), eq("employer-user-1"), eq("employer@example.com"),
                eq("registration-1"), eq("event-1"), eq("Career Fair"), eq("Acme Corp"), eq(""),
                any(EventEmailDetails.class));
    }

    @Test
    void registerForEvent_alreadyRegistered_doesNotNotify() {
        EventRegistration active = EventRegistration.builder()
                .id("registration-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile()));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED))
                .thenReturn(Optional.of(publishedEvent()));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(1L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.of(active));

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", AUTH_HEADER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("You are already registered for this event");

        verify(eventRegistrationRepository, never()).save(any());
        verify(notificationService, never()).notifyEventRegistrationConfirmed(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerForEvent_deadlinePassed_doesNotNotify() {
        Event expiredEvent = Event.builder()
                .id("event-1")
                .employerProfileId("employer-1")
                .title("Career Fair")
                .status(EventStatus.PUBLISHED)
                .registrationDeadline(LocalDateTime.now().minusDays(1))
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile()));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED))
                .thenReturn(Optional.of(expiredEvent));

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", AUTH_HEADER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Registration deadline has passed");

        verify(eventRegistrationRepository, never()).save(any());
        verify(notificationService, never()).notifyEventRegistrationConfirmed(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerForEvent_atCapacity_doesNotNotify() {
        Event fullEvent = Event.builder()
                .id("event-1")
                .employerProfileId("employer-1")
                .title("Career Fair")
                .status(EventStatus.PUBLISHED)
                .capacity(10)
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile()));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED))
                .thenReturn(Optional.of(fullEvent));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(10L);

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", AUTH_HEADER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This event is at full capacity");

        verify(eventRegistrationRepository, never()).save(any());
        verify(notificationService, never()).notifyEventRegistrationConfirmed(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerForEvent_jobSeekerProfileMissing_doesNotNotify() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", AUTH_HEADER))
                .isInstanceOf(BusinessException.class)
                .hasMessage("JobSeeker profile not found");

        verify(eventRegistrationRepository, never()).save(any());
        verify(notificationService, never()).notifyEventRegistrationConfirmed(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerForEvent_eventNotFound_doesNotNotify() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile()));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", AUTH_HEADER))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Event not found");

        verify(notificationService, never()).notifyEventRegistrationConfirmed(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void notificationPreparationFailure_doesNotFailRegisterForEvent() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(jobSeekerProfile()));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED))
                .thenReturn(Optional.of(publishedEvent()));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.empty());
        when(eventRegistrationRepository.save(any(EventRegistration.class)))
                .thenAnswer(invocation -> {
                    EventRegistration registration = invocation.getArgument(0);
                    registration.setId("registration-1");
                    return registration;
                });
        when(userRepository.findById("user-1")).thenThrow(new RuntimeException("boom"));

        var response = eventService.registerForEvent("event-1", AUTH_HEADER);

        assertThat(response.getRegistrationId()).isEqualTo("registration-1");
        verify(notificationService, never()).notifyEventRegistrationConfirmed(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void registerForEventThrowsBusinessExceptionWhenAuthHeaderMissing() {
        assertThatThrownBy(() -> eventService.registerForEvent("event-1", null))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Authentication required");

        verify(jobSeekerProfileRepository, never()).findByUserId(any());
    }

    @Test
    void registerForEventThrowsBusinessExceptionWhenAuthHeaderMalformed() {
        assertThatThrownBy(() -> eventService.registerForEvent("event-1", "not-bearer"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Authentication required");
    }

    @Test
    void registerForEventThrowsBusinessExceptionWhenTokenInvalid() {
        when(jwtService.extractUserId("bad-token")).thenThrow(new JwtException("invalid"));

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", "Bearer bad-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired token");
    }

    @Test
    void registerForEventThrowsBusinessExceptionWhenJobSeekerProfileMissing() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", "Bearer valid-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("JobSeeker profile not found");

        verify(eventRepository, never()).findByIdAndStatus(any(), any());
    }

    @Test
    void registerForEventThrowsResourceNotFoundExceptionWhenEventMissing() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRepository.findByIdAndStatus("missing-event", EventStatus.PUBLISHED)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.registerForEvent("missing-event", "Bearer valid-token"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Event not found");
    }

    @Test
    void registerForEventThrowsBusinessExceptionWhenRegistrationDeadlinePassed() {
        Event expiredEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        expiredEvent.setRegistrationDeadline(LocalDateTime.now().minusDays(1));

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(expiredEvent));

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", "Bearer valid-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Registration deadline has passed");

        verify(eventRegistrationRepository, never()).save(any());
    }

    @Test
    void registerForEventThrowsBusinessExceptionWhenEventAtFullCapacity() {
        Event fullEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        fullEvent.setCapacity(10);

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(fullEvent));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(10L);

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", "Bearer valid-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("This event is at full capacity");

        verify(eventRegistrationRepository, never()).save(any());
    }

    @Test
    void registerForEventThrowsBusinessExceptionWhenAlreadyRegistered() {
        Event openEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        EventRegistration existing = EventRegistration.builder()
                .id("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(openEvent));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(1L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> eventService.registerForEvent("event-1", "Bearer valid-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("You are already registered for this event");

        verify(eventRegistrationRepository, never()).save(any());
    }

    @Test
    void registerForEventReactivatesCancelledRegistration() {
        Event openEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        EventRegistration cancelled = EventRegistration.builder()
                .id("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.CANCELLED)
                .registeredAt(LocalDateTime.now().minusDays(5))
                .updatedAt(LocalDateTime.now().minusDays(5))
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(openEvent));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.of(cancelled));
        when(eventRegistrationRepository.save(any(EventRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventRegistrationResponse response = eventService.registerForEvent("event-1", "Bearer valid-token");

        assertThat(response.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);

        ArgumentCaptor<EventRegistration> captor = ArgumentCaptor.forClass(EventRegistration.class);
        verify(eventRegistrationRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("reg-1");
        assertThat(captor.getValue().getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
    }

    @Test
    void registerForEventCreatesNewRegistrationWhenNoneExists() {
        Event openEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(openEvent));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.empty());
        when(eventRegistrationRepository.save(any(EventRegistration.class)))
                .thenAnswer(invocation -> {
                    EventRegistration toSave = invocation.getArgument(0);
                    toSave.setId("reg-new");
                    return toSave;
                });

        EventRegistrationResponse response = eventService.registerForEvent("event-1", "Bearer valid-token");

        assertThat(response.getRegistrationId()).isEqualTo("reg-new");
        assertThat(response.getEventId()).isEqualTo("event-1");
        assertThat(response.getJobSeekerProfileId()).isEqualTo("jobSeeker-1");
        assertThat(response.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
    }

    @Test
    void registerForEventAllowsRegistrationWhenCapacityAndDeadlineAreNull() {
        Event unlimitedEvent = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE,
                LocalDateTime.now().plusDays(3));
        unlimitedEvent.setCapacity(null);
        unlimitedEvent.setRegistrationDeadline(null);

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRepository.findByIdAndStatus("event-1", EventStatus.PUBLISHED)).thenReturn(Optional.of(unlimitedEvent));
        when(eventRegistrationRepository.countByEventIdAndRegistrationStatus("event-1", RegistrationStatus.REGISTERED))
                .thenReturn(0L);
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.empty());
        when(eventRegistrationRepository.save(any(EventRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventRegistrationResponse response = eventService.registerForEvent("event-1", "Bearer valid-token");

        assertThat(response.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
    }

    // ---------- cancelRegistration ----------

    @Test
    void cancelRegistrationThrowsBusinessExceptionWhenJobSeekerProfileMissing() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.cancelRegistration("event-1", "Bearer valid-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("JobSeeker profile not found");
    }

    @Test
    void cancelRegistrationThrowsBusinessExceptionWhenNoActiveRegistrationFound() {
        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.cancelRegistration("event-1", "Bearer valid-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No active registration found for this event");
    }

    @Test
    void cancelRegistrationThrowsBusinessExceptionWhenRegistrationAlreadyCancelled() {
        EventRegistration cancelled = EventRegistration.builder()
                .id("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.CANCELLED)
                .registeredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> eventService.cancelRegistration("event-1", "Bearer valid-token"))
                .isInstanceOf(BusinessException.class)
                .hasMessage("No active registration found for this event");

        verify(eventRegistrationRepository, never()).save(any());
    }

    @Test
    void cancelRegistrationCancelsSuccessfullyWhenActiveRegistrationExists() {
        EventRegistration active = EventRegistration.builder()
                .id("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jwtService.extractUserId("valid-token")).thenReturn("user-1");
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRegistrationRepository.findByEventIdAndJobSeekerProfileId("event-1", "jobSeeker-1"))
                .thenReturn(Optional.of(active));
        when(eventRegistrationRepository.save(any(EventRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EventRegistrationResponse response = eventService.cancelRegistration("event-1", "Bearer valid-token");

        assertThat(response.getRegistrationStatus()).isEqualTo(RegistrationStatus.CANCELLED);

        ArgumentCaptor<EventRegistration> captor = ArgumentCaptor.forClass(EventRegistration.class);
        verify(eventRegistrationRepository).save(captor.capture());
        assertThat(captor.getValue().getRegistrationStatus()).isEqualTo(RegistrationStatus.CANCELLED);
    }

    // ---------- getMyRegistrations ----------

    @Test
    void getMyRegistrationsThrowsResourceNotFoundExceptionWhenJobSeekerProfileMissing() {
        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> eventService.getMyRegistrations("user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("JobSeeker profile not found");
    }

    @Test
    void getMyRegistrationsReturnsEmptyListWhenNoRegistrations() {
        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRegistrationRepository
                .findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc("jobSeeker-1", RegistrationStatus.REGISTERED))
                .thenReturn(List.of());
        when(eventRepository.findAllById(List.of())).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        List<JobSeekerEventRegistrationResponse> response = eventService.getMyRegistrations("user-1");

        assertThat(response).isEmpty();
    }

    @Test
    void getMyRegistrationsReturnsMappedListWithEventAndCompanyDetails() {
        LocalDateTime start = LocalDateTime.now().plusDays(3);
        Event event = event("event-1", "Career Fair", EventType.JOB_FAIR, EventMode.OFFLINE, start);
        EventRegistration registration = EventRegistration.builder()
                .id("reg-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRegistrationRepository
                .findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc("jobSeeker-1", RegistrationStatus.REGISTERED))
                .thenReturn(List.of(registration));
        when(eventRepository.findAllById(List.of("event-1"))).thenReturn(List.of(event));
        when(employerProfileRepository.findAllById(List.of("employer-1")))
                .thenReturn(List.of(employerProfile("employer-1", "Waypoint Technologies")));

        List<JobSeekerEventRegistrationResponse> response = eventService.getMyRegistrations("user-1");

        assertThat(response).hasSize(1);
        JobSeekerEventRegistrationResponse dto = response.get(0);
        assertThat(dto.getId()).isEqualTo("reg-1");
        assertThat(dto.getEventId()).isEqualTo("event-1");
        assertThat(dto.getEventTitle()).isEqualTo("Career Fair");
        assertThat(dto.getCompanyName()).isEqualTo("Waypoint Technologies");
        assertThat(dto.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
    }

    @Test
    void getMyRegistrationsHandlesMissingEventGracefully() {
        EventRegistration registration = EventRegistration.builder()
                .id("reg-1")
                .eventId("deleted-event")
                .jobSeekerProfileId("jobSeeker-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1"))
                .thenReturn(Optional.of(jobSeekerProfile("jobSeeker-1", "user-1")));
        when(eventRegistrationRepository
                .findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc("jobSeeker-1", RegistrationStatus.REGISTERED))
                .thenReturn(List.of(registration));
        when(eventRepository.findAllById(List.of("deleted-event"))).thenReturn(List.of());
        when(employerProfileRepository.findAllById(List.of())).thenReturn(List.of());

        List<JobSeekerEventRegistrationResponse> response = eventService.getMyRegistrations("user-1");

        assertThat(response).hasSize(1);
        JobSeekerEventRegistrationResponse dto = response.get(0);
        assertThat(dto.getEventTitle()).isNull();
        assertThat(dto.getCompanyName()).isNull();
    }
}
