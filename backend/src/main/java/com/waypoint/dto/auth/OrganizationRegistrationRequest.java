package com.waypoint.dto.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.waypoint.enums.CompanySize;
import com.waypoint.enums.PayrollPeriod;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class OrganizationRegistrationRequest {

    @NotBlank(message = "Organization name is required")
    @Size(max = 160, message = "Organization name must be 160 characters or fewer")
    private String organizationName;

    @NotBlank(message = "Company contact email is required")
    @Email(message = "Company contact email must be valid")
    private String organizationEmail;

    @NotBlank(message = "Phone number is required")
    @Size(max = 30, message = "Phone number must be 30 characters or fewer")
    private String phoneNumber;

    @NotBlank(message = "State is required")
    @Size(max = 100, message = "State must be 100 characters or fewer")
    private String state;

    @NotBlank(message = "City is required")
    @Size(max = 100, message = "City must be 100 characters or fewer")
    private String city;

    @NotBlank(message = "Street address is required")
    @Size(max = 200, message = "Street address must be 200 characters or fewer")
    private String streetAddress;

    @NotBlank(message = "ZIP code is required")
    @Size(max = 20, message = "ZIP code must be 20 characters or fewer")
    private String zipCode;

    @NotNull(message = "Company size is required")
    private CompanySize organizationSize;

    @NotBlank(message = "Industry is required")
    @Size(max = 120, message = "Industry must be 120 characters or fewer")
    private String industry;

    @Size(max = 300, message = "Website must be 300 characters or fewer")
    private String website;

    @Size(max = 2000, message = "Description must be 2000 characters or fewer")
    private String description;

    @NotBlank(message = "EIN number is required")
    @Pattern(regexp = "^\\s*\\d{2}-?\\d{7}\\s*$", message = "EIN must be 9 digits, e.g. 12-3456789")
    private String einNumber;

    // Lombok would otherwise expose this as "everifyNumber"; keep the name the settings API uses.
    @JsonProperty("eVerifyNumber")
    @NotBlank(message = "E-Verify number is required")
    @Size(max = 30, message = "E-Verify number must be 30 characters or fewer")
    private String eVerifyNumber;

    @NotNull(message = "Payroll period is required")
    private PayrollPeriod payrollPeriod;

    @NotBlank(message = "Admin name is required")
    @Size(max = 160, message = "Admin name must be 160 characters or fewer")
    private String adminName;

    @NotBlank(message = "Admin email is required")
    @Email(message = "Admin email must be valid")
    private String adminEmail;

    @NotBlank(message = "Admin contact number is required")
    @Size(max = 30, message = "Admin contact number must be 30 characters or fewer")
    private String adminPhoneNumber;
}
