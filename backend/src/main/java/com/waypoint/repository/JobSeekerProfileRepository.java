package com.waypoint.repository;

import com.waypoint.entity.JobSeekerProfile;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface JobSeekerProfileRepository extends MongoRepository<JobSeekerProfile, String> {

    Optional<JobSeekerProfile> findByUserId(String userId);
}
