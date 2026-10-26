package com.waypoint.dto.job;

import com.waypoint.enums.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobApplicationResponse {

    private String id;

    private String jobId;

    private String jobSeekerProfileId;

    private ApplicationStatus status;

    private LocalDateTime appliedAt;
}
