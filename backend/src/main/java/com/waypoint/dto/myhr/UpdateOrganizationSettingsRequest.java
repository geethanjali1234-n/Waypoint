package com.waypoint.dto.myhr;

import com.waypoint.enums.CompanySize;
import com.waypoint.enums.PayrollPeriod;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for PUT /api/myhr/organization-settings.
 *
 * organizationCode is deliberately absent - it participates in employee-ID
 * generation and is never editable through this API, no matter what a caller
 * sends (overposting an organizationCode field is simply ignored, since this
 * record has no such property to bind it to). Every other organization field
 * is editable by a COMPANY_ADMIN.
 */
public record UpdateOrganizationSettingsRequest(
        @NotBlank(message = "Organization name is required")
        @Size(max = 200, message = "Organization name must be 200 characters or fewer")
        String organizationName,

        @NotBlank(message = "Organization email is required")
        @Email(message = "Organization email must be valid")
        String organizationEmail,

        String phoneNumber,
        String streetAddress,
        String city,
        String state,
        String zipCode,
        CompanySize organizationSize,
        String industry,
        String website,
        @Pattern(regexp = "^\\s*\\d{2}-?\\d{7}\\s*$", message = "EIN must be 9 digits, e.g. 12-3456789")
        String einNumber,
        String eVerifyNumber,

        @Size(max = 160, message = "Admin name must be 160 characters or fewer")
        String adminName,

        @Email(message = "Admin email must be valid")
        String adminEmail,

        @Size(max = 30, message = "Admin phone number must be 30 characters or fewer")
        String adminPhoneNumber,

        @Size(max = 2000, message = "Description must be 2000 characters or fewer")
        String description,

        @NotNull(message = "Payroll Period is required")
        PayrollPeriod payrollPeriod,

        boolean removeLogo,
        boolean removeSignature) {
}
