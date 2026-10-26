package com.waypoint.repository;

import com.waypoint.entity.JobApplication;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface JobApplicationRepository extends MongoRepository<JobApplication, String> {

    long countByJobId(String jobId);

    long countByJobIdIn(Iterable<String> jobIds);

    List<JobApplication> findByJobSeekerProfileId(String jobSeekerProfileId);

    Optional<JobApplication> findByJobIdAndJobSeekerProfileId(String jobId, String jobSeekerProfileId);

    List<JobApplication> findByJobIdOrderByAppliedAtDesc(String jobId);

    Optional<JobApplication> findByIdAndJobId(String applicationId, String jobId);

    boolean existsByJobIdAndJobSeekerProfileId(String jobId, String jobSeekerProfileId);

    List<JobApplication> findByJobSeekerProfileIdAndJobIdIn(String jobSeekerProfileId, Collection<String> jobIds);

    List<JobApplication> findByJobSeekerProfileIdOrderByAppliedAtDesc(String jobSeekerProfileId);
}
