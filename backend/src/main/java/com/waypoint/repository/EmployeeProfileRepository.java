package com.waypoint.repository;

import com.waypoint.entity.EmployeeProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface EmployeeProfileRepository extends MongoRepository<EmployeeProfile, String> {

    Optional<EmployeeProfile> findByUserId(String userId);

    Optional<EmployeeProfile> findByOrganizationIdAndEmployeeId(String organizationId, String employeeId);

    List<EmployeeProfile> findByOrganizationId(String organizationId);

    /** Batch lookup so list endpoints resolve N employee profiles in one query instead of N. */
    List<EmployeeProfile> findByUserIdIn(Collection<String> userIds);
}
