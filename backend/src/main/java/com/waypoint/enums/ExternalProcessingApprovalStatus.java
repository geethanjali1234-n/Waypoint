package com.waypoint.enums;

/** An approval is ACTIVE until revoked; revoked records are kept as history and never reactivated. */
public enum ExternalProcessingApprovalStatus {
    ACTIVE,
    REVOKED
}
