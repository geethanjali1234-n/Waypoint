package com.waypoint.entity;

import com.waypoint.enums.ProfileChangeField;
import com.waypoint.enums.ProfileChangeStatus;
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
@Document(collection = "profile_change_requests")
@CompoundIndexes({
        @CompoundIndex(name = "organization_requester_requestedAt",
                def = "{'organizationId': 1, 'requesterUserId': 1, 'requestedAt': -1}"),
        @CompoundIndex(name = "organization_assignee_status_requestedAt",
                def = "{'organizationId': 1, 'assignedToUserId': 1, 'status': 1, 'requestedAt': -1}")
})
public class ProfileChangeRequest {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String requesterUserId;
    private String profileId;

    @Indexed
    private String assignedToUserId;

    @Indexed
    private ProfileChangeField fieldName;

    private Object existingValue;
    private Object requestedValue;
    private String requestReason;

    @Indexed
    private ProfileChangeStatus status;

    private String processedByUserId;
    private String processingComment;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime requestedAt;

    private LocalDateTime processedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
