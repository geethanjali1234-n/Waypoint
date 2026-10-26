package com.waypoint.repository;

import com.waypoint.entity.AuditLog;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface AuditLogRepository extends MongoRepository<AuditLog, String> {

    List<AuditLog> findByOrganizationIdOrderByPerformedAtDesc(String organizationId);

    List<AuditLog> findByOrganizationIdAndPerformedByUserIdOrderByPerformedAtDesc(
            String organizationId, String performedByUserId);

    List<AuditLog> findByOrganizationIdAndEntityTypeAndEntityIdOrderByPerformedAtDesc(
            String organizationId, String entityType, String entityId);
}
