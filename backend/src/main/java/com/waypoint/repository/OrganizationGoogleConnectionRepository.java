package com.waypoint.repository;

import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.GoogleConnectionStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

/**
 * Deliberately small. Connections are looked up by the organization id taken from authenticated server-side
 * context, so there is no "find by user" or "find all" access path to another organization's connection.
 */
public interface OrganizationGoogleConnectionRepository extends MongoRepository<OrganizationGoogleConnection, String> {

    Optional<OrganizationGoogleConnection> findByOrganizationId(String organizationId);

    /** Used to refuse connecting a Google account that another organization already has connected. */
    Optional<OrganizationGoogleConnection> findByGoogleSubjectAndStatus(String googleSubject, GoogleConnectionStatus status);
}
