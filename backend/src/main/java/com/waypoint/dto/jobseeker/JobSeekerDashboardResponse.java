package com.waypoint.dto.jobseeker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobSeekerDashboardResponse {

    private String userId;

    private String firstName;

    private String profilePicturePath;

    private Boolean hasProfile;
}
