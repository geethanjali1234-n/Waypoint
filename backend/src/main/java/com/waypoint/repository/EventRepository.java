package com.waypoint.repository;

import com.waypoint.entity.Event;
import com.waypoint.enums.EventStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface EventRepository extends MongoRepository<Event, String> {

    Optional<Event> findByIdAndStatus(String id, EventStatus status);

    List<Event> findByEmployerProfileIdOrderByStartDateTimeAsc(String employerProfileId);

    Optional<Event> findByIdAndEmployerProfileId(String id, String employerProfileId);

    long countByEmployerProfileId(String employerProfileId);

    long countByEmployerProfileIdAndStartDateTimeAfter(String employerProfileId, LocalDateTime dateTime);

    long countByEmployerProfileIdAndStatus(String employerProfileId, EventStatus status);

    boolean existsByEventCode(String eventCode);

    List<Event> findByLatitudeIsNullOrLongitudeIsNull();
}
