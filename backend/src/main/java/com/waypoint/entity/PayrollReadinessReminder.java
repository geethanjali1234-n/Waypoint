package com.waypoint.entity;

import com.waypoint.enums.PayrollReadinessReminderSource;
import com.waypoint.enums.PayrollReadinessReminderType;
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

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "payroll_readiness_reminders")
@CompoundIndexes({
        @CompoundIndex(name = "payroll_readiness_reminder_unique",
                def = "{'organizationId': 1, 'employeeUserId': 1, 'payrollMonth': 1, 'reminderType': 1}", unique = true),
        @CompoundIndex(name = "organization_payroll_month_sentAt",
                def = "{'organizationId': 1, 'payrollMonth': 1, 'sentAt': -1}")
})
public class PayrollReadinessReminder {
    @Id
    private String id;

    @Indexed
    private String organizationId;

    @Indexed
    private String employeeUserId;

    private LocalDate payrollMonth;
    private LocalDate requiredPeriodStart;
    private LocalDate requiredPeriodEnd;
    private PayrollReadinessReminderType reminderType;
    private PayrollReadinessReminderSource source;
    private String sentByUserId;
    private String notificationId;

    @Indexed(direction = IndexDirection.DESCENDING)
    private LocalDateTime sentAt;
}
