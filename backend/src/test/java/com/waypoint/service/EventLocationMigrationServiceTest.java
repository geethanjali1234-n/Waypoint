package com.waypoint.service;

import com.waypoint.entity.Event;
import com.waypoint.repository.EventRepository;
import com.waypoint.service.geocoding.GeoCoordinates;
import com.waypoint.service.geocoding.GeocodingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventLocationMigrationServiceTest {

    @Mock
    private EventRepository eventRepository;

    @Mock
    private GeocodingService geocodingService;

    @InjectMocks
    private EventLocationMigrationService migrationService;

    private Event eventWithAddress(String id, String address) {
        return Event.builder()
                .id(id)
                .address(address)
                .build();
    }

    @Test
    void doesNothingWhenNoEventsAreMissingCoordinates() {
        when(eventRepository.findByLatitudeIsNullOrLongitudeIsNull()).thenReturn(List.of());

        migrationService.backfillMissingCoordinates();

        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    void updatesEventWhenGeocodingSucceeds() {
        Event event = eventWithAddress("event-1", "McKinney, TX");
        when(eventRepository.findByLatitudeIsNullOrLongitudeIsNull()).thenReturn(List.of(event));
        when(geocodingService.geocode("McKinney, TX"))
                .thenReturn(Optional.of(new GeoCoordinates(new BigDecimal("33.1972"), new BigDecimal("-96.6398"))));

        migrationService.backfillMissingCoordinates();

        assertThat(event.getLatitude()).isEqualByComparingTo("33.1972");
        assertThat(event.getLongitude()).isEqualByComparingTo("-96.6398");
        verify(eventRepository).save(event);
    }

    @Test
    void skipsEventWhenAddressIsBlank() {
        Event event = eventWithAddress("event-online", null);
        when(eventRepository.findByLatitudeIsNullOrLongitudeIsNull()).thenReturn(List.of(event));

        migrationService.backfillMissingCoordinates();

        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    void skipsEventWhenGeocodingFindsNoMatch() {
        Event event = eventWithAddress("event-2", "Nonexistent Place");
        when(eventRepository.findByLatitudeIsNullOrLongitudeIsNull()).thenReturn(List.of(event));
        when(geocodingService.geocode("Nonexistent Place")).thenReturn(Optional.empty());

        migrationService.backfillMissingCoordinates();

        verify(eventRepository, never()).save(any(Event.class));
    }

    @Test
    void continuesProcessingWhenOneEventFailsAndUpdatesTheRest() {
        Event failing = eventWithAddress("event-fail", "Waco, TX");
        Event succeeding = eventWithAddress("event-ok", "Austin, TX");

        when(eventRepository.findByLatitudeIsNullOrLongitudeIsNull()).thenReturn(List.of(failing, succeeding));
        when(geocodingService.geocode("Waco, TX")).thenThrow(new RuntimeException("boom"));
        when(geocodingService.geocode("Austin, TX"))
                .thenReturn(Optional.of(new GeoCoordinates(new BigDecimal("30.2672"), new BigDecimal("-97.7431"))));

        migrationService.backfillMissingCoordinates();

        verify(eventRepository, never()).save(failing);
        verify(eventRepository).save(succeeding);
        assertThat(succeeding.getLatitude()).isEqualByComparingTo("30.2672");
    }
}
