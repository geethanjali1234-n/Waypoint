package com.waypoint.entity;

import com.waypoint.enums.RelationshipType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "reporting_assignments")
@CompoundIndexes({
        @CompoundIndex(
                name = "one_active_relationship_per_employee",
                def = "{'organizationId': 1, 'employeeUserId': 1, 'relationshipType': 1}",
                unique = true,
                partialFilter = "{'isActive': true}"),
        @CompoundIndex(
                name = "organization_employee_active",
                def = "{'organizationId': 1, 'employeeUserId': 1, 'isActive': 1}")
})
public class ReportingAssignment {

    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String employeeUserId;

    @Indexed
    private String reportingManagerUserId;

    @Indexed
    private RelationshipType relationshipType;

    @Indexed
    private Boolean isActive;

    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;
    private String assignedByUserId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
