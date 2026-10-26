package com.waypoint.dto.myhr;

import com.waypoint.enums.PayrollReadinessState;

import java.time.LocalDateTime;

public record PayrollReadinessReminderResultItem(
        String employeeUserId,
        boolean sent,
        boolean skipped,
        String reason,
        PayrollReadinessState readinessState,
        LocalDateTime sentAt,
        LocalDateTime lastSentAt) {
}
