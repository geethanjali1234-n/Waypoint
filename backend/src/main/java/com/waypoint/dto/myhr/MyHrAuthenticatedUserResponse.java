package com.waypoint.dto.myhr;

import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.UserRole;

public record MyHrAuthenticatedUserResponse(
        String userId,
        String loginId,
        String email,
        UserRole role,
        String organizationId,
        AccountStatus accountStatus,
        boolean mustChangePassword) {
}
