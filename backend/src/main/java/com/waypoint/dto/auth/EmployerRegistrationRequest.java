package com.waypoint.dto.auth;

import com.waypoint.enums.CompanySize;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class EmployerRegistrationRequest {

    @NotBlank(message = "Company name is required")
    private String companyName;

    @NotBlank(message = "Email is required")
    @Pattern(
            regexp = "^[A-Za-z0-9._-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}$",
            message = "Email must be valid")
    private String email;

    @NotBlank(message = "Company address is required")
    private String companyAddress;

    @NotBlank(message = "Phone number is required")
    @Pattern(regexp = "^\\+1\\d{10}$", message = "Enter a valid 10-digit phone number")
    private String phoneNumber;

    @NotNull(message = "Company size is required")
    private CompanySize companySize;

    @NotBlank(message = "Password is required")
    @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank(message = "Confirm password is required")
    private String confirmPassword;
}
