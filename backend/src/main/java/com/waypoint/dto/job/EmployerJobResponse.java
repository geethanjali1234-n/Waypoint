package com.waypoint.dto.job;

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
public class EmployerJobResponse {

    private String id;
    private String jobCode;
    private String title;
    private String department;
    private String description;
    private String location;
    private WorkMode workMode;
    private EmploymentType employmentType;
    private ExperienceLevel experienceLevel;
    private List<String> requiredSkills;
    private BigDecimal salaryMin;
    private BigDecimal salaryMax;
    private SalaryRange salaryRange;
    private LocalDateTime applicationDeadline;
    private Boolean resumeRequired;
    private JobStatus status;
    private long applicationCount;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
