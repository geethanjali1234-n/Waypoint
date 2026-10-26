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
import com.waypoint.util.GeoDistanceUtils;
import io.jsonwebtoken.JwtException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.waypoint.util.DisplayTitleFormatter.format;

@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private final EventRepository eventRepository;
    private final EventRegistrationRepository eventRegistrationRepository;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final MongoTemplate mongoTemplate;
    private final JwtService jwtService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final WeatherService weatherService;
    private final GeocodingService geocodingService;

    public List<EventSummaryResponse> listEvents(String keyword, String location, String dateFilter,
                                                  EventType eventType, EventMode eventMode,
                                                  BigDecimal lat, BigDecimal lng, Integer maxDistanceMiles) {
        LocalDateTime now = LocalDateTime.now();

        // When a distance filter and a city are both supplied, the city becomes
        // the radius center and fully replaces the plain-text address filter -
        // mirrors JobPortalService's resolveDistanceContext so "Irving" + 50mi
        // means "within 50 miles of Irving," not "address contains Irving."
        DistanceContext distanceContext = resolveDistanceContext(location, lat, lng, maxDistanceMiles);

        List<Criteria> criteriaList = new ArrayList<>();
        criteriaList.add(Criteria.where("status").is(EventStatus.PUBLISHED));
        criteriaList.add(Criteria.where("startDateTime").gte(now));

        if (keyword != null && !keyword.isBlank()) {
            criteriaList.add(Criteria.where("title").regex(keyword.trim(), "i"));
        }
        if (location != null && !location.isBlank() && !distanceContext.skipLocationTextFilter()) {
            // Case-insensitive substring match against the stored city/state string
            // (e.g. "Austin, Texas") — mirrors JobService's location filter so a
            // city name matches without requiring an exact street address.
            criteriaList.add(Criteria.where("address").regex(location.trim(), "i"));
        }
        if (eventType != null) {
            criteriaList.add(Criteria.where("eventType").is(eventType));
        }
        if (eventMode != null) {
            criteriaList.add(Criteria.where("eventMode").is(eventMode));
        }
        if ("THIS_WEEK".equalsIgnoreCase(dateFilter)) {
            LocalDateTime endOfWeek = now
                    .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                    .withHour(23).withMinute(59).withSecond(59).withNano(0);
            criteriaList.add(Criteria.where("startDateTime").lte(endOfWeek));
        } else if ("THIS_MONTH".equalsIgnoreCase(dateFilter)) {
            LocalDateTime endOfMonth = now
                    .with(TemporalAdjusters.lastDayOfMonth())
                    .withHour(23).withMinute(59).withSecond(59).withNano(0);
            criteriaList.add(Criteria.where("startDateTime").lte(endOfMonth));
        }

        Query query = new Query(new Criteria().andOperator(criteriaList.toArray(new Criteria[0])))
                .with(Sort.by(Sort.Direction.ASC, "startDateTime"));

        List<Event> events = mongoTemplate.find(query, Event.class);

        if (maxDistanceMiles != null) {
            events = events.stream()
                    .filter(event -> isWithinDistance(event, distanceContext.lat(), distanceContext.lng(), maxDistanceMiles))
                    .collect(Collectors.toList());
        }

        events = applyClosestFirstOrdering(events, distanceContext.lat(), distanceContext.lng());

        // Batch-load employer profiles to avoid N+1
        List<String> employerIds = events.stream()
                .map(Event::getEmployerProfileId)
                .distinct()
                .collect(Collectors.toList());

        Map<String, EmployerProfile> employerMap = employerProfileRepository.findAllById(employerIds)
                .stream()
                .collect(Collectors.toMap(EmployerProfile::getId, Function.identity()));

        return events.stream()
                .map(event -> EventSummaryResponse.builder()
                        .id(event.getId())
                        .title(format(event.getTitle()))
                        .eventType(event.getEventType())
                        .eventMode(event.getEventMode())
                        .companyName(Optional.ofNullable(employerMap.get(event.getEmployerProfileId()))
                                .map(EmployerProfile::getCompanyName)
                                .orElse("Unknown"))
                        .address(event.getAddress())
                        .latitude(event.getLatitude())
                        .longitude(event.getLongitude())
                        .startDateTime(event.getStartDateTime())
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * No distance filter is active unless lat/lng/maxDistanceMiles are all
     * supplied - in that case every event passes through untouched. When
     * active, virtual events always pass (no commute to measure), events
     * without coordinates are excluded, and everything else is measured
     * against maxDistanceMiles.
     */
    private boolean isWithinDistance(Event event, BigDecimal lat, BigDecimal lng, Integer maxDistanceMiles) {
        if (lat == null || lng == null || maxDistanceMiles == null) {
            return true;
        }
        if (event.getEventMode() == EventMode.ONLINE) {
            return true;
        }
        if (event.getLatitude() == null || event.getLongitude() == null) {
            return false;
        }
        return GeoDistanceUtils.haversineMiles(lat, lng, event.getLatitude(), event.getLongitude()) <= maxDistanceMiles;
    }

    /**
     * When a distance center and a city are both supplied, the city becomes
     * the radius center and fully replaces the plain-text address filter -
     * an event's address no longer needs to literally contain the searched
     * city text, it only needs to be within range of it. Falls back to the
     * job seeker's GPS coordinates, with the original text filter still
     * active, whenever no city is given or the given city can't be geocoded.
     */
    private DistanceContext resolveDistanceContext(
            String location, BigDecimal lat, BigDecimal lng, Integer maxDistanceMiles) {
        if (maxDistanceMiles != null && location != null && !location.isBlank()) {
            Optional<GeoCoordinates> cityCoordinates = geocodingService.geocode(location);
            if (cityCoordinates.isPresent()) {
                return new DistanceContext(cityCoordinates.get().latitude(), cityCoordinates.get().longitude(), true);
            }
        }
        return new DistanceContext(lat, lng, false);
    }

    private record DistanceContext(BigDecimal lat, BigDecimal lng, boolean skipLocationTextFilter) {
    }

    /**
     * Reorders events with a known distance (in-person, geocoded) closest
     * first, while virtual events and any without coordinates keep their
     * original date-ascending position instead of being pulled to either
     * end - they're simply not distance-sortable, so they stay wherever the
     * date-based query already put them.
     */
    private List<Event> applyClosestFirstOrdering(List<Event> events, BigDecimal centerLat, BigDecimal centerLng) {
        if (centerLat == null || centerLng == null) {
            return events;
        }

        List<Integer> sortableIndices = new ArrayList<>();
        List<Event> sortableEvents = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            if (event.getEventMode() != EventMode.ONLINE
                    && event.getLatitude() != null && event.getLongitude() != null) {
                sortableIndices.add(i);
                sortableEvents.add(event);
            }
        }

        if (sortableEvents.isEmpty()) {
            return events;
        }

        sortableEvents.sort(Comparator.comparingDouble(event ->
                GeoDistanceUtils.haversineMiles(centerLat, centerLng, event.getLatitude(), event.getLongitude())));

        List<Event> result = new ArrayList<>(events);
        for (int i = 0; i < sortableIndices.size(); i++) {
            result.set(sortableIndices.get(i), sortableEvents.get(i));
        }
        return result;
    }

    public EventDetailResponse getEventById(String id, String authHeader) {
        Event event = eventRepository
                .findByIdAndStatus(id, EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));

        EmployerProfile employerProfile = employerProfileRepository
                .findById(event.getEmployerProfileId())
                .orElse(null);

        long registrationCount = eventRegistrationRepository
                .countByEventIdAndRegistrationStatus(event.getId(), RegistrationStatus.REGISTERED);

        RegistrationStatus callerRegistrationStatus = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            try {
                String userId = extractUserId(authHeader);
                callerRegistrationStatus = jobSeekerProfileRepository.findByUserId(userId)
                        .flatMap(profile -> eventRegistrationRepository
                                .findByEventIdAndJobSeekerProfileId(event.getId(), profile.getId()))
                        .map(EventRegistration::getRegistrationStatus)
                        .orElse(null);
            } catch (Exception ignored) {
                // Invalid or expired token — treat caller as unauthenticated
            }
        }

        return buildDetailResponse(event, employerProfile, registrationCount, callerRegistrationStatus);
    }

    public WeatherResponse getWeatherForecast(String id) {
        Event event = eventRepository.findByIdAndStatus(id, EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));

        if (event.getEventMode() == EventMode.ONLINE) {
            throw new BusinessException("Weather forecasts are only available for in-person or hybrid events");
        }

        if (event.getLatitude() == null || event.getLongitude() == null) {
            throw new BusinessException("This event does not have a location to check the weather for");
        }

        return weatherService.getForecastByCoordinates(
                event.getLatitude().doubleValue(),
                event.getLongitude().doubleValue(),
                event.getStartDateTime());
    }

    public EventRegistrationResponse registerForEvent(String eventId, String authHeader) {
        String userId = extractUserId(authHeader);

        JobSeekerProfile jobSeekerProfile = jobSeekerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("JobSeeker profile not found"));

        Event event = eventRepository.findByIdAndStatus(eventId, EventStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Event not found"));

        if (event.getRegistrationDeadline() != null
                && LocalDateTime.now().isAfter(event.getRegistrationDeadline())) {
            throw new BusinessException("Registration deadline has passed");
        }

        long registrationCount = eventRegistrationRepository
                .countByEventIdAndRegistrationStatus(eventId, RegistrationStatus.REGISTERED);

        if (event.getCapacity() != null && registrationCount >= event.getCapacity()) {
            throw new BusinessException("This event is at full capacity");
        }

        Optional<EventRegistration> existing = eventRegistrationRepository
                .findByEventIdAndJobSeekerProfileId(eventId, jobSeekerProfile.getId());

        LocalDateTime now = LocalDateTime.now();

        if (existing.isPresent()) {
            EventRegistration registration = existing.get();
            if (registration.getRegistrationStatus() == RegistrationStatus.REGISTERED) {
                throw new BusinessException("You are already registered for this event");
            }
            registration.setRegistrationStatus(RegistrationStatus.REGISTERED);
            registration.setUpdatedAt(now);
            EventRegistration savedRegistration = eventRegistrationRepository.save(registration);
            sendEventRegistrationConfirmedNotification(jobSeekerProfile, event, savedRegistration);
            return toRegistrationResponse(savedRegistration);
        }

        EventRegistration registration = EventRegistration.builder()
                .eventId(eventId)
                .jobSeekerProfileId(jobSeekerProfile.getId())
                .registrationStatus(RegistrationStatus.REGISTERED)
                .attendanceStatus(AttendanceStatus.NOT_MARKED)
                .registeredAt(now)
                .updatedAt(now)
                .build();

        EventRegistration savedRegistration = eventRegistrationRepository.save(registration);
        sendEventRegistrationConfirmedNotification(jobSeekerProfile, event, savedRegistration);
        return toRegistrationResponse(savedRegistration);
    }

    private void sendEventRegistrationConfirmedNotification(
            JobSeekerProfile jobSeekerProfile, Event event, EventRegistration savedRegistration) {
        try {
            String jobSeekerEmail = userRepository.findById(jobSeekerProfile.getUserId())
                    .map(User::getEmail)
                    .orElse(null);

            EmployerProfile employerProfile = employerProfileRepository.findById(event.getEmployerProfileId())
                    .orElse(null);
            String employerEmail = employerProfile != null
                    ? userRepository.findById(employerProfile.getUserId()).map(User::getEmail).orElse(null)
                    : null;
            String companyName = employerProfile != null ? employerProfile.getCompanyName() : null;

            notificationService.notifyEventRegistrationConfirmed(
                    jobSeekerProfile.getUserId(),
                    jobSeekerEmail,
                    employerProfile != null ? employerProfile.getUserId() : null,
                    employerEmail,
                    savedRegistration.getId(),
                    event.getId(),
                    format(event.getTitle()),
                    companyName,
                    jobSeekerDisplayName(jobSeekerProfile),
                    EventEmailDetails.from(event));
        } catch (RuntimeException exception) {
            log.warn("Failed to prepare event-registration-confirmed notification [exceptionType={}]",
                    exception.getClass().getSimpleName());
        }
    }

    public EventRegistrationResponse cancelRegistration(String eventId, String authHeader) {
        String userId = extractUserId(authHeader);

        JobSeekerProfile jobSeekerProfile = jobSeekerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new BusinessException("JobSeeker profile not found"));

        EventRegistration registration = eventRegistrationRepository
                .findByEventIdAndJobSeekerProfileId(eventId, jobSeekerProfile.getId())
                .filter(r -> r.getRegistrationStatus() == RegistrationStatus.REGISTERED)
                .orElseThrow(() -> new BusinessException("No active registration found for this event"));

        LocalDateTime now = LocalDateTime.now();
        registration.setRegistrationStatus(RegistrationStatus.CANCELLED);
        registration.setUpdatedAt(now);

        EventRegistration savedRegistration = eventRegistrationRepository.save(registration);
        sendEventRegistrationCancelledNotification(jobSeekerProfile, savedRegistration);
        return toRegistrationResponse(savedRegistration);
    }

    private void sendEventRegistrationCancelledNotification(
            JobSeekerProfile jobSeekerProfile, EventRegistration savedRegistration) {
        try {
            Event event = eventRepository.findById(savedRegistration.getEventId()).orElse(null);
            if (event == null) {
                return;
            }

            String jobSeekerEmail = userRepository.findById(jobSeekerProfile.getUserId())
                    .map(User::getEmail)
                    .orElse(null);
            EmployerProfile employerProfile = employerProfileRepository.findById(event.getEmployerProfileId())
                    .orElse(null);
            String employerEmail = employerProfile != null
                    ? userRepository.findById(employerProfile.getUserId()).map(User::getEmail).orElse(null)
                    : null;

            notificationService.notifyEventRegistrationCancelled(
                    jobSeekerProfile.getUserId(),
                    jobSeekerEmail,
                    employerProfile != null ? employerProfile.getUserId() : null,
                    employerEmail,
                    savedRegistration.getId(),
                    event.getId(),
                    format(event.getTitle()),
                    jobSeekerDisplayName(jobSeekerProfile),
                    EventEmailDetails.from(event));
        } catch (RuntimeException exception) {
            log.warn("Failed to prepare event-registration-cancelled notification [exceptionType={}]",
                    exception.getClass().getSimpleName());
        }
    }

    private String jobSeekerDisplayName(JobSeekerProfile jobSeekerProfile) {
        return java.util.stream.Stream.of(jobSeekerProfile.getFirstName(), jobSeekerProfile.getLastName())
                .filter(name -> name != null && !name.isBlank())
                .collect(Collectors.joining(" "));
    }

    public List<JobSeekerEventRegistrationResponse> getMyRegistrations(String userId) {
        JobSeekerProfile jobSeekerProfile = jobSeekerProfileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("JobSeeker profile not found"));

        List<EventRegistration> registrations = eventRegistrationRepository
                .findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
                        jobSeekerProfile.getId(), RegistrationStatus.REGISTERED);

        List<String> eventIds = registrations.stream().map(EventRegistration::getEventId).distinct().toList();
        Map<String, Event> eventsById = eventRepository.findAllById(eventIds).stream()
                .collect(Collectors.toMap(Event::getId, Function.identity()));

        List<String> employerProfileIds = eventsById.values().stream()
                .map(Event::getEmployerProfileId).distinct().toList();
        Map<String, String> companyNamesByEmployerProfileId = employerProfileRepository.findAllById(employerProfileIds)
                .stream()
                .collect(Collectors.toMap(EmployerProfile::getId, EmployerProfile::getCompanyName));

        return registrations.stream()
                .map(registration -> {
                    Event event = eventsById.get(registration.getEventId());
                    String companyName = event != null
                            ? companyNamesByEmployerProfileId.get(event.getEmployerProfileId())
                            : null;
                    return toJobSeekerEventRegistrationResponse(registration, event, companyName);
                })
                .toList();
    }

    private JobSeekerEventRegistrationResponse toJobSeekerEventRegistrationResponse(
            EventRegistration registration, Event event, String companyName) {
        return JobSeekerEventRegistrationResponse.builder()
                .id(registration.getId())
                .eventId(registration.getEventId())
                .eventTitle(event != null ? format(event.getTitle()) : null)
                .companyName(companyName)
                .eventType(event != null ? event.getEventType() : null)
                .eventMode(event != null ? event.getEventMode() : null)
                .startDateTime(event != null ? event.getStartDateTime() : null)
                .registrationStatus(registration.getRegistrationStatus())
                .registeredAt(registration.getRegisteredAt())
                .build();
    }

    private String extractUserId(String authHeader) {
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            throw new BusinessException("Authentication required");
        }
        try {
            String token = authHeader.substring(7);
            return jwtService.extractUserId(token);
        } catch (JwtException e) {
            throw new BusinessException("Invalid or expired token");
        }
    }

    private EventDetailResponse buildDetailResponse(Event event, EmployerProfile employerProfile,
                                                     long registrationCount,
                                                     RegistrationStatus callerRegistrationStatus) {
        return EventDetailResponse.builder()
                .id(event.getId())
                .eventCode(event.getEventCode())
                .title(format(event.getTitle()))
                .description(event.getDescription())
                .eventType(event.getEventType())
                .eventMode(event.getEventMode())
                .companyName(employerProfile != null ? employerProfile.getCompanyName() : "Unknown")
                .venueName(event.getVenueName())
                .address(event.getAddress())
                .meetingLink(event.getMeetingLink())
                .startDateTime(event.getStartDateTime())
                .endDateTime(event.getEndDateTime())
                .registrationDeadline(event.getRegistrationDeadline())
                .capacity(event.getCapacity())
                .registrationCount(registrationCount)
                .status(event.getStatus())
                .callerRegistrationStatus(callerRegistrationStatus)
                .build();
    }

    private EventRegistrationResponse toRegistrationResponse(EventRegistration registration) {
        return EventRegistrationResponse.builder()
                .registrationId(registration.getId())
                .eventId(registration.getEventId())
                .jobSeekerProfileId(registration.getJobSeekerProfileId())
                .registrationStatus(registration.getRegistrationStatus())
                .registeredAt(registration.getRegisteredAt())
                .updatedAt(registration.getUpdatedAt())
                .build();
    }
}
