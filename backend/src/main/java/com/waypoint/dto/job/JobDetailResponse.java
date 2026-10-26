package com.waypoint.dto.job;

import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
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
public class JobDetailResponse {

    private String id;

    private String jobCode;

    private String title;

    private String description;

    private String location;

    private WorkMode workMode;

    private EmploymentType employmentType;

    private List<String> requiredSkills;

    private BigDecimal salaryMin;

    private BigDecimal salaryMax;

    private SalaryRange salaryRange;

    private LocalDateTime applicationDeadline;

    private JobStatus status;

    private LocalDateTime createdAt;

    private String companyName;

    private boolean hasApplied;
}
