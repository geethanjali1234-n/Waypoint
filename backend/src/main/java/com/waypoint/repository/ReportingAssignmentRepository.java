package com.waypoint.repository;

import com.waypoint.entity.ReportingAssignment;
import com.waypoint.enums.RelationshipType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ReportingAssignmentRepository extends MongoRepository<ReportingAssignment, String> {

    Optional<ReportingAssignment> findByEmployeeUserIdAndRelationshipTypeAndIsActiveTrue(
            String employeeUserId, RelationshipType relationshipType);

    List<ReportingAssignment> findByReportingManagerUserId(String reportingManagerUserId);

    List<ReportingAssignment> findByOrganizationIdAndReportingManagerUserIdAndRelationshipTypeAndIsActiveTrue(
            String organizationId, String reportingManagerUserId, RelationshipType relationshipType);

    List<ReportingAssignment> findByEmployeeUserIdOrderByEffectiveFromDescCreatedAtDesc(String employeeUserId);

    List<ReportingAssignment> findByOrganizationIdAndRelationshipTypeAndIsActiveTrue(
            String organizationId, RelationshipType relationshipType);
}
