package com.waypoint.repository;

import com.waypoint.entity.Paystub;
import com.waypoint.enums.PaystubStatus;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface PaystubRepository extends MongoRepository<Paystub, String> {

    List<Paystub> findByOrganizationIdAndUserIdOrderByGeneratedAtDesc(
            String organizationId, String userId);

    List<Paystub> findByPayrollRecordIdOrderByVersionDesc(String payrollRecordId);

    Optional<Paystub> findByPayrollRecordIdAndVersion(String payrollRecordId, Integer version);

    Optional<Paystub> findByPayrollRecordIdAndStatus(String payrollRecordId, PaystubStatus status);
}
