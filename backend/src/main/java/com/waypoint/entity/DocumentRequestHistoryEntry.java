package com.waypoint.entity;

import com.waypoint.enums.DocumentRequestStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Request-scoped audit trail shown on the request itself (who submitted,
 * reviewed, replaced, signed, approved/returned/rejected, and when). Each
 * entry is also written to the organization audit log through AuditService.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DocumentRequestHistoryEntry {
    private String action;
    private String actorUserId;
    private String actorName;
    private String actorRole;
    private DocumentRequestStatus fromStatus;
    private DocumentRequestStatus toStatus;
    private String documentId;
    private String detail;
    private LocalDateTime at;
}
