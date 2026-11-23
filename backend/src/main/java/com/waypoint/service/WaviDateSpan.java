package com.waypoint.service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads an explicit date span from a message: two dates joined by a range word ("between Oct 5 and Oct 18", "from
 * 2026-10-05 to 2026-10-18", "5th of October through the 18th", "Oct 5 to 18", "Oct 5-18"). Token based: dates and
 * joiners are grammar shared by every question, so no accepted wording is listed. Pure; it never decides whose
 * records are read.
 *
 * <p>Without a year the first date is the most recent such date that is not in the future, and the second is the
 * first such date on or after it, so a span may end after today ("Nov 2 to Nov 8" asked on Nov 5).
 */
final class WaviDateSpan {

    private WaviDateSpan() {
    }

    private record Found(LocalDate date, int firstToken, int lastToken, boolean explicitYear) {
    }

    /**
     * {@code {from, to}} when the message names exactly one span of at most {@code maxDays} days, otherwise null (no
     * dates, a single date, more than two, or two dates that are not joined as a span).
     */
    static LocalDate[] find(String text, LocalDate today, int maxDays) {
        if (text == null || today == null) return null;
        List<String> tokens = tokens(text);
        List<Found> dates = dates(tokens, today);
        if (dates == null) return null;
        try {
            if (dates.isEmpty() || dates.size() > 2) return null;
            Found first = dates.get(0);
            LocalDate to;
            if (dates.size() == 2) {
                Found second = dates.get(1);
                if (!joined(tokens, first, second.firstToken())) return null;
                to = second.date();
                if (!second.explicitYear()) {
                    to = to.withYear(first.date().getYear());
                    if (to.isBefore(first.date())) to = to.plusYears(1);
                }
            } else {
                // "Oct 5 to 18": a bare day of the same month after a joiner.
                to = bareEnd(tokens, first);
                if (to == null) return null;
            }
            LocalDate from = first.date();
            if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= maxDays) return null;
            return new LocalDate[]{from, to};
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * One explicit calendar date in a message, or null when there is no date, the message names a span, or it names
     * more than one date. This shares the span grammar so an explicit day cannot fall through to a month token.
     */
    static LocalDate single(String text, LocalDate today) {
        if (text == null || today == null) return null;
        List<String> tokens = tokens(text);
        List<Found> dates = dates(tokens, today);
        if (dates == null || dates.size() != 1) return null;
        Found found = dates.get(0);
        // "Oct 5 to 18" is a span even though only its first endpoint has a month token.
        return bareEnd(tokens, found) == null ? found.date() : null;
    }

    private static List<Found> dates(List<String> tokens, LocalDate today) {
        List<Found> dates = new ArrayList<>();
        try {
            for (int index = 0; index < tokens.size(); index++) {
                Found found = dateAt(tokens, index, today);
                if (found == null) continue;
                dates.add(found);
                index = found.lastToken();
            }
            return dates;
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
    }

    private static LocalDate bareEnd(List<String> tokens, Found first) {
        int next = first.lastToken() + 1;
        if (next >= tokens.size() || !JOINERS.contains(tokens.get(next)) || tokens.get(next).equals("and")) return null;
        next++;
        if (next < tokens.size() && tokens.get(next).equals("the")) next++;
        Integer day = next < tokens.size() ? dayNumber(tokens.get(next)) : null;
        if (day == null || (next + 1 < tokens.size() && NOT_A_DAY_AFTER.contains(tokens.get(next + 1)))) return null;
        try {
            return first.date().withDayOfMonth(day);
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    /** Nouns a date can name a timesheet by: "my oct 12 timesheet", "my oct 19 week" (the week that date starts). */
    private static final List<String> RECORD_NOUNS = List.of("timesheet", "timesheets", "week");
    private static final List<String> RECORD_LINKS = List.of("of", "for", "from", "dated", "on");

    /**
     * The date a message uses to IDENTIFY a timesheet rather than to filter days: a single date next to the record
     * noun ("my oct 12 timesheet", "the timesheet for 2026-10-12", "timesheet dated oct 5"). Null when the message
     * names no date, more than one, or the date is not attached to the noun ("hours on oct 12").
     */
    static LocalDate recordDate(String text, LocalDate today) {
        if (text == null || today == null) return null;
        List<String> tokens = tokens(text);
        Found found = null;
        try {
            for (int index = 0; index < tokens.size(); index++) {
                Found candidate = dateAt(tokens, index, today);
                if (candidate == null) continue;
                if (found != null) return null;
                found = candidate;
                index = candidate.lastToken();
            }
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
        if (found == null) return null;
        int after = found.lastToken() + 1;
        if (after < tokens.size() && RECORD_NOUNS.contains(tokens.get(after))) return found.date();
        int before = found.firstToken() - 1;
        if (before >= 0 && RECORD_LINKS.contains(tokens.get(before))) before--;
        if (before >= 0 && tokens.get(before).equals("the")) before--;
        // "The timesheet for Oct 5" names a record; "the week of Oct 19" names a period, not a record.
        return before >= 0 && RECORD_NOUNS.contains(tokens.get(before)) && !tokens.get(before).equals("week")
                ? found.date() : null;
    }

    private static final List<String> JOINERS =List.of("to", "through", "thru", "until", "till", "and", "-");
    /** After a bare number these make it a quantity or a time, not a day of the month. */
    private static final List<String> NOT_A_DAY_AFTER = List.of("hours", "hour", "hrs", "hr", "days", "day", "am", "pm",
            "weeks", "week", "timesheets", "timesheet", "people", "employees");

    /** Only joiners (and "the") between the two dates; "and" joins dates only after "between". */
    private static boolean joined(List<String> tokens, Found first, int secondStart) {
        int index = first.lastToken() + 1;
        if (index >= secondStart || !JOINERS.contains(tokens.get(index))) return false;
        boolean and = tokens.get(index).equals("and");
        index++;
        if (index < secondStart && tokens.get(index).equals("the")) index++;
        if (index != secondStart) return false;
        if (!and) return true;
        int before = first.firstToken() - 1;
        if (before >= 0 && tokens.get(before).equals("the")) before--;
        return before >= 0 && tokens.get(before).equals("between");
    }

    /** Lower-case tokens; a dash between two numbers or words ("5-18", "5 - 18") is its own joiner token. */
    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        // Commas delimit date components. Treating them as whitespace accepts ordinary no-space punctuation
        // ("Oct 23,2026") without making the comma part of either the day or the year.
        text = text.replace(',', ' ');
        for (String raw : text.toLowerCase(Locale.ROOT).replace('–', '-').replace('—', '-').split("\\s+")) {
            String token = raw.replaceAll("^[^a-z0-9-]+", "").replaceAll("[^a-z0-9]+$", "");
            if (token.isEmpty()) {
                if (raw.contains("-")) tokens.add("-");
                continue;
            }
            if (isoDate(token) != null) {
                tokens.add(token);
                continue;
            }
            String[] parts = token.split("-", -1);
            for (int part = 0; part < parts.length; part++) {
                if (part > 0) tokens.add("-");
                if (!parts[part].isEmpty()) tokens.add(parts[part]);
            }
        }
        return tokens;
    }

    /**
     * A numeric date "10/21/2026", "10/21/26" or "10/21": month first (the format Waypoint's timesheets default to),
     * or day first only when the first number cannot be a month ("21/10/2026"). Without a year, the most recent such
     * date not in the future. The reply always states the date it was read as. Null when not a valid date.
     */
    static LocalDate numericDate(String token, LocalDate today) {
        if (token == null || today == null || !token.matches("\\d{1,2}/\\d{1,2}(/\\d{2}(\\d{2})?)?")) return null;
        String[] parts = token.split("/");
        int first = Integer.parseInt(parts[0]);
        int second = Integer.parseInt(parts[1]);
        int month = first <= 12 ? first : second;
        int day = first <= 12 ? second : first;
        try {
            if (parts.length == 3) {
                int year = Integer.parseInt(parts[2]);
                return LocalDate.of(year < 100 ? 2000 + year : year, month, day);
            }
            LocalDate thisYear = LocalDate.of(today.getYear(), month, day);
            return thisYear.isAfter(today) ? thisYear.minusYears(1) : thisYear;
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    /** The single numeric date a message names ("hours on 10/21/2026"), or null when it names none or several. */
    static LocalDate singleNumericDate(String text, LocalDate today) {
        if (text == null) return null;
        LocalDate found = null;
        for (String token : tokens(text)) {
            LocalDate date = numericDate(token, today);
            if (date == null) continue;
            if (found != null) return null;
            found = date;
        }
        return found;
    }

    private static Found dateAt(List<String> tokens, int index, LocalDate today) {
        String token = tokens.get(index);
        LocalDate iso = isoDate(token);
        if (iso != null) return new Found(iso, index, index, true);
        LocalDate numeric = numericDate(token, today);
        if (numeric != null) return new Found(numeric, index, index, token.chars().filter(ch -> ch == '/').count() == 2);
        Month month = month(token);
        if (month != null && index + 1 < tokens.size()) {
            Integer day = dayNumber(tokens.get(index + 1));
            if (day != null) return dated(tokens, month, day, index, index + 1, today);
        }
        Integer day = dayNumber(token);
        if (day != null) {
            int next = index + 1;
            if (next < tokens.size() && tokens.get(next).equals("of")) next++;
            Month after = next < tokens.size() ? month(tokens.get(next)) : null;
            // "5 oct", "5th of October"; not "5 may" (a verb) unless written "5th of may".
            if (after != null && (after != Month.MAY || next > index + 1 || !token.equals(String.valueOf(day)))) {
                return dated(tokens, after, day, index, next, today);
            }
        }
        return null;
    }

    private static Found dated(List<String> tokens, Month month, int day, int first, int last, LocalDate today) {
        Integer year = last + 1 < tokens.size() ? year(tokens.get(last + 1)) : null;
        if (year != null) return new Found(LocalDate.of(year, month, day), first, last + 1, true);
        LocalDate thisYear = LocalDate.of(today.getYear(), month, day);
        return new Found(thisYear.isAfter(today) ? thisYear.minusYears(1) : thisYear, first, last, false);
    }

    private static LocalDate isoDate(String token) {
        String[] parts = token.split("-");
        if (parts.length != 3 || year(parts[0]) == null || !digits(parts[1]) || !digits(parts[2])) return null;
        try {
            return LocalDate.of(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static Integer year(String token) {
        return token.length() == 4 && token.startsWith("20") && digits(token) ? Integer.valueOf(token) : null;
    }

    private static boolean digits(String token) {
        return !token.isEmpty() && token.length() <= 4 && token.chars().allMatch(Character::isDigit);
    }

    /** "5", "5th", "18th": a day of the month, 1 to 31. */
    private static Integer dayNumber(String token) {
        String number = token;
        for (String suffix : List.of("st", "nd", "rd", "th")) {
            if (number.endsWith(suffix) && number.length() > suffix.length()) {
                number = number.substring(0, number.length() - suffix.length());
                break;
            }
        }
        if (!digits(number) || number.length() > 2) return null;
        int day = Integer.parseInt(number);
        return day >= 1 && day <= 31 ? day : null;
    }

    /** A month by its full English name or its three-letter form ("oct", "sept"). */
    private static Month month(String token) {
        if (token.length() < 3) return null;
        for (Month month : Month.values()) {
            String full = month.getDisplayName(TextStyle.FULL, Locale.US).toLowerCase(Locale.ROOT);
            if (token.equals(full) || token.equals(full.substring(0, 3)) || (month == Month.SEPTEMBER && token.equals("sept"))) {
                return month;
            }
        }
        return null;
    }
}
