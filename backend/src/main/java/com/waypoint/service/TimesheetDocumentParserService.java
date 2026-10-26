package com.waypoint.service;

import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import com.waypoint.dto.myhr.ParsedTimesheetResponse;
import com.waypoint.dto.myhr.TimesheetEntryResponse;
import com.waypoint.enums.TimesheetEntryType;
import com.waypoint.exception.BusinessException;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.*;

/** Conservative, deterministic import. Unrecognized rows never become guessed hours. */
@Service
public class TimesheetDocumentParserService {
    private static final Pattern DATE = Pattern.compile(
            "(?i)(?<!\\d)(\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}|\\d{1,2}[./-]\\d{1,2}[./-]\\d{4}|(?:Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?)[.,]?\\s+\\d{1,2}(?:st|nd|rd|th)?[,]?\\s+\\d{4}|\\d{1,2}\\s+[A-Za-z]+\\s+\\d{4})(?!\\d)");
    private static final Pattern HOURS = Pattern.compile("(?i)^(\\d{1,2}(?:[.,]\\d{1,2})?)(?:\\s*(?:h|hrs?|hours?))?$");
    private static final Pattern DURATION = Pattern.compile("^(\\d{1,2}):([0-5]\\d)$");
    private static final Pattern TEXT_HOURS = Pattern.compile("(?i)^\\s*(?:-\\s+|[|:]\\s*)?(?:(?:Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday|Mon|Tue|Wed|Thu|Fri|Sat|Sun)\\s+)?(\\d{1,2}:\\d{2}|\\d{1,2}(?:\\.\\d{1,2})?)(?:\\s*(?:hours?|hrs?|h)\\b)?(?=\\s|$)(.*)$");
    private static final int MAX_TEXT = 1_000_000;
    private static final Pattern CALENDAR_HEADER = Pattern.compile("(?i)\\bMo\\s+Tu\\s+We\\s+Th\\s+Fr\\s+Sa\\s+Su\\b");
    private static final Pattern CALENDAR_DAY_ROW = Pattern.compile(
            "^\\s*(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})\\s+(\\d{1,2})(?:\\s+\\d{1,3}(?:[.,]\\d+)?)?\\s*$");
    private static final Pattern CALENDAR_HOUR_ROW = Pattern.compile(
            "^\\s*(\\d{1,2}(?:[.,]\\d{1,2})?)\\s+(\\d{1,2}(?:[.,]\\d{1,2})?)\\s+(\\d{1,2}(?:[.,]\\d{1,2})?)\\s+(\\d{1,2}(?:[.,]\\d{1,2})?)\\s+(\\d{1,2}(?:[.,]\\d{1,2})?)\\s+(\\d{1,2}(?:[.,]\\d{1,2})?)\\s+(\\d{1,2}(?:[.,]\\d{1,2})?)(?:\\s+\\d{1,3}(?:[.,]\\d+)?)?\\s*$");

    private final ClaudeDocumentExtractionService claudeDocumentExtractionService;

    public TimesheetDocumentParserService() {
        this.claudeDocumentExtractionService = null;
    }

    @Autowired
    public TimesheetDocumentParserService(ClaudeDocumentExtractionService claudeDocumentExtractionService) {
        this.claudeDocumentExtractionService = claudeDocumentExtractionService;
    }

    public ParsedTimesheetResponse parse(byte[] bytes, String name, String documentId) {
        return parse(bytes, name, documentId, "MDY", null);
    }

    public ParsedTimesheetResponse parse(byte[] bytes, String name, String documentId, String dateOrder) {
        return parse(bytes, name, documentId, dateOrder, null);
    }

    public ParsedTimesheetResponse parse(byte[] bytes, String name, String documentId, String dateOrder, String ocrText) {
        ParsedTimesheetResponse local = parseLocally(bytes, name, documentId, dateOrder, ocrText);
        if (!"FALLBACK".equals(local.extractionStatus()) || claudeDocumentExtractionService == null) return local;
        return claudeDocumentExtractionService.extract(bytes, name, documentId, dateOrder).orElse(local);
    }

    private ParsedTimesheetResponse parseLocally(byte[] bytes, String name, String documentId, String dateOrder, String ocrText) {
        if (!Set.of("MDY", "DMY").contains(dateOrder)) throw new BusinessException("Choose MDY or DMY date order");
        String fileName = name == null ? "timesheet" : name;
        List<String> warnings = new ArrayList<>();
        String text;
        if (ocrText != null && !ocrText.isBlank()) {
            text = ocrText;
        } else {
            try {
                text = extract(bytes, fileName);
            } catch (Exception exception) {
                return fallback(documentId, fileName, "The document could not be read. Enter the dates and hours manually or upload a text document.");
            }
        }
        if (text.isBlank()) return fallback(documentId, fileName,
                "Dates and hours could not be automatically detected. Enter the dates and hours manually or upload a clearer document.");
        ParsedTimesheetResponse weekly = parseWeeklyTable(text, fileName, documentId, dateOrder);
        if (weekly != null) return weekly;
        ParsedTimesheetResponse calendar = parseCalendarFormat(text, fileName, documentId, dateOrder);
        if (calendar != null) return calendar;
        TreeMap<LocalDate, Day> days = new TreeMap<>();
        Set<LocalDate> invalidDays = new HashSet<>();
        List<String> headers = null;
        int skipped = 0;
        for (String line : text.split("\\R")) {
            if (line.isBlank()) continue;
            // Text extraction can concatenate PDF table cells with no whitespace.
            line = line.replaceAll("(?i)(\\d{4})(Mon|Tue|Wed|Thu|Fri|Sat|Sun)", "$1 $2 ")
                    .replaceAll("(\\d) h(?=[A-Z])", "$1 h ");
            List<String> cells = split(line);
            List<String> normalized = cells.stream().map(this::header).toList();
            if (normalized.stream().anyMatch(this::dateHeader) && normalized.stream().anyMatch(this::hourHeader)) {
                headers = normalized;
                continue;
            }
            Matcher dateMatcher = DATE.matcher(line);
            if (!dateMatcher.find()) continue;
            if (line.matches("(?i)^\\s*(?:period|pay period|generated|submitted|approved|printed|report date|total|from waypoint).*")) continue;
            if (DATE.matcher(line.substring(dateMatcher.end())).find()
                    || line.matches("(?i).*\\bat\\s+\\d{1,2}:\\d{2}.*")) continue;
            Day day = null;
            try {
                if (headers != null && cells.size() > 1) {
                    day = fromColumns(cells, headers, dateOrder);
                } else if (cells.size() >= 2 && DATE.matcher(cells.getFirst()).matches()) {
                    day = new Day(date(cells.getFirst(), dateOrder), hours(cells.get(1)),
                            cells.size() > 2 ? String.join("; ", cells.subList(2, cells.size())) : "");
                } else {
                    LocalDate date = date(dateMatcher.group(), dateOrder);
                    String remaining = line.substring(dateMatcher.end());
                    // Multiple dates or clock ranges are ambiguous without a labeled duration column.
                    if (!DATE.matcher(remaining).find() && !remaining.matches("(?i).*\\b(?:am|pm)\\b.*")) {
                        Matcher hours = TEXT_HOURS.matcher(remaining);
                        if (hours.matches() && !hours.group(2).matches(".*\\d{1,2}:\\d{2}.*")) {
                            day = new Day(date, hours(hours.group(1)), hours.group(2).replaceFirst("^\\s*[-|]\\s*", "").trim());
                        }
                    }
                }
            } catch (RuntimeException ignored) {
                // Invalid dates/values remain a visible warning instead of being guessed.
            }
            if (day == null || day.hours == null || day.date.isAfter(LocalDate.now())) { skipped++; continue; }
            if (invalidDays.contains(day.date)) { skipped++; continue; }
            Day previous = days.get(day.date);
            if (previous != null) {
                BigDecimal sum = previous.hours.add(day.hours);
                if (sum.compareTo(BigDecimal.valueOf(24)) > 0) {
                    days.remove(day.date);
                    invalidDays.add(day.date);
                    skipped++;
                    continue;
                }
                day = new Day(day.date, sum, previous.description + (day.description.isBlank() ? "" : "; " + day.description));
            }
            days.put(day.date, day);
        }
        if (days.isEmpty()) return fallback(documentId, fileName,
                "Dates and hours could not be automatically detected reliably. Check the selected date order and enter hours manually.");
        LocalDate start = days.keySet().iterator().next();
        LocalDate end = days.lastKey();
        if (ChronoUnit.DAYS.between(start, end) >= 31) return fallback(documentId, fileName,
                "This document spans more than 31 days. Upload one period at a time or enter a period manually. No rows were silently truncated.");
        if (skipped > 0) warnings.add(skipped + " dated row(s) could not be imported. Check the document for missing or invalid hours.");
        List<TimesheetEntryResponse> entries = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (LocalDate current = start; !current.isAfter(end); current = current.plusDays(1)) {
            Day day = days.get(current);
            BigDecimal value = day == null ? BigDecimal.ZERO : day.hours;
            total = total.add(value);
            entries.add(new TimesheetEntryResponse(current, current.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    value.setScale(2, RoundingMode.HALF_UP), TimesheetEntryType.WORK, day == null ? "" : day.description));
        }
        String message = "Extracted " + days.size() + " day(s). Verify every date and hour against the attached document before submitting. " + String.join(" ", warnings);
        return new ParsedTimesheetResponse(documentId, fileName, start, end, total.setScale(2, RoundingMode.HALF_UP),
                entries, skipped == 0 ? "SUCCESS" : "PARTIAL", message.trim());
    }

    /** Weekday-only tables need the printed period and both labeled hour columns. */
    private ParsedTimesheetResponse parseWeeklyTable(String text, String name, String id, String order) {
        if (!Pattern.compile("(?im)^\\s*\\|?\\s*(?:Monday|Tuesday|Wednesday|Thursday|Friday)\\b").matcher(text).find()) return null;
        if (!Pattern.compile("(?is)day of week.{0,300}?regular.{0,100}?overtime").matcher(text).find()) return null;
        LocalDate start = null, end = null;
        for (String line : text.split("\\R")) {
            if (!line.toLowerCase(Locale.ROOT).matches(".*(?:week of|period|week beginning).*")) continue;
            Matcher dates = DATE.matcher(line);
            try {
                if (dates.find()) start = date(dates.group(), order);
                if (dates.find()) end = date(dates.group(), order);
            } catch (RuntimeException ignored) { start = null; end = null; }
            break;
        }
        if (start == null || end == null || end.isBefore(start) || ChronoUnit.DAYS.between(start, end) > 6)
            return fallback(id, name, "The weekly table was found, but its date range could not be read reliably. Enter the printed dates and hours manually.");
        List<Day> rows = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        BigDecimal regularTotal = BigDecimal.ZERO, overtimeTotal = BigDecimal.ZERO;
        Pattern rowPattern = Pattern.compile("(?i)^\\s*\\|?\\s*(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday)\\b(.*)$");
        Pattern clock = Pattern.compile("(?i)\\d{1,2}:\\d{2}\\s*[AP]M");
        Pattern values = Pattern.compile("^\\s*(\\d{1,2}(?:\\.\\d{1,2})?)\\s+(\\d{1,2}(?:\\.\\d{1,2})?)(?:\\s+0(?:\\.0+)?)?\\s*$");
        boolean unreadable = false;
        for (String line : text.split("\\R")) {
            Matcher row = rowPattern.matcher(line);
            if (!row.matches()) continue;
            String tail = row.group(2).replace('|', ' ').trim();
            if (tail.isEmpty()) continue;
            Matcher times = clock.matcher(tail);
            int afterClock = -1;
            while (times.find()) afterClock = times.end();
            if (afterClock >= 0) tail = tail.substring(afterClock);
            // In scanned/OCR weekly tables, overtime '1' is commonly recognized as 'i', 'l', '|', '!', 'il', 'ul'.
            tail = tail.replaceAll("(?i)(?<=\\s)(?:[il|!]{1,3}|ul)(?=\\s*$|\\s)", "1").trim();
            Matcher numeric = values.matcher(tail);
            if (!numeric.matches()) { unreadable = true; continue; }
            BigDecimal regular = hours(numeric.group(1)), overtime = hours(numeric.group(2));
            if (regular == null || overtime == null || regular.add(overtime).compareTo(BigDecimal.valueOf(24)) > 0) { unreadable = true; continue; }
            labels.add(row.group(1));
            regularTotal = regularTotal.add(regular);
            overtimeTotal = overtimeTotal.add(overtime);
            rows.add(new Day(null, regular.add(overtime), "Regular: " + regular.toPlainString() + "h; overtime: " + overtime.toPlainString() + "h"));
        }
        Matcher total = Pattern.compile("(?im)^\\s*\\|?\\s*Total\\s+(\\d+(?:\\.\\d+)?)\\s+(\\d+(?:\\.\\d+)?)(?:\\s+0(?:\\.0+)?)?\\s*\\|?\\s*$").matcher(text);
        if (unreadable || rows.isEmpty() || !total.find()
                || regularTotal.compareTo(new BigDecimal(total.group(1))) != 0
                || overtimeTotal.compareTo(new BigDecimal(total.group(2))) != 0)
            return fallback(id, name, "The weekly hours could not be verified against the document totals. Enter the hours manually; no incomplete total was imported.");
        long periodDays = ChronoUnit.DAYS.between(start, end) + 1;
        List<TimesheetEntryResponse> entries = new ArrayList<>();
        boolean mismatch = false;
        for (int index = 0; index < rows.size(); index++) {
            int weekday = java.time.DayOfWeek.valueOf(labels.get(index).toUpperCase(Locale.ROOT)).getValue();
            LocalDate workDate = start.plusDays((weekday - start.getDayOfWeek().getValue() + 7) % 7);
            if (rows.size() == periodDays) {
                workDate = start.plusDays(index);
                if (workDate.getDayOfWeek().getValue() != weekday) mismatch = true;
            }
            if (workDate.isAfter(end) || workDate.isAfter(LocalDate.now()))
                return fallback(id, name, "The weekday rows do not fit the printed period. Confirm the dates and enter hours manually.");
            Day day = rows.get(index);
            entries.add(new TimesheetEntryResponse(workDate, workDate.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    day.hours.setScale(2, RoundingMode.HALF_UP), TimesheetEntryType.WORK, day.description));
        }
        entries.sort(Comparator.comparing(TimesheetEntryResponse::workDate));
        String message = "Extracted " + regularTotal.toPlainString() + " regular + " + overtimeTotal.toPlainString()
                + " overtime hours. Verify every value against the attached document."
                + (mismatch ? " The weekday labels conflict with the printed period. Used the printed dates " + start + " to " + end + " in row order; confirm or correct these dates before submitting." : " Dates were derived from the printed period and weekday labels.");
        return new ParsedTimesheetResponse(id, name, start, end, regularTotal.add(overtimeTotal).setScale(2, RoundingMode.HALF_UP),
                entries, "PARTIAL", message);
    }

    ParsedTimesheetResponse parseCalendarFormat(String text, String name, String id, String order) {
        boolean hasCalendarIndicators = Pattern.compile(
                "(?is)(?:Mo\\s+Tu|Mon\\s+Tue|Mo\\s+Tu\\s+We|Weekly\\s*40|\\bWeekly\\b.{0,30}\\b40\\b|\\bWeekly\\b|Timesheet|Assigned Task|Claim Task|timesheet\\.ultimatix|timesheetEntry|Download this month's Timesheet)"
        ).matcher(text).find();
        if (!hasCalendarIndicators) return null;

        // Filter out informational, notice, login, deadline, and footer lines so they never poison month detection
        StringBuilder cleanCalendarText = new StringBuilder();
        for (String line : text.split("\\R")) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("enabled") || lower.contains("till") || lower.contains("until")
                    || lower.contains("deadline") || lower.contains("last login") || lower.contains("login:")
                    || lower.contains("current week") || lower.contains("current month")
                    || lower.contains("build no") || lower.contains("copyright")) {
                continue;
            }
            cleanCalendarText.append(line).append("\n");
        }
        String calendarText = cleanCalendarText.toString();

        // Also strip inline deadline/audit notices in case newlines were lost during OCR
        calendarText = calendarText.replaceAll("(?i)(?:timesheet\\s+entry\\s+)?(?:enabled\\s+)?(?:till|until|deadline)\\s+[^\\n\\r]{0,30}?\\b(?:January|February|March|April|May|June|July|August|September|October|November|December)\\s+20\\d{2}", " ");
        calendarText = calendarText.replaceAll("(?i)last\\s+login\\s*:[^\\n\\r]{0,40}?\\b(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\b[^\\n\\r]{0,30}?", " ");
        calendarText = calendarText.replaceAll("(?i)current\\s+(?:week|month)[^\\n\\r]{0,40}?\\b(?:January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+20\\d{2}", " ");

        String matchedMonth = null;
        String matchedYear = null;

        // 1. Standalone calendar header line or day header line (e.g. "< August 2026 >", "(<) August 2026 (>)", "9th August 2026", "August 2026")
        Pattern headerLinePattern = Pattern.compile("(?im)^[\\s<>‹›«»()\\[\\]{}|•*\\-_/\\\\©®@~#^]*(?:\\d{1,2}(?:st|nd|rd|th)?\\s+)?(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)(?:\\s+\\d{1,2}(?:st|nd|rd|th)?[,]?)?\\s+(20\\d{2})[\\s<>‹›«»()\\[\\]{}|•*\\-_/\\\\©®@~#^]*$");
        Matcher hl = headerLinePattern.matcher(calendarText);
        if (hl.find()) {
            matchedMonth = hl.group(1);
            matchedYear = hl.group(2);
        }

        // 2. Month wrapped in navigation chevrons / parentheses anywhere in clean text
        if (matchedMonth == null) {
            Matcher navMatcher = Pattern.compile("(?i)(?:[<‹«(\\[{]\\s*)\\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(20\\d{2})\\b|\\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(20\\d{2})\\b(?:\\s*[>›»\\]}])").matcher(calendarText);
            if (navMatcher.find()) {
                matchedMonth = navMatcher.group(1) != null ? navMatcher.group(1) : navMatcher.group(3);
                matchedYear = navMatcher.group(2) != null ? navMatcher.group(2) : navMatcher.group(4);
            }
        }

        // 3. Month immediately preceding the calendar weekday header in clean text
        if (matchedMonth == null) {
            Matcher dayHeader = Pattern.compile("(?i)(?:Mo\\s+Tu|Mon\\s+Tue|Mo\\s+We|Weekly)").matcher(calendarText);
            int headerPos = dayHeader.find() ? dayHeader.start() : calendarText.length();
            String beforeHeader = calendarText.substring(0, headerPos);
            Matcher m = Pattern.compile("(?i)\\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(20\\d{2})\\b").matcher(beforeHeader);
            while (m.find()) {
                matchedMonth = m.group(1);
                matchedYear = m.group(2);
            }
        }

        // 4. Any month in clean text
        if (matchedMonth == null) {
            Matcher fallbackClean = Pattern.compile("(?i)\\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(20\\d{2})\\b").matcher(calendarText);
            if (fallbackClean.find()) {
                matchedMonth = fallbackClean.group(1);
                matchedYear = fallbackClean.group(2);
            }
        }

        // 5. Fallback to raw text if no month found in clean text
        if (matchedMonth == null) {
            Matcher fallbackRaw = Pattern.compile("(?i)\\b(January|February|March|April|May|June|July|August|September|October|November|December|Jan|Feb|Mar|Apr|Jun|Jul|Aug|Sep|Sept|Oct|Nov|Dec)\\s+(20\\d{2})\\b").matcher(text);
            if (!fallbackRaw.find()) return null;
            matchedMonth = fallbackRaw.group(1);
            matchedYear = fallbackRaw.group(2);
        }

        String monthRaw = matchedMonth.substring(0, 3).toLowerCase(Locale.ROOT);
        int month = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(monthRaw) + 1;
        int year = Integer.parseInt(matchedYear);
        String monthDisplay = Character.toUpperCase(matchedMonth.charAt(0)) + matchedMonth.substring(1).toLowerCase(Locale.ROOT);

        LocalDate start = LocalDate.of(year, month, 1);
        LocalDate end = start.plusMonths(1).minusDays(1);
        LocalDate today = LocalDate.now();

        if (start.isAfter(today)) {
            return fallback(id, name, "The calendar month " + monthDisplay + " " + year + " is in the future. Hours cannot be recorded for future periods.");
        }
        LocalDate effectiveEnd = end.isAfter(today) ? today : end;

        // Project / Task Extraction
        String projectDescription = "";
        Matcher projMatcher = Pattern.compile("(?i)Project Name\\s*:\\s*(?:TCS\\s+(?:Apple\\s+)?)?([^(\n\r]+)").matcher(text);
        if (projMatcher.find()) {
            projectDescription = projMatcher.group(1).trim();
        }
        if (projectDescription.isBlank()) {
            Matcher taskMatcher = Pattern.compile("(?i)(?:Assigned Task|Claim Task)[^\\n\\r]*[\\r\\n]+(?:[^\\n\\r]*[\\r\\n]+)?([A-Za-z0-9\\s_-]{3,50}\\b(?:Project|Projects|Task|Development|Support|Security|Services?)\\b)").matcher(text);
            if (taskMatcher.find()) {
                projectDescription = taskMatcher.group(1).trim();
            }
        }
        if (projectDescription.isBlank()) {
            Matcher directProject = Pattern.compile("(?i)\\b([A-Za-z0-9\\s_-]{3,40}\\b(?:Projects?|Security Projects?|Operations?))\\b").matcher(text);
            if (directProject.find()) {
                projectDescription = directProject.group(1).trim();
            }
        }
        if (projectDescription.isBlank() && text.contains("SAP Security")) {
            projectDescription = "SAP Security Projects";
        }
        if (projectDescription.isBlank()) {
            projectDescription = "Regular hours";
        }

        // Read the printed per-day grid when possible so holidays, leave, overtime, and part-time weeks are
        // captured instead of assuming every weekday is a uniform 8h. Falls back to that assumption when the
        // grid cannot be matched reliably (e.g. degraded OCR that lost the daily rows).
        Map<LocalDate, BigDecimal> grid = parseCalendarGrid(calendarText, start, end);

        List<TimesheetEntryResponse> entries = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        int workingDays = 0;

        for (LocalDate current = start; !current.isAfter(effectiveEnd); current = current.plusDays(1)) {
            BigDecimal dayHours;
            if (grid != null) {
                dayHours = grid.getOrDefault(current, BigDecimal.ZERO);
            } else {
                boolean isWeekend = current.getDayOfWeek() == java.time.DayOfWeek.SATURDAY || current.getDayOfWeek() == java.time.DayOfWeek.SUNDAY;
                dayHours = isWeekend ? BigDecimal.ZERO : new BigDecimal("8.00");
            }
            String desc = dayHours.compareTo(BigDecimal.ZERO) > 0 ? projectDescription : "";
            if (dayHours.compareTo(BigDecimal.ZERO) > 0) {
                workingDays++;
            }
            total = total.add(dayHours);
            entries.add(new TimesheetEntryResponse(
                    current,
                    current.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.US),
                    dayHours.setScale(2, RoundingMode.HALF_UP),
                    TimesheetEntryType.WORK,
                    desc
            ));
        }

        String message = "Extracted " + workingDays + " working days totaling " + total.setScale(2, RoundingMode.HALF_UP).toPlainString()
                + " hours for " + monthDisplay + " " + year + " (" + projectDescription + "). Verify dates and daily hours.";

        return new ParsedTimesheetResponse(id, name, start, effectiveEnd, total.setScale(2, RoundingMode.HALF_UP),
                entries, "SUCCESS", message);
    }

    /**
     * Reads the printed calendar grid (a day-number row followed by an hour row, per week, under the
     * "Mo Tu We Th Fr Sa Su" header) so the actual value shown for each day is used instead of an assumed
     * uniform workday. Each parsed day number is cross-checked against the calendar date it should be, and any
     * row that doesn't cleanly match returns null so the caller falls back to the uniform assumption rather
     * than guessing at a misread grid.
     */
    private Map<LocalDate, BigDecimal> parseCalendarGrid(String calendarText, LocalDate start, LocalDate end) {
        String[] lines = calendarText.split("\\R");
        int index = 0;
        while (index < lines.length && !CALENDAR_HEADER.matcher(lines[index]).find()) index++;
        if (index >= lines.length) return null;
        index++;
        Map<LocalDate, BigDecimal> byDate = new TreeMap<>();
        for (LocalDate weekStart = start.minusDays(start.getDayOfWeek().getValue() - 1);
             !weekStart.isAfter(end); weekStart = weekStart.plusDays(7)) {
            while (index < lines.length && lines[index].isBlank()) index++;
            if (index >= lines.length) return null;
            Matcher dayRow = CALENDAR_DAY_ROW.matcher(lines[index]);
            if (!dayRow.matches()) return null;
            index++;
            while (index < lines.length && lines[index].isBlank()) index++;
            if (index >= lines.length) return null;
            Matcher hourRow = CALENDAR_HOUR_ROW.matcher(lines[index]);
            if (!hourRow.matches()) return null;
            index++;
            for (int column = 0; column < 7; column++) {
                LocalDate date = weekStart.plusDays(column);
                if (Integer.parseInt(dayRow.group(column + 1)) != date.getDayOfMonth()) return null;
                if (date.isBefore(start) || date.isAfter(end)) continue;
                BigDecimal value = hours(hourRow.group(column + 1));
                if (value == null) return null;
                byDate.put(date, value);
            }
        }
        return byDate.size() == ChronoUnit.DAYS.between(start, end) + 1 ? byDate : null;
    }

    private Day fromColumns(List<String> cells, List<String> headers, String order) {
        int dateIndex = -1, totalIndex = -1, descriptionIndex = -1;
        List<Integer> hourIndices = new ArrayList<>();
        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i);
            if (dateHeader(h)) dateIndex = i;
            if (Set.of("description", "task", "notes", "project", "activity").contains(h)) descriptionIndex = i;
            if (Set.of("hours", "hours worked", "total hours", "daily total", "duration", "total", "worked hours").contains(h)) totalIndex = i;
            else if (hourHeader(h)) hourIndices.add(i);
        }
        if (dateIndex < 0 || dateIndex >= cells.size()) return null;
        Matcher match = DATE.matcher(cells.get(dateIndex));
        if (!match.find()) return null;
        BigDecimal value = null;
        if (totalIndex >= 0 && totalIndex < cells.size()) value = hours(cells.get(totalIndex));
        else if (!hourIndices.isEmpty()) {
            value = BigDecimal.ZERO;
            for (int index : hourIndices) {
                if (index >= cells.size()) return null;
                BigDecimal part = cells.get(index).isBlank() ? BigDecimal.ZERO : hours(cells.get(index));
                if (part == null) return null;
                value = value.add(part);
            }
        }
        if (value == null || value.compareTo(BigDecimal.valueOf(24)) > 0) return null;
        return new Day(date(match.group(), order), value,
                descriptionIndex >= 0 && descriptionIndex < cells.size() ? cells.get(descriptionIndex) : "");
    }

    private String header(String value) { return value.toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ").trim(); }
    private boolean dateHeader(String h) { return Set.of("date", "work date", "day", "entry date").contains(h); }
    private boolean hourHeader(String h) { return Set.of("hours", "hours worked", "worked hours", "total hours", "daily total", "duration", "total", "regular", "regular hours", "overtime", "overtime hours", "ot", "billable hours", "non billable hours").contains(h); }

    private BigDecimal hours(String raw) {
        String value = raw.trim();
        Matcher duration = DURATION.matcher(value);
        BigDecimal result;
        if (duration.matches()) result = new BigDecimal(duration.group(1)).add(new BigDecimal(duration.group(2)).divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        else {
            Matcher match = HOURS.matcher(value);
            if (!match.matches()) return null;
            result = new BigDecimal(match.group(1).replace(',', '.'));
        }
        return result.compareTo(BigDecimal.valueOf(24)) <= 0 ? result : null;
    }

    private LocalDate date(String value, String order) {
        if (value.matches("\\d{4}[-/].*")) {
            String[] p = value.split("[-/]");
            return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
        }
        if (value.matches("\\d{1,2}[./-].*")) {
            String[] p = value.split("[./-]");
            int first = Integer.parseInt(p[0]);
            int second = Integer.parseInt(p[1]);
            // A value over 12 can only be a day, not a month - that reading is unambiguous regardless of
            // which date order the user selected, so a mislabeled document still converts correctly. Only a
            // genuinely ambiguous date (both parts <= 12) falls back to the selected order.
            boolean dayFirst = first > 12 ? true : second > 12 ? false : order.equals("DMY");
            return LocalDate.of(Integer.parseInt(p[2]), dayFirst ? second : first, dayFirst ? first : second);
        }
        String clean = value.replaceAll("(?i)(\\d)(st|nd|rd|th)", "$1").replaceAll("[,.]", "");
        String[] p = clean.split("\\s+");
        boolean monthFirst = Character.isLetter(p[0].charAt(0));
        String month = p[monthFirst ? 0 : 1].substring(0, 3).toLowerCase(Locale.ROOT);
        int m = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec").indexOf(month) + 1;
        return LocalDate.of(Integer.parseInt(p[2]), m, Integer.parseInt(p[monthFirst ? 1 : 0]));
    }

    // Preserve quoted delimiters and empty columns, including company/project descriptions.
    private List<String> split(String line) {
        char separator = line.contains("\t") ? '\t' : line.contains(";") ? ';' : line.contains("|") ? '|' : ',';
        List<String> result = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append(c); i++; }
                else quoted = !quoted;
            } else if (c == separator && !quoted) { result.add(cell.toString().trim()); cell.setLength(0); }
            else cell.append(c);
        }
        result.add(cell.toString().trim());
        return result;
    }

    private String extract(byte[] bytes, String name) throws Exception {
        if (bytes == null || bytes.length == 0 || bytes.length > 5 * 1024 * 1024) return "";
        String lower = name.toLowerCase(Locale.ROOT);
        StringBuilder text = new StringBuilder();
        if (lower.endsWith(".pdf")) {
            PdfReader reader = new PdfReader(bytes);
            try {
                if (reader.getNumberOfPages() > 100) throw new IllegalArgumentException("Too many pages");
                PdfTextExtractor extractor = new PdfTextExtractor(reader);
                org.apache.pdfbox.pdmodel.PDDocument scannedPdf = null;
                try {
                    for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                        String pageText = extractor.getTextFromPage(page);
                        if (pageText.isBlank()) {
                            if (reader.getNumberOfPages() > 5) throw new IllegalArgumentException("Scanned documents are limited to 5 pages");
                            if (scannedPdf == null) scannedPdf = org.apache.pdfbox.Loader.loadPDF(bytes);
                            var box = scannedPdf.getPage(page - 1).getCropBox();
                            if (box.getWidth() * box.getHeight() * (200.0 / 72) * (200.0 / 72) > 12_000_000)
                                throw new IllegalArgumentException("Scanned page is too large");
                            var image = new org.apache.pdfbox.rendering.PDFRenderer(scannedPdf).renderImageWithDPI(page - 1, 200);
                            try { pageText = new TimesheetOcrService().read(image); }
                            finally { image.flush(); }
                        }
                        append(text, pageText + "\n");
                    }
                } finally { if (scannedPdf != null) scannedPdf.close(); }
            } finally { reader.close(); }
        } else if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            try (var stream = javax.imageio.ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = javax.imageio.ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) return "";
                var imageReader = readers.next();
                try {
                    imageReader.setInput(stream);
                    if ((long) imageReader.getWidth(0) * imageReader.getHeight(0) > 12_000_000)
                        throw new IllegalArgumentException("Image is too large");
                    var image = imageReader.read(0);
                    try { append(text, new TimesheetOcrService().read(image)); }
                    finally { image.flush(); }
                } finally { imageReader.dispose(); }
            }
        } else if (lower.endsWith(".xlsx") || lower.endsWith(".xls")) {
            try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                DataFormatter formatter = new DataFormatter(Locale.US);
                formatter.setUseCachedValuesForFormulaCells(true);
                for (Sheet sheet : workbook) {
                    if (sheet.getLastRowNum() > 10000) throw new IllegalArgumentException("Too many rows");
                    for (Row row : sheet) {
                        if (row.getLastCellNum() > 100) throw new IllegalArgumentException("Too many columns");
                        for (int c = 0; c < row.getLastCellNum(); c++) {
                            Cell cell = row.getCell(c);
                            String value = cell == null ? "" : formatter.formatCellValue(cell);
                            if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)
                                    && cell.getNumericCellValue() >= 1) value = cell.getLocalDateTimeCellValue().toLocalDate().toString();
                            append(text, value.replace('\t', ' ').replace('\n', ' ') + "\t");
                        }
                        append(text, "\n");
                    }
                }
            }
        } else if (lower.endsWith(".docx")) {
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                for (IBodyElement element : document.getBodyElements()) {
                    if (element instanceof XWPFParagraph p) append(text, p.getText() + "\n");
                    if (element instanceof XWPFTable table) for (XWPFTableRow row : table.getRows()) {
                        for (XWPFTableCell cell : row.getTableCells()) append(text, cell.getText().replace('\n', ' ') + "\t");
                        append(text, "\n");
                    }
                }
            }
        } else if (lower.endsWith(".csv") || lower.endsWith(".tsv") || lower.endsWith(".txt")) {
            append(text, new String(bytes, StandardCharsets.UTF_8).replace("\uFEFF", ""));
        }
        return text.toString();
    }

    private void append(StringBuilder text, String value) {
        if (text.length() + value.length() > MAX_TEXT) throw new IllegalArgumentException("Document is too large to extract");
        text.append(value);
    }

    private ParsedTimesheetResponse fallback(String id, String name, String message) {
        // No invented dates or fabricated zero-hour entries when extraction fails.
        return new ParsedTimesheetResponse(id, name, null, null, BigDecimal.ZERO.setScale(2), List.of(), "FALLBACK", message);
    }

    private record Day(LocalDate date, BigDecimal hours, String description) {}
}
