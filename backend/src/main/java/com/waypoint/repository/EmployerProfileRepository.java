package com.waypoint.repository;

import com.waypoint.entity.EmployerProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface EmployerProfileRepository extends MongoRepository<EmployerProfile, String> {

    Optional<EmployerProfile> findByUserId(String userId);
}
