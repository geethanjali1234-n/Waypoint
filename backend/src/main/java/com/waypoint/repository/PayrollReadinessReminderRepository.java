package com.waypoint.repository;

import com.waypoint.entity.PayrollReadinessReminder;
import com.waypoint.enums.PayrollReadinessReminderType;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PayrollReadinessReminderRepository extends MongoRepository<PayrollReadinessReminder, String> {
    Optional<PayrollReadinessReminder> findByOrganizationIdAndEmployeeUserIdAndPayrollMonthAndReminderType(
            String organizationId, String employeeUserId, LocalDate payrollMonth, PayrollReadinessReminderType reminderType);

    List<PayrollReadinessReminder> findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderType(
            String organizationId, Collection<String> employeeUserIds, LocalDate payrollMonth, PayrollReadinessReminderType reminderType);

    List<PayrollReadinessReminder> findByOrganizationIdAndEmployeeUserIdInAndPayrollMonthAndReminderTypeIn(
            String organizationId, Collection<String> employeeUserIds, LocalDate payrollMonth, Collection<PayrollReadinessReminderType> reminderTypes);
}
