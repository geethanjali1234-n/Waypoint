package com.waypoint.dto.myhr;

import com.waypoint.enums.CompanySize;
import com.waypoint.enums.PayrollPeriod;

/**
 * COMPANY_ADMIN organization settings payload returned by GET and PUT
 * /api/myhr/organization-settings.
 *
 * organizationId/organizationCode are read-only identity context - they are
 * never editable through this API (organizationCode in particular
 * participates in employee-ID generation). Everything from organizationEmail
 * down is editable; payrollPeriod is null until an admin saves one for the
 * first time (the organization_settings document is created lazily on the
 * first update, never at startup).
 */
public record OrganizationSettingsResponse(
        String organizationId,
        String organizationCode,
        String organizationEmail,
        String organizationName,
        String phoneNumber,
        String streetAddress,
        String city,
        String state,
        String zipCode,
        CompanySize organizationSize,
        String industry,
        String website,
        String einNumber,
        String eVerifyNumber,
        String adminName,
        String adminEmail,
        String adminPhoneNumber,
        String description,
        PayrollPeriod payrollPeriod,
        String organizationLogoUrl,
        String organizationSignatureUrl) {
}
