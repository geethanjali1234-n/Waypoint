package com.waypoint.service;

import com.waypoint.dto.myhr.TimesheetEntryResponse;
import com.waypoint.dto.myhr.TimesheetResponse;
import com.waypoint.enums.TimesheetStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The hours Waypoint's Timesheets page shows in Hours Overview for a range of dates, computed with the SAME rule the
 * page uses ({@code consolidatePeriodEntries(records, start, end, includeDrafts = true)} in TimesheetsPage.jsx), so
 * Wavi and the page never disagree:
 * <ul>
 *   <li>every submission or resubmission is a separate record; records are ordered by updatedAt, else submittedAt,
 *       else createdAt (oldest first);</li>
 *   <li>for each work date, the entry of the LATEST record that has one decides the day;</li>
 *   <li>if that latest entry belongs to a REJECTED record, the day falls back to the most recent APPROVED entry with
 *       hours for that date, otherwise it counts 0;</li>
 *   <li>a SUBMITTED, APPROVED or DRAFT latest entry counts as entered;</li>
 *   <li>the total is the sum of the per-day values, and worked days are the days with more than 0 hours.</li>
 * </ul>
 * So overlapping submissions are never added twice. Pure: callers pass only records they are authorized to see.
 */
final class TimesheetEffectiveHours {

    private TimesheetEffectiveHours() {
    }

    /** One date's effective hours and the record status they come from (null status = no hours). */
    record Day(LocalDate date, BigDecimal hours, TimesheetStatus status, boolean fallbackFromRejected,
               int submissionsWithEntry) {
    }

    /** The effective days of a range, with totals by the status the hours come from. */
    record Result(LocalDate from, LocalDate to, List<Day> days, BigDecimal total, int workedDays,
                  Map<TimesheetStatus, BigDecimal> byStatus, int overlappingDays) {

        BigDecimal hoursOn(LocalDate date) {
            return days.stream().filter(day -> day.date().equals(date)).findFirst().map(Day::hours).orElse(BigDecimal.ZERO);
        }

        Day day(LocalDate date) {
            return days.stream().filter(day -> day.date().equals(date)).findFirst().orElse(null);
        }
    }

    private static LocalDateTime orderKey(TimesheetResponse record) {
        return record.updatedAt() != null ? record.updatedAt() : record.submittedAt() != null ? record.submittedAt()
                : record.createdAt();
    }

    static Result compute(List<TimesheetResponse> records, LocalDate from, LocalDate to) {
        List<TimesheetResponse> chronological = (records == null ? List.<TimesheetResponse>of() : records).stream()
                .filter(record -> record != null && record.entries() != null)
                .sorted(Comparator.comparing(TimesheetEffectiveHours::orderKey, Comparator.nullsFirst(Comparator.naturalOrder())))
                .toList();
        Map<LocalDate, List<Object[]>> byDate = new LinkedHashMap<>();
        for (TimesheetResponse record : chronological) {
            for (TimesheetEntryResponse entry : record.entries()) {
                if (entry == null || entry.workDate() == null || entry.workDate().isBefore(from) || entry.workDate().isAfter(to)) continue;
                BigDecimal hours = entry.hoursWorked() == null ? BigDecimal.ZERO : entry.hoursWorked();
                byDate.computeIfAbsent(entry.workDate(), ignored -> new ArrayList<>()).add(new Object[]{hours, record.status()});
            }
        }
        List<Day> days = new ArrayList<>();
        Map<TimesheetStatus, BigDecimal> byStatus = new LinkedHashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        int worked = 0;
        int overlapping = 0;
        for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
            List<Object[]> history = byDate.getOrDefault(date, List.of());
            if (history.size() > 1) overlapping++;
            if (history.isEmpty()) {
                days.add(new Day(date, BigDecimal.ZERO, null, false, 0));
                continue;
            }
            Object[] latest = history.getLast();
            BigDecimal hours = (BigDecimal) latest[0];
            TimesheetStatus status = (TimesheetStatus) latest[1];
            boolean fallback = false;
            if (status == TimesheetStatus.REJECTED) {
                Object[] approved = null;
                for (int index = history.size() - 1; index >= 0; index--) {
                    Object[] item = history.get(index);
                    if (item[1] == TimesheetStatus.APPROVED && ((BigDecimal) item[0]).signum() > 0) {
                        approved = item;
                        break;
                    }
                }
                if (approved == null) {
                    hours = BigDecimal.ZERO;
                    status = TimesheetStatus.REJECTED;
                } else {
                    hours = (BigDecimal) approved[0];
                    status = TimesheetStatus.APPROVED;
                    fallback = true;
                }
            }
            days.add(new Day(date, hours, status, fallback, history.size()));
            total = total.add(hours);
            if (hours.signum() > 0) {
                worked++;
                byStatus.merge(status, hours, BigDecimal::add);
            }
        }
        return new Result(from, to, List.copyOf(days), total, worked, byStatus, overlapping);
    }
}
