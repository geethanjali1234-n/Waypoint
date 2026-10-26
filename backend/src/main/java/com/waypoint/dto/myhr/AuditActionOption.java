package com.waypoint.dto.myhr;

import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.AuditEventCategory;

/**
 * A selectable audit action for the Audit History filter UI.
 */
public record AuditActionOption(
        AuditActionType value,
        String label,
        AuditEventCategory category) {
}
