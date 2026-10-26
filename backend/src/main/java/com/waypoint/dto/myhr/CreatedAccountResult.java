package com.waypoint.dto.myhr;

import com.waypoint.enums.UserRole;

/**
 * Response for HR/Employee account creation. It never carries the temporary
 * password; that goes only to the new user's email. {@code emailDelivered} is
 * false when the account exists but the welcome email could not be sent.
 */
public record CreatedAccountResult(
        String userId,
        String employeeProfileId,
        String loginId,
        String employeeId,
        String email,
        UserRole role,
        String organizationId,
        boolean mustChangePassword,
        boolean emailDelivered) {
}
