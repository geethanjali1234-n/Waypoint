package com.waypoint.repository;

import com.waypoint.entity.PendingOrganizationRegistration;
import com.waypoint.enums.OrganizationRegistrationStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDateTime;

public interface PendingOrganizationRegistrationRepository
        extends MongoRepository<PendingOrganizationRegistration, String> {

    /** True while another unexpired, unfinished registration has reserved this organization code. */
    boolean existsByOrganizationCodeAndStatusAndExpiresAtAfter(
            String organizationCode, OrganizationRegistrationStatus status, LocalDateTime now);
}
