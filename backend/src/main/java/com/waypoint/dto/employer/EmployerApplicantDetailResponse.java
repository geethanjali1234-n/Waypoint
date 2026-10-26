package com.waypoint.dto.employer;

import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EmploymentType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmployerApplicantDetailResponse {

    private String applicationId;
    private String jobSeekerProfileId;
    private String fullName;
    private String email;
    private String phoneNumber;
    private String address;
    private List<String> skills;
    private List<String> preferredJobRoles;
    private List<String> preferredLocations;
    private BigDecimal expectedSalaryMin;
    private BigDecimal expectedSalaryMax;
    private List<EmploymentType> preferredEmploymentType;
    private String resumeFileName;
    private boolean hasResume;
    private boolean resumeRecordExists;
    private ApplicationStatus status;
    private LocalDateTime appliedAt;
    private LocalDateTime updatedAt;
}
