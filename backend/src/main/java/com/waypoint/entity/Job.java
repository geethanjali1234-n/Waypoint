package com.waypoint.entity;

import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.ExperienceLevel;
import com.waypoint.enums.JobStatus;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "jobs")
public class Job {

    @Id
    private String id;

    @Indexed
    private String employerProfileId;

    @Indexed(unique = true)
    private String jobCode;

    private String title;

    private String department;

    private String description;

    @Indexed
    private String location;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private WorkMode workMode;

    private EmploymentType employmentType;

    private ExperienceLevel experienceLevel;

    @Indexed
    private List<String> requiredSkills;

    private BigDecimal salaryMin;

    private BigDecimal salaryMax;

    /**
     * Salary band used by the current job-posting flow. The numeric fields
     * above remain readable for older postings created before salary bands
     * were introduced.
     */
    private SalaryRange salaryRange;

    private LocalDateTime applicationDeadline;

    private Boolean resumeRequired;

    @Indexed
    private JobStatus status;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
