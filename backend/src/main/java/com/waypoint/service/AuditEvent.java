package com.waypoint.service;

import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.UserRole;
import lombok.Builder;
import lombok.Getter;

/**
 * Immutable description of something that happened and should be written to the
 * organization audit trail. Callers populate what they know; {@link AuditService}
 * fills in actor identity, timestamps, category and module before persisting.
 */
@Getter
@Builder
public class AuditEvent {

    /**
     * Deterministic id for idempotent writes (e.g. reconciled/backfilled events).
     * When set, {@link AuditService} uses it as the {@code _id} and skips the write
     * if a row with that id already exists. When null, Mongo assigns an ObjectId.
     */
    private final String eventId;

    /**
     * The real time the event happened. Used for backfilled/reconciled historical
     * events. When null, {@link AuditService} stamps "now".
     */
    private final java.time.LocalDateTime occurredAt;

    /** Organization the event belongs to. Required — events without an org are dropped. */
    private final String organizationId;

    /** User who performed the action, or {@code null} for system/automated actions. */
    private final String actorUserId;

    /** Optional actor hints so {@link AuditService} can skip lookups when the caller already has them. */
    private final String actorLoginId;
    private final UserRole actorRole;

    private final AuditActionType actionType;

    /** Type of the thing acted on, e.g. {@code "USER"}, {@code "REPORTING_ASSIGNMENT"}. */
    private final String entityType;
    private final String entityId;
    private final String entityDisplayName;

    /** Human sentence describing the change, shown as the primary line in the UI. */
    private final String description;

    /** State before / after the change. Stored as-is (maps render cleanly in Mongo and JSON). */
    private final Object previousValue;
    private final Object updatedValue;

    /** {@code "SUCCESS"} (default) or {@code "FAILURE"}. */
    private final String outcome;
    private final String failureReason;

    /** Where the event originated, e.g. {@code "MY_HR"} (default), {@code "SYSTEM_PROVISIONING"}. */
    private final String source;

    /** Optional request correlation metadata. */
    private final String ipAddress;
    private final String userAgent;
    private final String requestId;
}
