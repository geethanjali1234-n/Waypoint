package com.waypoint.dto.profile;

import com.waypoint.enums.EmploymentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
public class JobSeekerProfileUpdateRequest {

    @NotBlank(message = "First name is required")
    private String firstName;

    @NotBlank(message = "Last name is required")
    private String lastName;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+1\\d{10}$", message = "Enter a valid 10-digit phone number")
    private String phoneNumber;

    @NotEmpty(message = "Add at least 1 skill")
    private List<String> skills;

    @NotEmpty(message = "Select at least 1 preferred job role")
    private List<String> preferredJobRoles;

    private List<EmploymentType> preferredEmploymentType;

    @PositiveOrZero(message = "Salary min must be zero or greater")
    private BigDecimal expectedSalaryMin;

    @PositiveOrZero(message = "Salary max must be zero or greater")
    private BigDecimal expectedSalaryMax;

    private boolean removeProfilePicture;
}
