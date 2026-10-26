package com.waypoint.dto.myhr;

import com.waypoint.enums.UserRole;

public record CreatedAccountCredential(
        String userId,
        String employeeProfileId,
        String loginId,
        String employeeId,
        String email,
        UserRole role,
        String organizationId,
        boolean mustChangePassword,
        String temporaryPassword) {
}
