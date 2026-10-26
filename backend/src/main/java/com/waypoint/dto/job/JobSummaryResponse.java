package com.waypoint.dto.job;

import com.waypoint.enums.EmploymentType;
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
public class JobSummaryResponse {

    private String id;

    private String jobCode;

    private String title;

    private String location;

    private WorkMode workMode;

    private EmploymentType employmentType;

    private BigDecimal salaryMin;

    private BigDecimal salaryMax;

    private SalaryRange salaryRange;

    private LocalDateTime applicationDeadline;

    private LocalDateTime createdAt;

    private List<String> requiredSkills;

    private boolean hasApplied;
}
