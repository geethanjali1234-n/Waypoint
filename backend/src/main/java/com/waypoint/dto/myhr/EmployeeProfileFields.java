package com.waypoint.dto.myhr;

import com.waypoint.enums.EmploymentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDate;

@Data
public class EmployeeProfileFields {
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+)*$", message = "First name may contain letters and spaces only") private String firstName;
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+)*$", message = "Last name may contain letters and spaces only") private String lastName;
    @NotBlank @Email @Size(max = 254) private String personalEmail;
    @Email private String workEmail;
    @NotBlank @Pattern(regexp = "^\\+1[0-9]{10}$", message = "Enter a valid 10-digit phone number") private String phoneNumber;
    @NotBlank @Size(max = 120) private String designation;
    @NotNull private EmploymentType employmentType;
    @NotNull private LocalDate startDate;
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+)*$", message = "State may contain letters and spaces only") private String state;
    @NotBlank @Size(max = 100) @Pattern(regexp = "^[\\p{L}\\p{M}]+(?: [\\p{L}\\p{M}]+)*$", message = "City may contain letters and spaces only") private String city;
    @NotBlank @Size(max = 200) private String streetAddress;
    @NotBlank @Pattern(regexp = "^\\d{5}$", message = "Enter a valid 5-digit ZIP code") private String zipCode;
}
