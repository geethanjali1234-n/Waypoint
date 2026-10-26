package com.waypoint.entity;

import com.waypoint.enums.AuditActionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.IndexDirection;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "audit_logs")
@CompoundIndexes({
        @CompoundIndex(name = "audit_org_time",
                def = "{'organizationId': 1, 'performedAt': -1}"),
        @CompoundIndex(name = "audit_org_actor_time",
                def = "{'organizationId': 1, 'performedByUserId': 1, 'performedAt': -1}"),
        @CompoundIndex(name = "organization_entity_performedAt",
                def = "{'organizationId': 1, 'entityType': 1, 'entityId': 1, 'performedAt': -1}")
})
public class AuditLog {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String performedByUserId;

    private String actorLoginId;
    private String actorName;
    private String performedByRole;

    @Indexed
    private AuditActionType actionType;

    @Indexed
    private String entityType;

    @Indexed
    private String entityId;

    private String eventCategory;
    private String module;
    private String entityDisplayName;
    private Object previousValue;
    private Object updatedValue;
    private String description;
    private String outcome;
    private String failureReason;
    private String errorCode;
    private String source;

    @Indexed(sparse = true)
    private String requestId;

    @Indexed(sparse = true)
    private String correlationId;

    private String ipAddress;
    private String userAgent;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime performedAt;

    private LocalDateTime createdAt;

    @Builder.Default
    private Integer schemaVersion = 1;
}
