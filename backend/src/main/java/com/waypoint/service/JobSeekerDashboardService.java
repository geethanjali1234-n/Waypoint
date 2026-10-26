package com.waypoint.service;

import com.waypoint.dto.jobseeker.JobSeekerDashboardResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.repository.JobSeekerProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class JobSeekerDashboardService {

    private final JobSeekerProfileRepository jobSeekerProfileRepository;

    public JobSeekerDashboardResponse getDashboard(String userId) {
        Optional<JobSeekerProfile> jobSeekerProfile = jobSeekerProfileRepository.findByUserId(userId);

        if (jobSeekerProfile.isEmpty()) {
            return JobSeekerDashboardResponse.builder()
                    .userId(userId)
                    .hasProfile(false)
                    .build();
        }

        JobSeekerProfile profile = jobSeekerProfile.get();

        return JobSeekerDashboardResponse.builder()
                .userId(userId)
                .firstName(profile.getFirstName())
                .profilePicturePath(profile.getProfilePicturePath())
                .hasProfile(true)
                .build();
    }
}
