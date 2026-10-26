package com.waypoint.dto.jobseeker;

import com.waypoint.enums.ApplicationStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class JobSeekerApplicationResponse {

    private String applicationId;
    private String jobId;
    private String jobTitle;
    private ApplicationStatus status;
    private LocalDateTime appliedAt;
    private boolean hasResume;
}
