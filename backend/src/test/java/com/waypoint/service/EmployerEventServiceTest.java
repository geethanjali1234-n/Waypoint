package com.waypoint.service;

import com.waypoint.dto.event.CreateEventRequest;
import com.waypoint.dto.event.EmployerEventResponse;
import com.waypoint.dto.event.EventDashboardResponse;
import com.waypoint.dto.event.EventRegistrantResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.entity.EmployerProfile;
import com.waypoint.entity.Event;
import com.waypoint.entity.EventRegistration;
import com.waypoint.entity.User;
import com.waypoint.enums.CompanySize;
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
import com.waypoint.service.geocoding.GeoCoordinates;
import com.waypoint.service.geocoding.GeocodingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmployerEventServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventRegistrationRepository eventRegistrationRepository;

    @Mock
    private EmployerProfileRepository employerProfileRepository;

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private GeocodingService geocodingService;

    @InjectMocks
    private EmployerEventService employerEventService;

    private EmployerProfile completeEmployerProfile() {
        return EmployerProfile.builder()
                .id("employer-profile-1")
                .userId("employer-user-1")
                .companyName("Acme Corp")
                .companyAddress("Austin, Texas")
                .phoneNumber("5551234567")
                .companySize(CompanySize.MEDIUM)
                .build();
    }

    private Event publishedEvent() {
        return Event.builder()
                .id("event-1")
                .employerProfileId("employer-profile-1")
                .eventCode("EVT-ABC12345")
                .title("Career Fair")
                .status(EventStatus.PUBLISHED)
                .startDateTime(LocalDateTime.now().plusDays(10))
                .endDateTime(LocalDateTime.now().plusDays(10).plusHours(2))
                .registrationDeadline(LocalDateTime.now().plusDays(9))
                .capacity(50)
                .build();
    }

    private CreateEventRequest validRequest() {
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
        return request;
    }

    @Test
    void getEmployerEvents_returnsMappedEventsForEmployer() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.findByEmployerProfileIdOrderByStartDateTimeAsc("employer-profile-1"))
                .thenReturn(List.of(publishedEvent()));
        when(eventRegistrationRepository.countByEventId("event-1")).thenReturn(3L);

        List<EmployerEventResponse> result = employerEventService.getEmployerEvents("employer-user-1");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo("event-1");
        assertThat(result.get(0).getRegistrationCount()).isEqualTo(3L);
    }

    @Test
    void getEmployerEvents_employerProfileNotFound_throwsResourceNotFoundException() {
        when(employerProfileRepository.findByUserId("employer-user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employerEventService.getEmployerEvents("employer-user-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Employer profile was not found");

        verify(eventRepository, never()).findByEmployerProfileIdOrderByStartDateTimeAsc(any());
    }

    @Test
    void getDashboard_returnsCountsFromRepository() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.countByEmployerProfileId("employer-profile-1")).thenReturn(5L);
        when(eventRepository.countByEmployerProfileIdAndStartDateTimeAfter(eq("employer-profile-1"), any()))
                .thenReturn(2L);
        when(eventRepository.countByEmployerProfileIdAndStatus("employer-profile-1", EventStatus.COMPLETED))
                .thenReturn(1L);

        EventDashboardResponse response = employerEventService.getDashboard("employer-user-1");

        assertThat(response.getTotalEvents()).isEqualTo(5L);
        assertThat(response.getUpcomingEvents()).isEqualTo(2L);
        assertThat(response.getCompletedEvents()).isEqualTo(1L);
    }

    @Test
    void generateAvailableEventCode_returnsCodeNotAlreadyUsed() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.existsByEventCode(anyString())).thenReturn(false);

        String code = employerEventService.generateAvailableEventCode("employer-user-1");

        assertThat(code).startsWith("EVT-");
    }

    @Test
    void generateAvailableEventCode_retriesOnCollisionUntilUnique() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.existsByEventCode(anyString())).thenReturn(true, false);

        String code = employerEventService.generateAvailableEventCode("employer-user-1");

        assertThat(code).startsWith("EVT-");
        verify(eventRepository, times(2)).existsByEventCode(anyString());
    }

    @Test
    void createEvent_validRequest_savesAndReturnsEvent() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.existsByEventCode(anyString())).thenReturn(false);
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> {
            Event event = invocation.getArgument(0);
            event.setId("event-1");
            return event;
        });
        when(eventRegistrationRepository.countByEventId("event-1")).thenReturn(0L);

        EmployerEventResponse response = employerEventService.createEvent("employer-user-1", validRequest());

        assertThat(response.getId()).isEqualTo("event-1");
        assertThat(response.getTitle()).isEqualTo("Career Fair");
        assertThat(response.getStatus()).isEqualTo(EventStatus.PUBLISHED);
    }

    @Test
    void createEvent_offlineEventWithAddress_geocodesAndStoresCoordinates() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.existsByEventCode(anyString())).thenReturn(false);
        when(geocodingService.geocode("500 W 2nd St, Austin, TX 78701"))
                .thenReturn(Optional.of(new GeoCoordinates(new BigDecimal("30.2672"), new BigDecimal("-97.7431"))));
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateEventRequest request = validRequest();
        request.setEventMode(EventMode.OFFLINE);
        request.setMeetingLink(null);
        request.setVenueName("Austin Convention Center");
        request.setAddress("500 W 2nd St, Austin, TX 78701");

        employerEventService.createEvent("employer-user-1", request);

        ArgumentCaptor<Event> savedEvent = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getLatitude()).isEqualByComparingTo("30.2672");
        assertThat(savedEvent.getValue().getLongitude()).isEqualByComparingTo("-97.7431");
    }

    @Test
    void createEvent_addressCannotBeGeocoded_savesWithNullCoordinates() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.existsByEventCode(anyString())).thenReturn(false);
        when(geocodingService.geocode(anyString())).thenReturn(Optional.empty());
        when(eventRepository.save(any(Event.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateEventRequest request = validRequest();
        request.setEventMode(EventMode.OFFLINE);
        request.setMeetingLink(null);
        request.setVenueName("Austin Convention Center");
        request.setAddress("not a real address");

        employerEventService.createEvent("employer-user-1", request);

        ArgumentCaptor<Event> savedEvent = ArgumentCaptor.forClass(Event.class);
        verify(eventRepository).save(savedEvent.capture());
        assertThat(savedEvent.getValue().getLatitude()).isNull();
        assertThat(savedEvent.getValue().getLongitude()).isNull();
    }

    @Test
    void createEvent_incompleteEmployerProfile_throwsBusinessException() {
        EmployerProfile incompleteProfile = EmployerProfile.builder()
                .id("employer-profile-1")
                .userId("employer-user-1")
                .companyName("Acme Corp")
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(incompleteProfile));

        assertThatThrownBy(() -> employerEventService.createEvent("employer-user-1", validRequest()))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Complete the required employer profile information before creating an event");

        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_startDateTimeInPast_throwsBusinessException() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));

        CreateEventRequest request = validRequest();
        request.setStartDateTime(LocalDateTime.now().minusDays(1));

        assertThatThrownBy(() -> employerEventService.createEvent("employer-user-1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Event start date and time cannot be in the past");

        verify(eventRepository, never()).save(any());
    }

    @Test
    void createEvent_offlineEventMissingVenue_throwsBusinessException() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));

        CreateEventRequest request = validRequest();
        request.setEventMode(EventMode.OFFLINE);
        request.setMeetingLink(null);

        assertThatThrownBy(() -> employerEventService.createEvent("employer-user-1", request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Venue name is required for offline events");

        verify(eventRepository, never()).save(any());
    }

    @Test
    void getEventRegistrants_returnsMappedRegistrants() {
        EventRegistration registration = EventRegistration.builder()
                .id("registration-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .build();

        JobSeekerProfile jobSeekerProfile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .firstName("Jordan")
                .lastName("Rivera")
                .city("Austin")
                .state("TX")
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.findByIdAndEmployerProfileId("event-1", "employer-profile-1"))
                .thenReturn(Optional.of(publishedEvent()));
        when(eventRegistrationRepository.findByEventId("event-1")).thenReturn(List.of(registration));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(userRepository.findById("jobSeeker-user-1"))
                .thenReturn(Optional.of(User.builder().id("jobSeeker-user-1").email("jordan@example.com").build()));

        List<EventRegistrantResponse> result = employerEventService.getEventRegistrants("employer-user-1", "event-1");

        assertThat(result).hasSize(1);
        EventRegistrantResponse registrant = result.get(0);
        assertThat(registrant.getJobSeekerProfileId()).isEqualTo("jobSeeker-profile-1");
        assertThat(registrant.getFirstName()).isEqualTo("Jordan");
        assertThat(registrant.getLastName()).isEqualTo("Rivera");
        assertThat(registrant.getEmail()).isEqualTo("jordan@example.com");
        assertThat(registrant.getCity()).isEqualTo("Austin");
        assertThat(registrant.getState()).isEqualTo("TX");
        assertThat(registrant.getRegistrationStatus()).isEqualTo(RegistrationStatus.REGISTERED);
    }

    @Test
    void getEventRegistrants_eventNotOwnedByEmployer_throwsResourceNotFoundException() {
        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.findByIdAndEmployerProfileId("event-1", "employer-profile-1"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> employerEventService.getEventRegistrants("employer-user-1", "event-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Event was not found");

        verify(eventRegistrationRepository, never()).findByEventId(any());
    }

    @Test
    void getEventRegistrants_missingJobSeekerProfile_throwsResourceNotFoundException() {
        EventRegistration registration = EventRegistration.builder()
                .id("registration-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.findByIdAndEmployerProfileId("event-1", "employer-profile-1"))
                .thenReturn(Optional.of(publishedEvent()));
        when(eventRegistrationRepository.findByEventId("event-1")).thenReturn(List.of(registration));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employerEventService.getEventRegistrants("employer-user-1", "event-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("JobSeeker profile not found");

        verify(userRepository, never()).findById(any());
    }

    @Test
    void getEventRegistrants_missingUser_throwsResourceNotFoundException() {
        EventRegistration registration = EventRegistration.builder()
                .id("registration-1")
                .eventId("event-1")
                .jobSeekerProfileId("jobSeeker-profile-1")
                .registrationStatus(RegistrationStatus.REGISTERED)
                .registeredAt(LocalDateTime.now())
                .build();

        JobSeekerProfile jobSeekerProfile = JobSeekerProfile.builder()
                .id("jobSeeker-profile-1")
                .userId("jobSeeker-user-1")
                .firstName("Jordan")
                .lastName("Rivera")
                .build();

        when(employerProfileRepository.findByUserId("employer-user-1"))
                .thenReturn(Optional.of(completeEmployerProfile()));
        when(eventRepository.findByIdAndEmployerProfileId("event-1", "employer-profile-1"))
                .thenReturn(Optional.of(publishedEvent()));
        when(eventRegistrationRepository.findByEventId("event-1")).thenReturn(List.of(registration));
        when(jobSeekerProfileRepository.findById("jobSeeker-profile-1")).thenReturn(Optional.of(jobSeekerProfile));
        when(userRepository.findById("jobSeeker-user-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> employerEventService.getEventRegistrants("employer-user-1", "event-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("User not found");
    }
}
