package com.waypoint.repository;

import com.waypoint.entity.Organization;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface OrganizationRepository extends MongoRepository<Organization, String> {

    Optional<Organization> findByOrganizationCode(String organizationCode);

    Optional<Organization> findByOrganizationEmail(String organizationEmail);

    Page<Organization> findByOrganizationNameContainingIgnoreCaseOrOrganizationCodeContainingIgnoreCaseOrOrganizationEmailContainingIgnoreCase(
            String organizationName, String organizationCode, String organizationEmail, Pageable pageable);
}
