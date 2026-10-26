package com.waypoint.dto.employer;

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
public class EmployerApplicantSummaryResponse {

    private String applicationId;
    private String jobSeekerProfileId;
    private String fullName;
    private String email;
    private boolean hasResume;
    private boolean resumeRecordExists;
    private ApplicationStatus status;
    private LocalDateTime appliedAt;
}
