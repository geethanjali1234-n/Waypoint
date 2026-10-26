package com.waypoint.repository;

import com.waypoint.entity.PayrollRecord;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;

public interface PayrollRecordRepository extends MongoRepository<PayrollRecord, String> {

    List<PayrollRecord> findByOrganizationIdAndUserIdOrderByProcessedAtDesc(
            String organizationId, String userId);

    List<PayrollRecord> findByOrganizationIdAndReportingManagerUserIdOrderByProcessedAtDesc(
            String organizationId, String reportingManagerUserId);

    List<PayrollRecord> findByUserIdAndPayrollPeriodStartAndPayrollPeriodEnd(
            String userId, LocalDate payrollPeriodStart, LocalDate payrollPeriodEnd);
}
