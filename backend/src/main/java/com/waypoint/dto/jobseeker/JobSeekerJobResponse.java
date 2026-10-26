package com.waypoint.dto.jobseeker;

import com.waypoint.enums.ApplicationStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.ExperienceLevel;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class JobSeekerJobResponse {

    private String id;
    private String title;
    private String description;
    private String location;
    private WorkMode workMode;
    private EmploymentType employmentType;
    private ExperienceLevel experienceLevel;
    private List<String> requiredSkills;
    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private SalaryRange salaryRange;
    private LocalDateTime createdAt;
    private LocalDateTime applicationDeadline;
    private Boolean resumeRequired;
    private JobStatus status;
    private boolean hasResume;
    private boolean hasApplied;
    private ApplicationStatus applicationStatus;
    private LocalDateTime appliedAt;
}
