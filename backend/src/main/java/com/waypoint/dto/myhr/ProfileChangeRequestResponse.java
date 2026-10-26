package com.waypoint.dto.myhr;

import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;

import java.time.LocalDateTime;

public record ProfileChangeRequestResponse(
        String id,
        String requesterUserId,
        String profileId,
        String assignedToUserId,
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
        // Real display information resolved server-side from EmployeeProfile/User
        // (batched - one query per list, not one per row) so the frontend never
        // has to show a raw MongoDB id and never issues N follow-up requests.
        String employeeId,
        String employeeName,
        ReportingManagerSummary reportingManager,
        // Requester's UserRole name ("EMPLOYEE" / "HR" / "COMPANY_ADMIN"),
        // resolved server-side so the admin list can filter by request type
        // without guessing from names or ids. Null only when the requester
        // User record cannot be found.
        String requesterRole) {
}
