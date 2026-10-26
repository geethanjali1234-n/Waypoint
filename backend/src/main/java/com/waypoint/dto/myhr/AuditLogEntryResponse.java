package com.waypoint.dto.myhr;

import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;

import java.time.LocalDateTime;

/**
 * One row of the organization Audit History.
 */
public record AuditLogEntryResponse(
        String id,
        LocalDateTime performedAt,
        AuditActionType actionType,
        String actionLabel,
        AuditEventCategory category,
        String description,
        String actorUserId,
        String actorLoginId,
        String actorName,
        String actorRole,
        String entityType,
        String entityId,
        String entityDisplayName,
        Object previousValue,
        Object updatedValue,
        String outcome,
        String source) {
}
