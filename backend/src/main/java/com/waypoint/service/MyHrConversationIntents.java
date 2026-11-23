package com.waypoint.service;

import com.waypoint.enums.UserRole;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic recognition of questions Wavi answers from the verified actor and authorized lookups, never from a
 * model: the user's own role (including romanized Telugu-English such as "Nenu HR ah kadha?"), who reports to them,
 * timesheet upload troubleshooting, the status of their own timesheets for a week, month or day, an overview of
 * what is waiting for them, and requests to open a page. Pure and side-effect free; every method takes a message and
 * returns null when it does not apply.
 *
 * <p>A role named in the message is only what the user asked about; the answer always comes from the server-side role.
 */
final class MyHrConversationIntents {

    private MyHrConversationIntents() {
    }

    // --- Own role -------------------------------------------------------------------------------------------

    /** The role a role question asks about; ANY when it asks what the role is. */
    enum AskedRole { HR, COMPANY_ADMIN, EMPLOYEE, MANAGER, ANY }

    private static final String ROLE_WORD = "(hr|human resources|admin|administrator|company admin|employee|manager)";
    private static final Pattern ENGLISH_ROLE_CHECK = Pattern.compile(
            "^(?:am i|i am|i'm)(?: an?)? " + ROLE_WORD
                    + "(?: user| person| staff| account| member)?(?: or not| right| correct)?$");
    /**
     * "My job title says HR Business Partner, but am I an HR user in the app?": a role check at the end of a longer
     * question. Only a question ending in the check counts, so "I'm an employee and ..." is not one.
     */
    private static final Pattern TRAILING_ROLE_CHECK = Pattern.compile(
            "(?:^|,|\\bbut|\\bso|\\band|\\bthen)\\s*(?:am i|do i have)(?: an?)? " + ROLE_WORD
                    + "(?: user| account| role| access| rights| permissions?)?(?: (?:in|on) (?:the app|waypoint|wavi|the system))?"
                    + "(?: or not| right| correct)?$");
    /** "Is my account an admin account?", "Is mine an HR login?": the same check, asked about the account. */
    private static final Pattern ACCOUNT_ROLE_CHECK = Pattern.compile(
            "^(?:is|am) (?:my account|my user|my login|mine|i) (?:an? )?" + ROLE_WORD
                    + "(?: account| user| login| role)?(?: account)?(?: or not| right)?$");
    private static final Pattern ENGLISH_ROLE_ACCESS = Pattern.compile(
            "^do i have (?:an? )?" + ROLE_WORD + " (?:access|rights|role|permissions?)$");
    private static final Pattern ENGLISH_ROLE_WHAT = Pattern.compile(
            "^(?:what(?: is|'s) my (?:account |user |waypoint |login |app )?role(?: in (?:waypoint|the app))?"
                    + "|what role (?:do i have|am i)(?: in (?:waypoint|the app))?"
                    + "|which role (?:do i have|am i|is mine)|what kind of (?:user|account) am i"
                    + "|what type of (?:user|account) am i|my role|my account role)$");
    /** Romanized Telugu: "nenu HR ah kadha?" (I'm HR, right?), "nenu employee na?", "naa role enti?". */
    private static final Pattern TELUGU_ROLE_CHECK = Pattern.compile(
            "^(?:nenu|nen|naenu) " + ROLE_WORD
                    + "(?: ?(?:ah?|aa|na|naa|ani|eh))?(?: (?:kadha|kada|kadaa|kaadha|kada na|ledha|leda|kadhu))?$");
    private static final Pattern TELUGU_ROLE_WHAT = Pattern.compile(
            "^(?:naa|na|naaku|naku) role (?:enti|emiti|yenti|emti|entha|enti chepu|cheppu)$");

    static AskedRole roleQuestion(String normalized) {
        if (normalized == null) return null;
        String text = normalized.replace('’', '\'');
        if (ENGLISH_ROLE_WHAT.matcher(text).matches() || TELUGU_ROLE_WHAT.matcher(text).matches()) {
            return AskedRole.ANY;
        }
        for (Pattern pattern : new Pattern[]{ENGLISH_ROLE_CHECK, ACCOUNT_ROLE_CHECK, ENGLISH_ROLE_ACCESS,
                TELUGU_ROLE_CHECK}) {
            Matcher matcher = pattern.matcher(text);
            if (matcher.matches()) {
                return askedRole(matcher.group(1));
            }
        }
        Matcher trailing = TRAILING_ROLE_CHECK.matcher(text);
        return trailing.find() ? askedRole(trailing.group(1)) : null;
    }

    private static AskedRole askedRole(String word) {
        return switch (word) {
            case "hr", "human resources" -> AskedRole.HR;
            case "admin", "administrator", "company admin" -> AskedRole.COMPANY_ADMIN;
            case "employee" -> AskedRole.EMPLOYEE;
            default -> AskedRole.MANAGER;
        };
    }

    static String roleReply(AskedRole asked, UserRole actual) {
        String name = roleName(actual);
        String fact = "Your Waypoint account role is " + name + ".";
        return switch (asked) {
            case ANY -> fact;
            case MANAGER -> fact + (actual == UserRole.EMPLOYEE
                    ? " Employee accounts don't manage anyone in Waypoint."
                    : " HR and Company Admin accounts can be reporting managers in Waypoint.");
            default -> (matches(asked, actual) ? "Yes. " : "No. ") + fact;
        };
    }

    /**
     * "What is my role?" can mean the account role or the job title, so both are given: the account role decides
     * what the user can do in Waypoint, the job title is only their position. Null title: the role alone.
     */
    static String roleAndTitleReply(UserRole actual, String jobTitle) {
        String fact = roleReply(AskedRole.ANY, actual);
        return jobTitle == null || jobTitle.isBlank() ? fact
                : fact + " Your job title is " + jobTitle.strip() + ". Your account role, not your job title, decides "
                + "what you can see and do in Waypoint.";
    }

    private static boolean matches(AskedRole asked, UserRole actual) {
        return (asked == AskedRole.HR && actual == UserRole.HR)
                || (asked == AskedRole.COMPANY_ADMIN && actual == UserRole.COMPANY_ADMIN)
                || (asked == AskedRole.EMPLOYEE && actual == UserRole.EMPLOYEE);
    }

    static String roleName(UserRole role) {
        if (role == null) return "unknown";
        return switch (role) {
            case HR -> "HR";
            case COMPANY_ADMIN -> "Company Admin";
            case EMPLOYEE -> "Employee";
            default -> role.name().charAt(0) + role.name().substring(1).toLowerCase(Locale.ROOT).replace('_', ' ');
        };
    }

    // --- Direct reports -------------------------------------------------------------------------------------

    private static final String TEAM_NOUN = "(?:direct reports?|direct reportees?|reportees?|reports|team members?|team"
            + "|subordinates?|people under me)";
    private static final Pattern DIRECT_REPORTS = Pattern.compile(
            "^(?:who(?: all)? (?:reports|report|is reporting|are reporting) (?:directly )?to me|whom do i manage"
                    + "|who do i manage|who (?:all )?(?:are|is) (?:on |in )?my " + TEAM_NOUN
                    + "|who(?: all)? (?:are|is) (?:in|on|part of) my team"
                    + "|(?:which|what) (?:employees|people|members|staff) (?:report|are reporting) (?:directly )?to me(?: directly)?"
                    + "|(?:list|show)(?: me)?(?: all)? my " + TEAM_NOUN + "|my " + TEAM_NOUN
                    + "|how many (?:people|employees|members|direct reports|reportees) (?:report to me|do i have"
                    + "|are in my team|are on my team))$");

    static boolean isDirectReportsQuestion(String normalized) {
        return normalized != null && DIRECT_REPORTS.matcher(normalized).matches();
    }

    // --- Timesheet upload troubleshooting -------------------------------------------------------------------

    private static final Pattern TIMESHEET_WORD = Pattern.compile("\\b(?:time ?sheets?|time ?cards?)\\b");
    private static final Pattern UPLOAD_WORD = Pattern.compile(
            "\\b(?:upload(?:ed|ing|s)?|attach(?:ed|ing|ment)?|import(?:ed|ing)?|file|files|document|pdf|excel|xlsx|xls"
                    + "|csv|scan(?:ned)?|photo|image|picture)\\b");
    private static final Pattern TROUBLE_WORD = Pattern.compile(
            "\\b(?:fail\\w*|error\\w*|crash\\w*|broken|break(?:s|ing)?|glitch\\w*|bug\\w*|keeps|not working|doesn't work|does not work|won't|wont|can't|cannot"
                    + "|can not|unable|problem|issue|issues|why|stuck|rejected|format|formats|types?|size|limit|limits"
                    + "|supported|accept(?:ed|s)?|allowed|maximum|max|how (?:do|can|to) i|too (?:big|large|long|heavy)"
                    + "|big|large|huge|wrong|incorrect|not (?:read|recogni[sz]ed|detected|picked up|showing)|misread"
                    + "|unreadable|blank|empty|missing|ocr)\\b");

    static boolean isTimesheetUploadHelp(String normalized) {
        return normalized != null && TIMESHEET_WORD.matcher(normalized).find()
                && UPLOAD_WORD.matcher(normalized).find() && TROUBLE_WORD.matcher(normalized).find();
    }

    /**
     * From the upload and import code: MyHrDocumentService rejects supporting documents over 5 MB, and
     * TimesheetDocumentParserService reads .pdf, .png, .jpg/.jpeg, .xlsx/.xls, .docx and .csv/.tsv/.txt; scanned
     * pages need OCR. A failed extraction keeps the stored document and lets hours be entered manually.
     */
    static final String TIMESHEET_UPLOAD_HELP = "Timesheet documents can be PDF, Word (.docx), Excel (.xlsx or .xls), "
            + "CSV, TSV, plain text (.txt), PNG or JPG/JPEG files of up to 5 MB each. If an upload fails, check that "
            + "the file is one of those types and under 5 MB. For a scanned PDF or a photo, make sure the dates and "
            + "hours are clear enough to read, and choose the date order (MM/DD/YYYY or DD/MM/YYYY) that matches your "
            + "document. If Waypoint can't read the dates or hours, the uploaded document is kept and you can enter or "
            + "correct the hours yourself before you submit. If it still fails, contact HR.";

    // --- Own timesheets for a week, month or day ------------------------------------------------------------

    /** The dates a timesheet question asks about, and how to name them in the reply. */
    record TimesheetRange(LocalDate from, LocalDate to, String label, boolean includesToday, Unit unit) {

        /** SPAN: explicit start and end dates ("between Oct 5 and Oct 18"), not a calendar day, week or month. */
        enum Unit { DAY, WEEK, MONTH, SPAN }

        boolean singleDay() {
            return from.equals(to);
        }

        /** The same kind of period just before this one ("the week before"). */
        TimesheetRange previous() {
            return switch (unit) {
                case DAY -> day(from.minusDays(1), null, null);
                case WEEK -> week(from.minusWeeks(1), null, null);
                case MONTH -> month(YearMonth.from(from).minusMonths(1), null);
                case SPAN -> span(from.minusDays(ChronoUnit.DAYS.between(from, to) + 1), from.minusDays(1), null);
            };
        }
    }

    /** The longest explicit date span answered; the timesheet lookups accept no more. */
    static final int MAX_SPAN_DAYS = 92;
    static final String SPAN_LABEL = "the dates you named";

    /** A span rebuilt from two stored dates; null when they are not a valid span. */
    static TimesheetRange spanOf(LocalDate from, LocalDate to, LocalDate today) {
        if (from == null || to == null || to.isBefore(from) || ChronoUnit.DAYS.between(from, to) >= MAX_SPAN_DAYS) return null;
        return from.equals(to) ? day(from, null, today) : span(from, to, today);
    }

    private static TimesheetRange span(LocalDate from, LocalDate to, LocalDate today) {
        boolean current = today != null && !today.isBefore(from) && !today.isAfter(to);
        return new TimesheetRange(from, to, SPAN_LABEL, current, TimesheetRange.Unit.SPAN);
    }

    /**
     * Two explicit dates joined as a span ("between Oct 5 and Oct 18", "from 2026-10-05 to 2026-10-18", "Oct 5 to
     * 18"): every day from the first to the second, inclusive. Read from the message's tokens by {@link WaviDateSpan}
     * (dates are grammar, not accepted wordings); null unless exactly such a pair is named.
     */
    private static TimesheetRange explicitSpan(String text, LocalDate today) {
        LocalDate[] dates = WaviDateSpan.find(text, today, MAX_SPAN_DAYS);
        if (dates == null) return null;
        return dates[0].equals(dates[1]) ? day(dates[0], null, today) : span(dates[0], dates[1], today);
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d, yyyy", Locale.US);

    private static TimesheetRange week(LocalDate anyDay, String label, LocalDate today) {
        LocalDate monday = anyDay.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate sunday = monday.plusDays(6);
        boolean current = today != null && !today.isBefore(monday) && !today.isAfter(sunday);
        return new TimesheetRange(monday, sunday, label == null ? "the week of " + monday.format(DAY) : label, current,
                TimesheetRange.Unit.WEEK);
    }

    private static TimesheetRange month(YearMonth month, LocalDate today) {
        LocalDate first = month.atDay(1);
        LocalDate last = month.atEndOfMonth();
        boolean current = today != null && YearMonth.from(today).equals(month);
        return new TimesheetRange(first, last, month.getMonth().getDisplayName(TextStyle.FULL, Locale.US) + " "
                + month.getYear(), current, TimesheetRange.Unit.MONTH);
    }

    private static TimesheetRange day(LocalDate date, String label, LocalDate today) {
        return new TimesheetRange(date, date, label == null ? date.format(DAY) : label + " (" + date.format(DAY) + ")",
                date.equals(today), TimesheetRange.Unit.DAY);
    }

    private static final Map<String, Month> MONTHS = Map.ofEntries(
            Map.entry("january", Month.JANUARY), Map.entry("jan", Month.JANUARY),
            Map.entry("february", Month.FEBRUARY), Map.entry("feb", Month.FEBRUARY),
            Map.entry("march", Month.MARCH), Map.entry("mar", Month.MARCH),
            Map.entry("april", Month.APRIL), Map.entry("apr", Month.APRIL), Map.entry("may", Month.MAY),
            Map.entry("june", Month.JUNE), Map.entry("jun", Month.JUNE), Map.entry("july", Month.JULY),
            Map.entry("jul", Month.JULY), Map.entry("august", Month.AUGUST), Map.entry("aug", Month.AUGUST),
            Map.entry("september", Month.SEPTEMBER), Map.entry("sept", Month.SEPTEMBER), Map.entry("sep", Month.SEPTEMBER),
            Map.entry("october", Month.OCTOBER), Map.entry("oct", Month.OCTOBER),
            Map.entry("november", Month.NOVEMBER), Map.entry("nov", Month.NOVEMBER),
            Map.entry("december", Month.DECEMBER), Map.entry("dec", Month.DECEMBER));
    private static final String MONTH_NAME = "(january|february|march|april|may|june|july|august|september|october"
            + "|november|december|jan|feb|mar|apr|jun|jul|aug|sept|sep|oct|nov|dec)";
    private static final Pattern ISO_DATE = Pattern.compile("\\b(20\\d\\d)-(\\d{1,2})-(\\d{1,2})\\b");
    /** "October 14", "Oct 14th, 2026", "14 October", "the 14th of October". */
    private static final Pattern MONTH_DAY = Pattern.compile(
            "\\b" + MONTH_NAME + "\\.? (\\d{1,2})(?:st|nd|rd|th)?(?:,? (20\\d\\d))?\\b");
    private static final Pattern DAY_MONTH = Pattern.compile(
            "\\b(\\d{1,2})(?:st|nd|rd|th)? (?:of )?" + MONTH_NAME + "\\b(?:,? (20\\d\\d))?");
    /** A month on its own. "May" is also a verb, so it counts only after in/for/of/during or before a year. */
    private static final Pattern MONTH_ONLY = Pattern.compile(
            "\\b(?:(in|for|of|during|from) )?(?:the month of )?" + MONTH_NAME + "\\b(?:,? (20\\d\\d))?");
    private static final Pattern WEEKS_AGO = Pattern.compile(
            "\\b(\\d|one|two|three|four|five|six)\\s+weeks?\\s+(?:ago|back|before)\\b");
    private static final Pattern WEEK_OF = Pattern.compile("\\b(?:the )?week (?:of|starting|beginning|from)\\b");
    private static final Pattern RELATIVE = Pattern.compile(
            "\\b(this|current|last|previous|past|next|coming) (week|month)(?:'s)?\\b|\\b(weekly)\\b"
                    + "|\\b(yesterday|today)(?:'s)?\\b");
    /** Someone else's timesheets, the team's, or a queue count: never the user's own records. */
    private static final Pattern OTHER_SUBJECT = Pattern.compile(
            "\\b(?:team|teams|employees?|staff|direct reports?|reportees?|everyone|everybody|anyone|someone|people"
                    + "|his|her|their|how many|count|number of|organi[sz]ation)\\b"
                    + "|\\b(?!(?:week|month|year|today|yesterday|tomorrow|day|october|november|december|january|february"
                    + "|march|april|may|june|july|august|september)'s)[a-z][a-z0-9.@_-]*'s (?:time ?sheets?"
                    + "|time ?cards?|hours)|\\b[a-z]{2,}[-_]?\\d{2,}\\b");
    private static final Pattern HOURS_WORD = Pattern.compile("\\b(?:time ?sheets?|time ?cards?|hours)\\b");
    private static final Pattern HOW_TO = Pattern.compile("^(?:how (?:do|can|should|to|would)|where (?:do|can) i)\\b");
    /** Deadlines and rules about a period are company-policy questions, not record lookups. */
    private static final Pattern POLICY_WORDS = Pattern.compile(
            "\\b(?:deadline|due|cut-?off|policy|policies|rules?|allowed|supposed to|must|late)\\b");
    private static final Pattern FOLLOW_UP_LEAD = Pattern.compile(
            "^(?:(?:and|but|ok|okay|so)\\s+)?(?:(?:what|how) about|what of|same for|same question for|also|and|now)?"
                    + "\\s*(?:for|in|on|during|from)?\\s*(?:the\\s+)?");
    private static final Pattern FOLLOW_UP_PREVIOUS = Pattern.compile(
            "^(?:(?:and|but|ok|okay|so)\\s+)?(?:(?:what|how) about\\s+)?(?:for\\s+)?(?:the\\s+)?"
                    + "(?:(?:week|month|day) before(?: that)?|(?:one|week|month) before that|previous one|one before)"
                    + "(?:\\s+(?:that|too|one))?$");
    private static final Set<String> FOLLOW_UP_FILLER = Set.of("one", "too", "please", "then", "as well", "timesheet",
            "timesheets", "status", "one too");

    /**
     * The dates a question about the user's own timesheets names: this, last or next week or month, "two weeks ago",
     * a named month ("October", "September 2026"), a date ("October 14", "2026-10-20", "yesterday") or "the week of"
     * a date. Null for how-to, policy, upload or someone else's timesheets, or when no period is named.
     */
    static TimesheetRange timesheetRange(String normalized, LocalDate today) {
        if (normalized == null || HOW_TO.matcher(normalized).find() || !HOURS_WORD.matcher(normalized).find()
                || isTimesheetUploadHelp(normalized) || POLICY_WORDS.matcher(normalized).find()) {
            return null;
        }
        // The user's own ("was last week's timesheet approved?" needs no "my"), unless the question names someone
        // else, the team or a count.
        if (OTHER_SUBJECT.matcher(normalized).find()) return null;
        return range(normalized, today);
    }

    /**
     * The day, week or month a message names, whoever it is about: the same period reading as
     * {@link #timesheetRange}, without its own-record checks. For a question whose subject was already resolved
     * elsewhere (a reviewer's question about a person in scope). Null for how-to and policy questions.
     */
    static TimesheetRange periodIn(String normalized, LocalDate today) {
        if (normalized == null || HOW_TO.matcher(normalized).find() || POLICY_WORDS.matcher(normalized).find()) return null;
        return range(normalized, today);
    }

    /** "What about last week?", "and October?", "the week before?": a period on its own, after a timesheet question. */
    static TimesheetRange followUpRange(String normalized, LocalDate today, TimesheetRange previous) {
        if (normalized == null) return null;
        if (previous != null && FOLLOW_UP_PREVIOUS.matcher(normalized).matches()) return previous.previous();
        String rest = FOLLOW_UP_LEAD.matcher(normalized).replaceFirst("");
        TimesheetRange range = range(rest, today);
        if (range == null) return null;
        // Nothing but the period may be left: "and October?" is a follow-up, "and what is the October policy" is not.
        String leftover = rest.replaceAll(RELATIVE.pattern() + "|" + MONTH_DAY.pattern() + "|" + DAY_MONTH.pattern()
                        + "|" + ISO_DATE.pattern() + "|" + WEEKS_AGO.pattern() + "|" + MONTH_ONLY.pattern(), " ")
                .replaceAll("\\b(?:week|of|the|for|in|on)\\b", " ").replaceAll("[^a-z ]", " ").replaceAll("\\s+", " ")
                .strip();
        return leftover.isEmpty() || FOLLOW_UP_FILLER.contains(leftover) ? range : null;
    }

    private static TimesheetRange range(String text, LocalDate today) {
        boolean weekOf = WEEK_OF.matcher(text).find();
        // Two dates joined as a span are one period, never only its first day.
        TimesheetRange span = weekOf ? null : explicitSpan(text, today);
        if (span != null) return span;
        // Resolve the explicit token grammar before a bare month. For example, "Oct 23,2026" is one day,
        // never all of October merely because punctuation omitted a space after the comma.
        LocalDate date = WaviDateSpan.single(text, today);
        if (date == null) date = specificDate(text, today);
        if (date != null) return weekOf ? week(date, null, today) : day(date, null, today);
        Matcher ago = WEEKS_AGO.matcher(text);
        if (ago.find()) {
            int weeks = number(ago.group(1));
            return week(today.minusWeeks(weeks), weeks + (weeks == 1 ? " week ago" : " weeks ago"), today);
        }
        Matcher relative = RELATIVE.matcher(text);
        if (relative.find()) {
            if (relative.group(3) != null) return week(today, "this week", today);
            if (relative.group(4) != null) {
                return relative.group(4).equals("today") ? day(today, "today", today)
                        : day(today.minusDays(1), "yesterday", today);
            }
            String which = relative.group(1);
            int offset = which.equals("this") || which.equals("current") ? 0
                    : which.equals("next") || which.equals("coming") ? 1 : -1;
            String label = (offset == 0 ? "this " : offset > 0 ? "next " : "last ") + relative.group(2);
            return relative.group(2).equals("week") ? week(today.plusWeeks(offset), label, today)
                    : relabel(month(YearMonth.from(today).plusMonths(offset), today), label);
        }
        Matcher month = MONTH_ONLY.matcher(text);
        while (month.find()) {
            String name = month.group(2);
            // "may" is only a month after a preposition or before a year; "mar" and "dec" are too short to guess.
            if (name.equals("may") && month.group(1) == null && month.group(3) == null) continue;
            Month value = MONTHS.get(name);
            int year = month.group(3) != null ? Integer.parseInt(month.group(3))
                    : value.getValue() > today.getMonthValue() ? today.getYear() - 1 : today.getYear();
            return month(YearMonth.of(year, value), today);
        }
        return null;
    }

    private static TimesheetRange relabel(TimesheetRange range, String label) {
        return new TimesheetRange(range.from(), range.to(), label, range.includesToday(), range.unit());
    }

    /** An explicit date; without a year, the most recent such date that is not in the future. */
    private static LocalDate specificDate(String text, LocalDate today) {
        try {
            Matcher iso = ISO_DATE.matcher(text);
            if (iso.find()) {
                return LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3)));
            }
            Matcher monthDay = MONTH_DAY.matcher(text);
            if (monthDay.find()) return datedOrRecent(MONTHS.get(monthDay.group(1)), monthDay.group(2), monthDay.group(3), today);
            Matcher dayMonth = DAY_MONTH.matcher(text);
            if (dayMonth.find()) return datedOrRecent(MONTHS.get(dayMonth.group(2)), dayMonth.group(1), dayMonth.group(3), today);
        } catch (DateTimeException | NumberFormatException invalid) {
            return null;
        }
        return null;
    }

    private static LocalDate datedOrRecent(Month month, String day, String year, LocalDate today) {
        int dayOfMonth = Integer.parseInt(day);
        if (year != null) return LocalDate.of(Integer.parseInt(year), month, dayOfMonth);
        LocalDate thisYear = LocalDate.of(today.getYear(), month, dayOfMonth);
        return thisYear.isAfter(today) ? thisYear.minusYears(1) : thisYear;
    }

    private static int number(String word) {
        return switch (word) {
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            default -> Integer.parseInt(word);
        };
    }

    // --- What is waiting for the user ------------------------------------------------------------------------

    /**
     * "Do I have any pending approvals?", "What are my pending tasks?", "Anything waiting for me?": an overview of
     * everything open for the user, not one queue. Specific subjects ("pending profile change requests") keep their
     * own routes, so only generic nouns are accepted here.
     */
    private static final String WHEN_OR_WHERE = "(?: (?:today|now|right now|currently|at the moment|this week"
            + "|on my (?:side|end|plate)|in waypoint))?";
    private static final Pattern PENDING_OVERVIEW = Pattern.compile(
            "^(?:(?:do i have|have i got|are there|is there|show(?: me)?|list|what are|what is|what's|check|give me|any)"
                    + " )?(?:any |my |all |all my |the )?(?:pending|open|outstanding|waiting)(?: approvals?| tasks?"
                    + "| items?| actions?| requests?| work| things| stuff| reviews?)?(?: for me| on me| to do"
                    + "| to approve| to review| (?:waiting|awaiting) (?:for )?me)?" + WHEN_OR_WHERE + "$"
                    + "|^(?:is |are )?(?:there )?(?:any(?:thing)?|something) (?:pending|waiting|awaiting|open|outstanding)"
                    + "(?: (?:for|on|from|with) (?:me|my (?:side|end|plate|approval|review|action|attention|part)))?"
                    + WHEN_OR_WHERE + "$"
                    + "|^what (?:do i need|do i have) to (?:approve|review|do)" + WHEN_OR_WHERE + "$"
                    + "|^(?:is there |do i have )?any(?:thing)? (?:that )?i (?:need|have) to (?:approve|review|sign off"
                    + "|action)(?: on)?" + WHEN_OR_WHERE + "$"
                    + "|^what (?:needs|requires|is waiting for|awaits) my (?:approval|review|action|attention)"
                    + WHEN_OR_WHERE + "$"
                    + "|^(?:my )?(?:approvals|to-?do(?: list)?|tasks)(?: list)?$|^my pending (?:approvals|tasks|items)$");

    static boolean isPendingOverviewQuestion(String normalized) {
        if (normalized == null) return false;
        if (PENDING_OVERVIEW.matcher(normalized).matches()) return true;
        // "Do I have anything pending?": the same question in a "do I have" frame. The frame is removed in code, not
        // added as another accepted wording to the frozen pattern.
        String frame = "do i have ";
        return normalized.startsWith(frame) && PENDING_OVERVIEW.matcher(normalized.substring(frame.length())).matches();
    }

    // --- What a payroll term means ------------------------------------------------------------------------

    /** Terms Wavi defines from what Waypoint actually does; the longest name is listed first. */
    /** TIMESHEET_PERIOD is reached through the router only; the anchored definition form covers the payroll terms. */
    enum Term { PAYROLL_READINESS, PAYROLL, TIMESHEET_PERIOD }

    /**
     * "Payroll means?", "What does payroll readiness mean?", "Explain payroll readiness": the whole message asks
     * what a term means. Read after Waypoint's vocabulary is applied, so "pay roll" is "payroll".
     */
    private static final Pattern DEFINITION = Pattern.compile(
            "^(?:(?:can you |could you |please )?(?:explain|define|describe)(?: what)?(?: is)? |what (?:is|are|does) "
                    + "|what's |meaning of |definition of )?(?:the |a |an )?(payroll readiness|payroll)"
                    + "(?: (?:mean|means|meaning|stands? for|is|page|feature|in waypoint))*$");

    static Term definitionTerm(String vocabulary) {
        if (vocabulary == null) return null;
        Matcher matcher = DEFINITION.matcher(vocabulary.strip());
        if (!matcher.matches()) return null;
        return matcher.group(1).equals("payroll readiness") ? Term.PAYROLL_READINESS : Term.PAYROLL;
    }

    /**
     * From the code: PayrollReadinessService serves HR and Company Admin only, judges each employee's timesheets for
     * a payroll month (READY when every dated entry in the month is approved; AWAITING_APPROVAL, PARTIALLY_APPROVED,
     * MISSING_TIMESHEET, INCOMPLETE_TIMESHEET, CORRECTION_REQUIRED otherwise) and sends timesheet reminders.
     */
    static String definitionReply(Term term, UserRole role) {
        boolean reviewer = role == UserRole.HR || role == UserRole.COMPANY_ADMIN;
        return switch (term) {
            case PAYROLL -> "Payroll is the process of paying employees for a pay period: working out what each "
                    + "person is owed, applying taxes and deductions, and paying them. In Waypoint, payroll relies on "
                    + "approved timesheets, which is why submitting your timesheet on time and correcting a rejected one "
                    + "matter. I can't see pay amounts or pay dates; check your pay documents or ask HR for those.";
            case PAYROLL_READINESS -> reviewer
                    ? "Payroll Readiness is the Waypoint page where HR and Company Admins check, for a payroll month, "
                    + "whether each employee's timesheets are ready for payroll. An employee is Ready when every dated "
                    + "entry in that month is approved; otherwise they show as Awaiting approval (submitted, not yet "
                    + "reviewed), Partially approved, Missing timesheet, Incomplete timesheet (a draft, or no entries in "
                    + "the month) or Correction required (a rejected entry). From the page you can send an employee a "
                    + "reminder to complete their timesheets."
                    : "Payroll Readiness is a Waypoint page for HR and Company Admin accounts. It checks, for each "
                    + "payroll month, whether employees' timesheets cover the month and are approved, so payroll can be "
                    + "run. You don't have that page, but you help by submitting your timesheets on time and correcting "
                    + "any that are rejected.";
            // From TimesheetService: a period is the date range chosen when the timesheet is entered (at most 31 days);
            // a later submission covering already-recorded days becomes its own record.
            case TIMESHEET_PERIOD -> "A Waypoint timesheet covers the date range chosen when it is entered, up to 31 "
                    + "days, so timesheets don't have a fixed number of days: one may cover a week, part of a week or a "
                    + "longer period, and days without hours simply have none recorded. If you submit hours for days that "
                    + "an earlier timesheet already covers, the new submission is kept as its own timesheet, so two of "
                    + "your timesheets can overlap. Open Timesheets to see each one's exact dates.";
        };
    }

    // --- Opening a page -------------------------------------------------------------------------------------

    private static final Set<UserRole> ALL_ROLES = EnumSet.of(UserRole.EMPLOYEE, UserRole.HR, UserRole.COMPANY_ADMIN);
    private static final Set<UserRole> REVIEWERS = EnumSet.of(UserRole.HR, UserRole.COMPANY_ADMIN);
    private static final Set<UserRole> ADMIN_ONLY = EnumSet.of(UserRole.COMPANY_ADMIN);

    /**
     * MyHR pages, with the label the chat UI shows on the matching button, the roles that have them (from the
     * routes in the frontend's AppRouter.jsx) and the action key the frontend turns into that role's path.
     */
    enum Page {
        DOCUMENT_REQUESTS("Document Requests", ALL_ROLES), TIMESHEETS("Timesheets", ALL_ROLES),
        MY_PROFILE("My Profile", ALL_ROLES), PROFILE_CHANGE_REQUESTS("Profile Change Requests", ALL_ROLES),
        DASHBOARD("Dashboard", ALL_ROLES), EMPLOYEES("Employees", REVIEWERS), REPORTS("Reports", REVIEWERS),
        KNOWLEDGE_BASE("Knowledge Base", REVIEWERS), PAYROLL_READINESS("Payroll Readiness", REVIEWERS),
        DOCUMENT_APPROVALS("Document Approvals", ADMIN_ONLY), AUDIT_LOG("Audit Log", ADMIN_ONLY),
        ORGANIZATION_SETTINGS("Organization Settings", ADMIN_ONLY);

        final String label;
        final Set<UserRole> roles;

        Page(String label, Set<UserRole> roles) {
            this.label = label;
            this.roles = roles;
        }

        boolean availableTo(UserRole role) {
            return roles.contains(role);
        }
    }

    private static final String PAGE_TARGET = "(document requests?|doc requests?|request documents|timesheets?"
            + "|time sheets?|my timesheets?|profile change requests?|profile changes?|my profile|profile|dashboard"
            + "|home ?page|home|employees? (?:list|directory|management)|employees?|reports?|knowledge ?base"
            + "|payroll readiness|payroll|document approvals?|offer letter approvals?|audit logs?|audit trail|audit"
            + "|organi[sz]ation settings|organi[sz]ation|org settings|company settings)";
    private static final Pattern NAVIGATION = Pattern.compile(
            "^(?:please |pls |can you |could you |can you please |could you please )?(?:navigate|go|take me|bring me"
                    + "|open|show me|redirect me|move me|launch)(?: (?:me )?to)?(?: the| my)? " + PAGE_TARGET
                    + "(?: page| section| tab| screen| module)?(?: please)?$");

    /** Words of a navigation command whose small misspellings ("nagivate", "documnet") are recognised. */
    private static final List<String> NAVIGATION_WORDS = List.of("navigate", "document", "documents", "employee",
            "request", "requests", "timesheet", "timesheets", "profile", "change", "changes", "dashboard",
            "employees", "reports", "knowledge", "payroll", "readiness", "approvals", "organization");

    static Page navigationTarget(String normalized) {
        if (normalized == null) return null;
        Matcher matcher = NAVIGATION.matcher(correctNavigationWords(normalized));
        if (!matcher.matches()) return null;
        String target = matcher.group(1);
        // Approvals first: "document approvals" also starts with "doc".
        if (target.startsWith("document approval") || target.startsWith("offer letter")) return Page.DOCUMENT_APPROVALS;
        if (target.startsWith("doc") || target.equals("request documents")) return Page.DOCUMENT_REQUESTS;
        if (target.startsWith("profile change")) return Page.PROFILE_CHANGE_REQUESTS;
        if (target.contains("profile")) return Page.MY_PROFILE;
        if (target.startsWith("dashboard") || target.startsWith("home")) return Page.DASHBOARD;
        if (target.startsWith("employee")) return Page.EMPLOYEES;
        if (target.startsWith("report")) return Page.REPORTS;
        if (target.startsWith("knowledge")) return Page.KNOWLEDGE_BASE;
        if (target.startsWith("payroll")) return Page.PAYROLL_READINESS;
        if (target.startsWith("audit")) return Page.AUDIT_LOG;
        if (target.startsWith("org") || target.startsWith("company")) return Page.ORGANIZATION_SETTINGS;
        return Page.TIMESHEETS;
    }

    /**
     * Replaces each word that is a small misspelling of a navigation word with that word: at most one edit for words
     * of up to six letters, two for longer ones (a swap of neighbouring letters counts as one edit). Words shorter
     * than four letters are never changed. Used only to read a navigation command; the result must still match the
     * whole NAVIGATION pattern, so ordinary sentences are unaffected.
     */
    static String correctNavigationWords(String normalized) {
        String[] words = normalized.split(" ");
        for (int index = 0; index < words.length; index++) {
            String word = words[index];
            if (word.length() < 4 || NAVIGATION_WORDS.contains(word)) continue;
            String best = null;
            int bestDistance = Integer.MAX_VALUE;
            for (String candidate : NAVIGATION_WORDS) {
                int allowed = candidate.length() <= 6 ? 1 : 2;
                int distance = editDistance(word, candidate);
                if (distance <= allowed && distance < bestDistance) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
            if (best != null) words[index] = best;
        }
        return String.join(" ", words);
    }

    /** Optimal string alignment distance: insertions, deletions, substitutions and adjacent swaps. */
    static int editDistance(String a, String b) {
        int[][] d = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) d[i][0] = i;
        for (int j = 0; j <= b.length(); j++) d[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
                }
            }
        }
        return d[a.length()][b.length()];
    }

    static String navigationReply(Page page) {
        return "Use the " + page.label + " button below to open the " + page.label + " page.";
    }

    /** A page the verified role does not have: said plainly, with no button. */
    static String unavailablePageReply(Page page, UserRole role) {
        return "The " + page.label + " page isn't available to " + roleName(role) + " accounts in Waypoint.";
    }
}
