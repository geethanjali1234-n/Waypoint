package com.waypoint.dto.myhr;

import com.waypoint.enums.TimesheetStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Minimal own-record projection: no identity, document, description or reviewer content. */
public record MyHrDailyHours(LocalDate from, LocalDate to, List<Submission> submissions, boolean truncated) {
    public MyHrDailyHours { submissions = List.copyOf(submissions); }
    public record Submission(LocalDate from, LocalDate to, TimesheetStatus status, List<Entry> entries) {
        public Submission { entries = List.copyOf(entries); }
    }
    public record Entry(LocalDate day, BigDecimal hours) {}
}
