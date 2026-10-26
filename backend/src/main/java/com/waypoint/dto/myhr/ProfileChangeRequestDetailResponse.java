package com.waypoint.dto.myhr;

import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;

import java.time.LocalDateTime;

public record ProfileChangeRequestDetailResponse(
        String id,
        ProfileChangeField fieldName,
        Object existingValue,
        Object requestedValue,
        String requestReason,
        ProfileChangeStatus status,
        String processedByUserId,
        String processingComment,
        LocalDateTime requestedAt,
        LocalDateTime processedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        RequesterSummary requester,
        ReportingManagerSummary reportingManager) {

    /** Sourced from EmployeeProfile.findByUserId(request.requesterUserId) - the requester always has a profile. */
    public record RequesterSummary(
            String userId,
            String employeeId,
            String name,
            String role) {
    }
}
