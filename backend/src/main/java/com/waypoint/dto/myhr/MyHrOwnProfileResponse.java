package com.waypoint.dto.myhr;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.EmploymentType;
import com.waypoint.enums.PayrollPeriod;
import com.waypoint.enums.UserRole;

import java.time.LocalDate;

public record MyHrOwnProfileResponse(
        String userId,
        String loginId,
        String employeeId,
        UserRole role,
        AccountStatus accountStatus,
        String firstName,
        String lastName,
        String name,
        String accountEmail,
        String personalEmail,
        String phoneNumber,
        String designation,
        EmploymentType employmentType,
        LocalDate startDate,
        String streetAddress,
        String city,
        String state,
        String zipCode,
        String organizationId,
        String organizationName,
        String organizationCode,
        String organizationEmail,
        String organizationAddress,
        String organizationPhoneNumber,
        String organizationWebsite,
        String organizationEinNumber,
        String organizationEVerifyNumber,
        String organizationAdminName,
        String organizationAdminEmail,
        String organizationAdminPhoneNumber,
        // Organization Settings payroll period (e.g. the salary verification
        // letter's pay frequency); null until an admin has saved settings.
        PayrollPeriod organizationPayrollPeriod,
        String organizationLogoUrl,
        String organizationSignatureUrl,
        ReportingManagerRef reportingManager,
        // The authenticated user's own signature (Profile), separate from the
        // organization's official signature above.
        String signatureUrl) {
}
