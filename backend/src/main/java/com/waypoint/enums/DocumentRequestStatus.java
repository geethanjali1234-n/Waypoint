package com.waypoint.enums;

public enum DocumentRequestStatus {
    PENDING,
    // Signed I-983 workflow: the employee has uploaded their I-983 and is
    // waiting on HR/Admin review (shown as "Submitted"), was sent
    // back to correct it, or HR/Admin approved the organization-signed copy.
    SUBMITTED,
    RETURNED_FOR_CORRECTION,
    APPROVED,
    FULFILLED,
    REJECTED,
    CANCELLED
}
