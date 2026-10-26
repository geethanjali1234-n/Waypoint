package com.waypoint.entity;

import com.waypoint.enums.TimesheetStatus;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "timesheets")
@CompoundIndexes({
        @CompoundIndex(name = "organization_user_submittedAt",
                def = "{'organizationId': 1, 'userId': 1, 'submittedAt': -1}"),
        @CompoundIndex(name = "organization_manager_submittedAt",
                def = "{'organizationId': 1, 'reportingManagerUserId': 1, 'submittedAt': -1}"),
        @CompoundIndex(name = "organization_submittedAt",
                def = "{'organizationId': 1, 'submittedAt': -1}"),
        @CompoundIndex(name = "organization_periodStartDate",
                def = "{'organizationId': 1, 'periodStartDate': -1}")
})
public class Timesheet {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String userId;

    private String profileId;

    @Indexed
    private String reportingManagerUserId;

    private LocalDate periodStartDate;
    private LocalDate periodEndDate;
    private List<TimesheetEntry> entries;
    private BigDecimal totalHours;
    private List<String> supportingDocumentIds;
    private TimesheetStatus status;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime submittedAt;

    private String reviewedByUserId;
    private LocalDateTime reviewedAt;
    private String reviewComment;
    private String changeReason;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
