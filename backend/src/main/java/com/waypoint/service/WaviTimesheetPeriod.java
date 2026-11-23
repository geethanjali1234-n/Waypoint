package com.waypoint.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The structured form of "which of my timesheets": the arguments of {@code get_my_timesheets}, and their
 * conversion to and from {@link MyHrConversationIntents.TimesheetRange}. Built only from enums, ISO dates and
 * small integers, so the reply's period label is always generated here, never copied from a router.
 *
 * <p>The conversion reproduces the existing parser's ranges exactly, labels included. Explicit ranges use one
 * bounded ISO date-range value, so they follow the same validated tool path as days, weeks and months rather than carrying
 * an ad hoc conversation-only representation.
 */
final class WaviTimesheetPeriod {

    private WaviTimesheetPeriod() {
    }

    enum Period { LATEST, DAY, WEEK, MONTH, RANGE }

    enum Relative { NONE, TODAY, YESTERDAY, THIS, LAST, NEXT, WEEKS_AGO }

    static final int PAST_DAYS = 5 * 366;
    static final int FUTURE_DAYS = 366;
    static final int PAST_MONTHS = 60;
    static final int FUTURE_MONTHS = 12;
    static final int MAX_WEEKS_AGO = 6;

    static final WaviToolArguments.Schema SCHEMA = WaviToolArguments.Schema.of(
            WaviToolArguments.Spec.enumOf("period", true, Period.class,
                    "LATEST for the most recent timesheet; otherwise the kind of period asked about"),
            WaviToolArguments.Spec.date("date", false, PAST_DAYS, FUTURE_DAYS,
                    "A day in the period (DAY or WEEK), when the user named a date"),
            WaviToolArguments.Spec.month("month", false, PAST_MONTHS, FUTURE_MONTHS,
                    "The month asked about (MONTH), when the user named one"),
            WaviToolArguments.Spec.dateRange("range", false, PAST_DAYS, FUTURE_DAYS,
                    "Inclusive start/end dates of an explicit RANGE"),
            WaviToolArguments.Spec.enumOf("relative", false, Relative.class,
                    "A period relative to today instead of a date: TODAY, YESTERDAY (DAY); THIS, LAST, NEXT (WEEK or "
                            + "MONTH); WEEKS_AGO with weeksAgo (WEEK)"),
            WaviToolArguments.Spec.integer("weeksAgo", false, 1, MAX_WEEKS_AGO, "With relative WEEKS_AGO only"),
            WaviToolArguments.Spec.enumOf("detail", false, Detail.class,
                    "STATUS: status by period; HOURS: period totals; DAILY_HOURS: actual entries per date; PERIOD: dates only; SUBMISSION_DATE: latest submission timestamp (LATEST only)"));

    /** What about the period is asked: its timesheets' status, or the hours recorded. */
    enum Detail { STATUS, HOURS, SUBMISSION_DATE, DAILY_HOURS, PERIOD }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US);

    /** Null when the arguments are a consistent period; otherwise a problem code. */
    static String check(WaviToolArguments.Validated arguments, LocalDate today) {
        Period period = arguments.enumValue("period", Period.class);
        if (period == null) return "INVALID_PERIOD";
        if (period == Period.LATEST) {
            // Only the detail qualifier may accompany LATEST: it names what is asked, not another period.
            int qualifiers = arguments.has("detail") ? 1 : 0;
            return arguments.names().size() == 1 + qualifiers ? null : "INCONSISTENT_PERIOD";
        }
        if ("SUBMISSION_DATE".equals(arguments.text("detail"))) return "INCONSISTENT_PERIOD";
        return range(arguments, today) == null ? "INCONSISTENT_PERIOD" : null;
    }

    /** The range the arguments name, or null when they are inconsistent (or LATEST, which is not a range). */
    static MyHrConversationIntents.TimesheetRange range(WaviToolArguments.Validated arguments, LocalDate today) {
        Period period = arguments.enumValue("period", Period.class);
        Relative relative = arguments.has("relative") ? arguments.enumValue("relative", Relative.class) : Relative.NONE;
        LocalDate date = arguments.date("date");
        YearMonth month = arguments.month("month");
        WaviToolArguments.DateRange explicit = arguments.dateRange("range");
        Integer weeksAgo = arguments.integer("weeksAgo");
        if (period == null || relative == null || today == null) return null;
        if (weeksAgo != null && relative != Relative.WEEKS_AGO) return null;
        return switch (period) {
            case LATEST -> null;
            case DAY -> {
                if (month != null || explicit != null) yield null;
                if (relative == Relative.NONE) yield date == null ? null : day(date, null, today);
                if (date != null) yield null;
                yield switch (relative) {
                    case TODAY -> day(today, "today", today);
                    case YESTERDAY -> day(today.minusDays(1), "yesterday", today);
                    default -> null;
                };
            }
            case WEEK -> {
                if (month != null || explicit != null) yield null;
                if (relative == Relative.NONE) yield date == null ? null : week(date, null, today);
                if (date != null) yield null;
                yield switch (relative) {
                    case THIS -> week(today, "this week", today);
                    case LAST -> week(today.minusWeeks(1), "last week", today);
                    case NEXT -> week(today.plusWeeks(1), "next week", today);
                    case WEEKS_AGO -> weeksAgo == null ? null
                            : week(today.minusWeeks(weeksAgo), weeksAgo + (weeksAgo == 1 ? " week ago" : " weeks ago"), today);
                    default -> null;
                };
            }
            case MONTH -> {
                if (date != null || explicit != null) yield null;
                if (relative == Relative.NONE) yield month == null ? null : month(month, null, today);
                if (month != null) yield null;
                yield switch (relative) {
                    case THIS -> month(YearMonth.from(today), "this month", today);
                    case LAST -> month(YearMonth.from(today).minusMonths(1), "last month", today);
                    case NEXT -> month(YearMonth.from(today).plusMonths(1), "next month", today);
                    default -> null;
                };
            }
            case RANGE -> {
                if (date != null || month != null || explicit == null || relative != Relative.NONE || weeksAgo != null) yield null;
                yield MyHrConversationIntents.spanOf(explicit.from(), explicit.to(), today);
            }
        };
    }

    /**
     * The arguments that name exactly {@code range}, or null when no arguments reproduce it (including its label
     * and whether it includes today), so the caller keeps the existing route.
     */
    static Map<String, Object> arguments(MyHrConversationIntents.TimesheetRange range, LocalDate today) {
        if (range == null || today == null) return null;
        Map<String, Object> arguments = new LinkedHashMap<>();
        String label = range.label();
        switch (range.unit()) {
            case DAY -> {
                arguments.put("period", Period.DAY.name());
                if (label.startsWith("today (")) arguments.put("relative", Relative.TODAY.name());
                else if (label.startsWith("yesterday (")) arguments.put("relative", Relative.YESTERDAY.name());
                else arguments.put("date", range.from().toString());
            }
            case WEEK -> {
                arguments.put("period", Period.WEEK.name());
                switch (label) {
                    case "this week" -> arguments.put("relative", Relative.THIS.name());
                    case "last week" -> arguments.put("relative", Relative.LAST.name());
                    case "next week" -> arguments.put("relative", Relative.NEXT.name());
                    default -> {
                        Integer ago = weeksAgo(label);
                        if (ago != null) {
                            arguments.put("relative", Relative.WEEKS_AGO.name());
                            arguments.put("weeksAgo", ago);
                        } else {
                            arguments.put("date", range.from().toString());
                        }
                    }
                }
            }
            case MONTH -> {
                arguments.put("period", Period.MONTH.name());
                switch (label) {
                    case "this month" -> arguments.put("relative", Relative.THIS.name());
                    case "last month" -> arguments.put("relative", Relative.LAST.name());
                    case "next month" -> arguments.put("relative", Relative.NEXT.name());
                    default -> arguments.put("month", YearMonth.from(range.from()).toString());
                }
            }
            case SPAN -> {
                arguments.put("period", Period.RANGE.name());
                arguments.put("range", range.from() + "/" + range.to());
            }
        }
        WaviToolArguments.Validation validation = SCHEMA.validate(arguments, today);
        if (!validation.valid()) return null;
        // Only an exact round trip counts: same dates, label and "includes today".
        return range.equals(range(validation.arguments(), today)) ? Map.copyOf(arguments) : null;
    }

    private static Integer weeksAgo(String label) {
        for (int weeks = 1; weeks <= MAX_WEEKS_AGO; weeks++) {
            if (label.equals(weeks + (weeks == 1 ? " week ago" : " weeks ago"))) return weeks;
        }
        return null;
    }

    // The same constructions as MyHrConversationIntents' private week/month/day, so ranges compare equal.

    private static MyHrConversationIntents.TimesheetRange week(LocalDate anyDay, String label, LocalDate today) {
        LocalDate monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        boolean current = today != null && !today.isBefore(monday) && !today.isAfter(sunday);
        return new MyHrConversationIntents.TimesheetRange(monday, sunday,
                label == null ? "the week of " + monday.format(DAY) : label, current,
                MyHrConversationIntents.TimesheetRange.Unit.WEEK);
    }

    private static MyHrConversationIntents.TimesheetRange month(YearMonth month, String label, LocalDate today) {
        boolean current = today != null && YearMonth.from(today).equals(month);
        String name = month.getMonth().getDisplayName(TextStyle.FULL, Locale.US) + " " + month.getYear();
        return new MyHrConversationIntents.TimesheetRange(month.atDay(1), month.atEndOfMonth(),
                label == null ? name : label, current, MyHrConversationIntents.TimesheetRange.Unit.MONTH);
    }

    private static MyHrConversationIntents.TimesheetRange day(LocalDate date, String label, LocalDate today) {
        return new MyHrConversationIntents.TimesheetRange(date, date,
                label == null ? date.format(DAY) : label + " (" + date.format(DAY) + ")", date.equals(today),
                MyHrConversationIntents.TimesheetRange.Unit.DAY);
    }
}
