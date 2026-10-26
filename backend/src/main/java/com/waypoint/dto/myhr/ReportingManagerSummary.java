package com.waypoint.dto.myhr;

/**
 * Shared by the Profile Change Request list and detail responses.
 * Sourced from EmployeeProfile.findByUserId(assignedToUserId) when the
 * assignee has a profile (e.g. HR): employeeId/name come from that profile,
 * loginId/email stay null. When the assignee has no EmployeeProfile (always
 * true for COMPANY_ADMIN, which never gets one), this falls back to User:
 * loginId/email are populated instead and employeeId/name stay null - no
 * name is ever invented for that case.
 */
public record ReportingManagerSummary(
        String userId,
        String employeeId,
        String name,
        String loginId,
        String email) {
}
