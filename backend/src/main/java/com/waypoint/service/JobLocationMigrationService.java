package com.waypoint.service;

import com.waypoint.entity.Job;
import com.waypoint.repository.JobRepository;
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
 * Backfills coordinates for jobs that existed before automatic geocoding
 * shipped. Only ever queries jobs missing latitude, so it's naturally
 * idempotent and safe to run on every startup - already-backfilled jobs are
 * never touched again, and jobs whose geocode failed last run are simply
 * retried.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "waypoint.startup.job-location-migration.enabled", havingValue = "true")
public class JobLocationMigrationService {

    private static final Logger logger = LoggerFactory.getLogger(JobLocationMigrationService.class);

    // Stay under Nominatim's ~1 request/second public usage policy.
    private static final long GEOCODING_REQUEST_DELAY_MS = 1100;

    private final JobRepository jobRepository;
    private final GeocodingService geocodingService;

    @EventListener(ApplicationReadyEvent.class)
    public void backfillMissingCoordinates() {
        List<Job> jobsMissingCoordinates = jobRepository.findByLatitudeIsNull();
        if (jobsMissingCoordinates.isEmpty()) {
            return;
        }

        int backfilled = 0;
        for (Job job : jobsMissingCoordinates) {
            Optional<GeoCoordinates> coordinates = geocodingService.geocode(job.getLocation());
            if (coordinates.isPresent()) {
                job.setLatitude(coordinates.get().latitude());
                job.setLongitude(coordinates.get().longitude());
                jobRepository.save(job);
                backfilled++;
            }
            sleepBetweenRequests();
        }

        logger.info(
                "Job location migration: backfilled coordinates for {} of {} job(s) missing coordinates",
                backfilled,
                jobsMissingCoordinates.size());
    }

    private void sleepBetweenRequests() {
        try {
            Thread.sleep(GEOCODING_REQUEST_DELAY_MS);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
        }
    }
}
