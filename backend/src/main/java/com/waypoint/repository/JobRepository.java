package com.waypoint.repository;

import com.waypoint.entity.Job;
import com.waypoint.enums.JobStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface JobRepository extends MongoRepository<Job, String> {

    List<Job> findByEmployerProfileIdOrderByCreatedAtDesc(String employerProfileId);

    List<Job> findByStatusOrderByCreatedAtDesc(JobStatus status);

    boolean existsByJobCode(String jobCode);

    List<Job> findByStatus(JobStatus status);

    List<Job> findByLatitudeIsNull();
}
