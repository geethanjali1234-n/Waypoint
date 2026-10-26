package com.waypoint.dto.jobseeker;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobSeekerProfileResponse {

    private String userId;

    private String firstName;

    private String lastName;

    private String address;

    private String phoneNumber;

    private String profilePicturePath;

    private String resumePath;
}
