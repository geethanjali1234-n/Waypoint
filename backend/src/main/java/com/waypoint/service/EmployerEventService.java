package com.waypoint.service;

import com.waypoint.dto.event.CreateEventRequest;
import com.waypoint.dto.event.EventDashboardResponse;
import com.waypoint.dto.event.EventRegistrantResponse;
import com.waypoint.dto.event.EmployerEventResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Event;
import com.waypoint.entity.User;
import com.waypoint.enums.EventMode;
import com.waypoint.enums.EventStatus;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.JobSeekerProfileRepository;
import com.waypoint.repository.EmployerProfileRepository;
import com.waypoint.repository.EventRegistrationRepository;
import com.waypoint.repository.EventRepository;
import com.waypoint.repository.UserRepository;
import com.waypoint.service.geocoding.GeoCoordinates;
import com.waypoint.service.geocoding.GeocodingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.waypoint.util.DisplayTitleFormatter.format;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmployerEventService {

    private final EventRepository eventRepository;
    private final EventRegistrationRepository eventRegistrationRepository;
    private final EmployerProfileRepository employerProfileRepository;
    private final JobSeekerProfileRepository jobSeekerProfileRepository;
    private final UserRepository userRepository;
    private final NotificationService notificationService;
    private final GeocodingService geocodingService;

    public List<EmployerEventResponse> getEmployerEvents(String employerUserId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);

        return eventRepository
                .findByEmployerProfileIdOrderByStartDateTimeAsc(employerProfile.getId())
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public EventDashboardResponse getDashboard(String employerUserId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        String employerProfileId = employerProfile.getId();

        return EventDashboardResponse.builder()
                .totalEvents(eventRepository.countByEmployerProfileId(employerProfileId))
                .upcomingEvents(eventRepository.countByEmployerProfileIdAndStartDateTimeAfter(
                        employerProfileId, LocalDateTime.now()))
                .completedEvents(eventRepository.countByEmployerProfileIdAndStatus(
                        employerProfileId, EventStatus.COMPLETED))
                .build();
    }

    public EmployerEventResponse getEmployerEvent(String employerUserId, String eventId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        return toResponse(findOwnedEvent(eventId, employerProfile.getId()));
    }

    public String generateAvailableEventCode(String employerUserId) {
        findEmployerProfile(employerUserId);
        return generateEventCode();
    }

    public EmployerEventResponse createEvent(String employerUserId, CreateEventRequest request) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);

        validateEmployerProfile(employerProfile);
        validateEventRequest(request);

        LocalDateTime now = LocalDateTime.now();

        Event event = Event.builder()
                .employerProfileId(employerProfile.getId())
                .eventCode(generateEventCode())
                .status(EventStatus.PUBLISHED)
                .createdAt(now)
                .updatedAt(now)
                .build();

        applyRequest(event, request);

        return toResponse(eventRepository.save(event));
    }

    public EmployerEventResponse updateEvent(
            String employerUserId, String eventId, CreateEventRequest request) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        Event event = findOwnedEvent(eventId, employerProfile.getId());

        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessException("Only published events can be edited");
        }

        validateEventRequest(request);
        boolean detailsChanged = eventDetailsChanged(event, request);
        applyRequest(event, request);
        event.setUpdatedAt(LocalDateTime.now());
        Event savedEvent = eventRepository.save(event);
        if (detailsChanged) {
            notifyRegisteredJobSeekersOfEventUpdate(savedEvent);
        }
        return toResponse(savedEvent);
    }

    public EmployerEventResponse closeEvent(String employerUserId, String eventId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        Event event = findOwnedEvent(eventId, employerProfile.getId());

        if (event.getStatus() != EventStatus.PUBLISHED) {
            throw new BusinessException("Only published events can be closed");
        }

        event.setStatus(EventStatus.CANCELLED);
        event.setUpdatedAt(LocalDateTime.now());
        return toResponse(eventRepository.save(event));
    }

    public EmployerEventResponse reopenEvent(String employerUserId, String eventId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);
        Event event = findOwnedEvent(eventId, employerProfile.getId());

        if (event.getStatus() != EventStatus.CANCELLED) {
            throw new BusinessException("Only cancelled events can be reopened");
        }

        LocalDateTime now = LocalDateTime.now();
        if (event.getStartDateTime() == null || !event.getStartDateTime().isAfter(now)) {
            throw new BusinessException("An event cannot be reopened after its start time");
        }
        if (event.getRegistrationDeadline() == null || !event.getRegistrationDeadline().isAfter(now)) {
            throw new BusinessException("An event cannot be reopened after its registration deadline");
        }

        event.setStatus(EventStatus.PUBLISHED);
        event.setUpdatedAt(now);
        return toResponse(eventRepository.save(event));
    }

    public List<EventRegistrantResponse> getEventRegistrants(String employerUserId, String eventId) {
        EmployerProfile employerProfile = findEmployerProfile(employerUserId);

        findOwnedEvent(eventId, employerProfile.getId());

        return eventRegistrationRepository
                .findByEventId(eventId)
                .stream()
                .map(registration -> {
                    JobSeekerProfile profile = jobSeekerProfileRepository
                            .findById(registration.getJobSeekerProfileId())
                            .orElseThrow(() -> new ResourceNotFoundException("JobSeeker profile not found"));

                    User user = userRepository
                            .findById(profile.getUserId())
                            .orElseThrow(() -> new ResourceNotFoundException("User not found"));

                    return EventRegistrantResponse.builder()
                            .jobSeekerProfileId(profile.getId())
                            .firstName(profile.getFirstName())
                            .lastName(profile.getLastName())
                            .email(user.getEmail())
                            .city(profile.getCity())
                            .state(profile.getState())
                            .registrationStatus(registration.getRegistrationStatus())
                            .registeredAt(registration.getRegisteredAt())
                            .build();
                })
                .toList();
    }

    private EmployerProfile findEmployerProfile(String employerUserId) {
        return employerProfileRepository
                .findByUserId(employerUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Employer profile was not found"));
    }

    private Event findOwnedEvent(String eventId, String employerProfileId) {
        return eventRepository
                .findByIdAndEmployerProfileId(eventId, employerProfileId)
                .orElseThrow(() -> new ResourceNotFoundException("Event was not found"));
    }

    private void validateEmployerProfile(EmployerProfile profile) {
        if (isBlank(profile.getCompanyName())
                || isBlank(profile.getCompanyAddress())
                || isBlank(profile.getPhoneNumber())
                || profile.getCompanySize() == null) {

            throw new BusinessException(
                    "Complete the required employer profile information before creating an event");
        }
    }

    private void validateEventRequest(CreateEventRequest request) {
        LocalDateTime now = LocalDateTime.now();

        if (request.getStartDateTime() == null) {
            throw new BusinessException("Event start date and time are required");
        }

        if (request.getEndDateTime() == null) {
            throw new BusinessException("Event end date and time are required");
        }

        if (request.getRegistrationDeadline() == null) {
            throw new BusinessException("Registration deadline is required");
        }

        if (request.getStartDateTime().isBefore(now)) {
            throw new BusinessException("Event start date and time cannot be in the past");
        }

        if (!request.getEndDateTime().isAfter(request.getStartDateTime())) {
            throw new BusinessException("Event end date and time must be after the start date and time");
        }

        if (request.getRegistrationDeadline().isBefore(now)) {
            throw new BusinessException("Registration deadline cannot be in the past");
        }

        if (request.getRegistrationDeadline().isAfter(request.getStartDateTime())) {
            throw new BusinessException("Registration deadline must be before the event starts");
        }

        if (request.getCapacity() == null) {
            throw new BusinessException("Event capacity is required");
        }

        if (request.getCapacity() <= 0) {
            throw new BusinessException("Event capacity must be greater than zero");
        }

        validateEventLocation(request);
    }

    private void validateEventLocation(CreateEventRequest request) {
        if (request.getEventMode() == null) {
            throw new BusinessException("Event mode is required");
        }

        if (request.getEventMode() == EventMode.ONLINE) {
            if (isBlank(request.getMeetingLink())) {
                throw new BusinessException("Meeting link is required for online events");
            }
        }

        if (request.getEventMode() == EventMode.OFFLINE) {
            if (isBlank(request.getVenueName())) {
                throw new BusinessException("Venue name is required for offline events");
            }

            if (isBlank(request.getAddress())) {
                throw new BusinessException("Address is required for offline events");
            }
        }

        if (request.getEventMode() == EventMode.HYBRID) {
            if (isBlank(request.getVenueName())) {
                throw new BusinessException("Venue name is required for hybrid events");
            }

            if (isBlank(request.getAddress())) {
                throw new BusinessException("Address is required for hybrid events");
            }

            if (isBlank(request.getMeetingLink())) {
                throw new BusinessException("Meeting link is required for hybrid events");
            }
        }
    }

    private void applyRequest(Event event, CreateEventRequest request) {
        event.setTitle(format(request.getTitle()));
        event.setDescription(request.getDescription().trim());
        event.setEventType(request.getEventType());
        event.setEventMode(request.getEventMode());
        event.setStartDateTime(request.getStartDateTime());
        event.setEndDateTime(request.getEndDateTime());
        event.setRegistrationDeadline(request.getRegistrationDeadline());
        event.setCapacity(request.getCapacity());
        event.setVenueName(trimToNull(request.getVenueName()));
        String resolvedAddress = trimToNull(request.getAddress());
        event.setAddress(resolvedAddress);
        applyGeocodedCoordinates(event, resolvedAddress);
        event.setMeetingLink(trimToNull(request.getMeetingLink()));
    }

    /**
     * Keeps latitude/longitude in sync with the address on every create/edit,
     * so events are searchable/sortable by distance as soon as they're
     * published rather than waiting on the startup backfill migration. A
     * failed or missing geocode simply clears coordinates - distance search
     * degrades gracefully for that event rather than blocking the save.
     */
    private void applyGeocodedCoordinates(Event event, String address) {
        if (address == null) {
            event.setLatitude(null);
            event.setLongitude(null);
            return;
        }

        Optional<GeoCoordinates> coordinates = geocodingService.geocode(address);
        event.setLatitude(coordinates.map(GeoCoordinates::latitude).orElse(null));
        event.setLongitude(coordinates.map(GeoCoordinates::longitude).orElse(null));
    }

    private boolean eventDetailsChanged(Event event, CreateEventRequest request) {
        return !Objects.equals(format(event.getTitle()), format(request.getTitle()))
                || !Objects.equals(event.getDescription(), request.getDescription().trim())
                || event.getEventType() != request.getEventType()
                || event.getEventMode() != request.getEventMode()
                || !Objects.equals(event.getVenueName(), trimToNull(request.getVenueName()))
                || !Objects.equals(event.getAddress(), trimToNull(request.getAddress()))
                || !Objects.equals(event.getMeetingLink(), trimToNull(request.getMeetingLink()))
                || !Objects.equals(event.getStartDateTime(), request.getStartDateTime())
                || !Objects.equals(event.getEndDateTime(), request.getEndDateTime())
                || !Objects.equals(event.getRegistrationDeadline(), request.getRegistrationDeadline())
                || !Objects.equals(event.getCapacity(), request.getCapacity());
    }

    private void notifyRegisteredJobSeekersOfEventUpdate(Event event) {
        eventRegistrationRepository.findByEventId(event.getId()).stream()
                .filter(registration -> registration.getRegistrationStatus()
                        == com.waypoint.enums.RegistrationStatus.REGISTERED)
                .forEach(registration -> {
                    try {
                        jobSeekerProfileRepository.findById(registration.getJobSeekerProfileId())
                                .ifPresent(profile -> userRepository.findById(profile.getUserId())
                                        .ifPresent(user -> notificationService.notifyEventUpdated(
                                                profile.getUserId(),
                                                user.getEmail(),
                                                event.getId(),
                                                format(event.getTitle()),
                                                EventEmailDetails.from(event))));
                    } catch (RuntimeException exception) {
                        log.warn("Failed to prepare event-updated notification [eventId={}, exceptionType={}]",
                                event.getId(), exception.getClass().getSimpleName());
                    }
                });
    }

    private EmployerEventResponse toResponse(Event event) {
        return EmployerEventResponse.builder()
                .id(event.getId())
                .eventCode(event.getEventCode())
                .title(format(event.getTitle()))
                .description(event.getDescription())
                .eventType(event.getEventType())
                .eventMode(event.getEventMode())
                .venueName(event.getVenueName())
                .address(event.getAddress())
                .meetingLink(event.getMeetingLink())
                .startDateTime(event.getStartDateTime())
                .endDateTime(event.getEndDateTime())
                .registrationDeadline(event.getRegistrationDeadline())
                .capacity(event.getCapacity())
                .status(event.getStatus())
                .registrationCount(
                        event.getId() == null
                                ? 0L
                                : eventRegistrationRepository.countByEventId(event.getId()))
                .createdAt(event.getCreatedAt())
                .updatedAt(event.getUpdatedAt())
                .build();
    }

    private String generateEventCode() {
        String eventCode;

        do {
            eventCode = "EVT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        } while (eventRepository.existsByEventCode(eventCode));

        return eventCode;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
}
