package com.waypoint.enums;

/**
 * High-level grouping for {@link AuditActionType} values so the Audit History
 * UI can present activity by area of the organization rather than as a flat
 * list of raw action codes.
 */
public enum AuditEventCategory {
    ACCOUNT_MANAGEMENT,
    REPORTING_STRUCTURE,
    PROFILE,
    TIMESHEET,
    PAYROLL,
    DOCUMENT,
    RECRUITMENT,
    REPORTING_OUTPUT,
    SECURITY
}
