package com.waypoint.repository;

import com.waypoint.entity.EventRegistration;
import com.waypoint.enums.RegistrationStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface EventRegistrationRepository extends MongoRepository<EventRegistration, String> {

    Optional<EventRegistration> findByEventIdAndJobSeekerProfileId(String eventId, String jobSeekerProfileId);

    long countByEventIdAndRegistrationStatus(String eventId, RegistrationStatus registrationStatus);

    List<EventRegistration> findByJobSeekerProfileIdAndRegistrationStatusOrderByRegisteredAtDesc(
            String jobSeekerProfileId, RegistrationStatus registrationStatus);

    List<EventRegistration> findByEventId(String eventId);

    long countByEventId(String eventId);
}
