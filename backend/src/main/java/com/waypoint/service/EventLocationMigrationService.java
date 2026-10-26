package com.waypoint.service;

import com.waypoint.entity.Event;
import com.waypoint.repository.EventRepository;
import com.waypoint.service.geocoding.GeoCoordinates;
import com.waypoint.service.geocoding.GeocodingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Backfills coordinates for events missing latitude/longitude - including
 * any inserted directly into MongoDB outside the application, which never
 * runs any of this app's code and so can only be picked up here, on the
 * next startup after insertion. Only ever queries events missing a
 * coordinate, so it's naturally idempotent - a fully-backfilled event is
 * never touched again.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "waypoint.startup.event-location-migration.enabled", havingValue = "true")
public class EventLocationMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(EventLocationMigrationService.class);

    // Stay under Nominatim's ~1 request/second public usage policy.
    private static final long GEOCODING_REQUEST_DELAY_MS = 1100;

    private final EventRepository eventRepository;
    private final GeocodingService geocodingService;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingCoordinates() {
        List<Event> eventsMissingCoordinates = eventRepository.findByLatitudeIsNullOrLongitudeIsNull();
        if (eventsMissingCoordinates.isEmpty()) {
            return;
        }

        int updated = 0;
        int skipped = 0;
        int failed = 0;

        for (Event event : eventsMissingCoordinates) {
            try {
                if (event.getAddress() == null || event.getAddress().isBlank()) {
                    skipped++;
                    continue;
                }

                Optional<GeoCoordinates> coordinates = geocodingService.geocode(event.getAddress());
                if (coordinates.isEmpty()) {
                    skipped++;
                    continue;
                }

                event.setLatitude(coordinates.get().latitude());
                event.setLongitude(coordinates.get().longitude());
                eventRepository.save(event);
                updated++;
            } catch (Exception exception) {
                failed++;
                logger.warn("Event location migration: failed to geocode event {} ('{}'): {}",
                        event.getId(), event.getAddress(), exception.getMessage());
            } finally {
                sleepBetweenRequests();
            }
        }

        logger.info(
                "Event location migration: scanned {} event(s) missing coordinates "
                        + "- {} updated, {} skipped, {} failed",
                eventsMissingCoordinates.size(), updated, skipped, failed);
    }

    private void sleepBetweenRequests() {
        try {
            Thread.sleep(GEOCODING_REQUEST_DELAY_MS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
