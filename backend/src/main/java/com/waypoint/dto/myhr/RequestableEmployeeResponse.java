package com.waypoint.dto.myhr;

import com.waypoint.enums.UserRole;

public record RequestableEmployeeResponse(
        String userId, String employeeId, String name, String loginId, UserRole role) {
}
