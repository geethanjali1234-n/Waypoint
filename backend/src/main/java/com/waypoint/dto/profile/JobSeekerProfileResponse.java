package com.waypoint.dto.profile;

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
public class JobSeekerProfileResponse {

    private String userId;

    private String jobSeekerProfileId;

    private String firstName;

    private String lastName;

    private String email;

    private String phoneNumber;

    private String address;

    private String profilePictureUrl;

    private String resumeFileName;

    private LocalDateTime resumeUploadedAt;

    private Long resumeSizeBytes;

    private List<String> skills;

    private List<String> preferredJobRoles;

    private List<EmploymentType> preferredEmploymentType;

    private BigDecimal expectedSalaryMin;

    private BigDecimal expectedSalaryMax;

    private int completionPercentage;

    private long jobsApplied;

    private long inReview;

    private long shortlisted;

    private long eventsRegistered;
}
