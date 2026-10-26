package com.waypoint.dto.profile;

import com.waypoint.enums.CompanySize;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Year;

@Data
public class EmployerProfileUpdateRequest {

    @NotBlank(message = "Company name is required")
    @Size(max = 150, message = "Company name must not exceed 150 characters")
    private String companyName;

    @Size(max = 2000, message = "Company description must not exceed 2000 characters")
    private String companyDescription;

    @Size(max = 120, message = "Contact person name must not exceed 120 characters")
    private String contactPersonName;

    @Size(max = 120, message = "Industry must not exceed 120 characters")
    private String industry;

    private CompanySize companySize;

    @Min(value = 1000, message = "Founded year must be a valid four-digit year")
    @Max(value = 9999, message = "Founded year must be a valid four-digit year")
    private Integer foundedYear;

    @Email(message = "Careers contact email must be valid")
    @Size(max = 254, message = "Careers contact email must not exceed 254 characters")
    private String careersContactEmail;

    @Pattern(regexp = "^(|https?://.+)$", message = "Website must begin with http:// or https://")
    @Size(max = 500, message = "Website must not exceed 500 characters")
    private String website;

    @Size(max = 500, message = "Company address must not exceed 500 characters")
    private String companyAddress;

    @Pattern(regexp = "^(|\\+1\\d{10})$", message = "Enter a valid 10-digit phone number")
    private String phoneNumber;

    private boolean removeCompanyLogo;

    @AssertTrue(message = "Founded year cannot be later than the current year")
    public boolean isFoundedYearNotInFuture() {
        return foundedYear == null || foundedYear <= Year.now().getValue();
    }
}
