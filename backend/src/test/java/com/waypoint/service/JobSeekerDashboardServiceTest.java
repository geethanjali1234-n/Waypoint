package com.waypoint.service;

import com.waypoint.dto.jobseeker.JobSeekerDashboardResponse;
import com.waypoint.entity.JobSeekerProfile;
import com.waypoint.repository.JobSeekerProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobSeekerDashboardServiceTest {

    @Mock
    private JobSeekerProfileRepository jobSeekerProfileRepository;

    @InjectMocks
    private JobSeekerDashboardService jobSeekerDashboardService;

    @Test
    void returnsDashboardWithProfileDetailsWhenProfileExists() {
        JobSeekerProfile profile = JobSeekerProfile.builder()
                .id("jobSeeker-1")
                .userId("user-1")
                .firstName("Jordan")
                .lastName("Rivera")
                .profilePicturePath("/uploads/profile-pictures/user-1.png")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        when(jobSeekerProfileRepository.findByUserId("user-1")).thenReturn(Optional.of(profile));

        JobSeekerDashboardResponse response = jobSeekerDashboardService.getDashboard("user-1");

        assertThat(response.getUserId()).isEqualTo("user-1");
        assertThat(response.getFirstName()).isEqualTo("Jordan");
        assertThat(response.getProfilePicturePath()).isEqualTo("/uploads/profile-pictures/user-1.png");
        assertThat(response.getHasProfile()).isTrue();
    }

    @Test
    void returnsDashboardWithHasProfileFalseWhenProfileDoesNotExist() {
        when(jobSeekerProfileRepository.findByUserId("user-without-profile")).thenReturn(Optional.empty());

        JobSeekerDashboardResponse response = jobSeekerDashboardService.getDashboard("user-without-profile");

        assertThat(response.getUserId()).isEqualTo("user-without-profile");
        assertThat(response.getFirstName()).isNull();
        assertThat(response.getProfilePicturePath()).isNull();
        assertThat(response.getHasProfile()).isFalse();
    }
}
