package com.waypoint.dto.myhr;

import java.util.List;

public record PayrollReadinessReminderResult(
        int requested,
        int matched,
        int eligible,
        int sent,
        int skipped,
        int skippedReady,
        int skippedDuplicate,
        int skippedUnauthorized,
        int skippedNoRecipient,
        int failed,
        List<PayrollReadinessReminderResultItem> results) {
}
