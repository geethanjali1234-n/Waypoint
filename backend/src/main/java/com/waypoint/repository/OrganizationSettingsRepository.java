package com.waypoint.repository;

import com.waypoint.entity.OrganizationSettings;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OrganizationSettingsRepository extends MongoRepository<OrganizationSettings, String> {

    Optional<OrganizationSettings> findByOrganizationId(String organizationId);
}
