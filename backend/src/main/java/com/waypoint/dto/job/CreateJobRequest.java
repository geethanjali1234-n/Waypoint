package com.waypoint.dto.job;

import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.ExperienceLevel;
import com.waypoint.enums.SalaryRange;
import com.waypoint.enums.WorkMode;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.FutureOrPresent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class CreateJobRequest {

    @Pattern(
            regexp = "JOB-[A-F0-9]{8}",
            message = "Job ID must use the format JOB-XXXXXXXX")
    private String jobCode;

    @NotBlank(message = "Job title is required")
    @Size(max = 150, message = "Job title must be 150 characters or fewer")
    private String title;

    @NotNull(message = "Work type is required")
    private WorkMode workMode;

    @NotBlank(message = "Location is required")
    @Size(max = 150, message = "Location must be 150 characters or fewer")
    private String location;

    @NotNull(message = "Employment type is required")
    private EmploymentType employmentType;

    @NotNull(message = "Experience level is required")
    private ExperienceLevel experienceLevel;

    @NotEmpty(message = "At least one required skill is required")
    private List<@NotBlank(message = "Required skills cannot be blank") String> requiredSkills;

    @DecimalMin(value = "0.0", inclusive = true, message = "Minimum salary cannot be negative")
    private BigDecimal salaryMin;

    @DecimalMin(value = "0.0", inclusive = true, message = "Maximum salary cannot be negative")
    private BigDecimal salaryMax;

    private SalaryRange salaryRange;

    @NotBlank(message = "Description is required")
    @Size(max = 5000, message = "Description must be 5000 characters or fewer")
    private String description;

    @FutureOrPresent(message = "Application deadline cannot be in the past")
    @NotNull(message = "Application deadline is required")
    private LocalDate applicationDeadline;

    @NotNull(message = "Resume requirement is required")
    private Boolean resumeRequired;
}
