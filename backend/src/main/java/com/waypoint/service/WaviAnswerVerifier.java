package com.waypoint.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks a model-written answer against TYPED evidence instead of scanning its prose for patterns of English
 * capitalisation. The evidence is what the model was allowed to see (authorized tool results, the user's own message,
 * the verified exchange before it) plus counts the server derives from those results (list rows, distinct people).
 *
 * <p>What is verified, independent of the answer's language:
 * <ul>
 *   <li><b>Figures</b> (digits): each number, and each date, must be present in the evidence, or be one of its derived
 *       counts. Dates are compared as dates, so "2026-11-02", "2.11.2026" and "Nov 2, 2026" are the same fact, and
 *       numbers are compared by value ("08" is 8). A list marker ("1.", "2)") needs no evidence. The model is told to
 *       write numbers as digits; a spelled-out number cannot be checked and is not.</li>
 *   <li><b>People</b>: the answer refers to people with handles ({@code [P1]}); every handle must be one the model was
 *       shown. A handle it was never given is an invented person.</li>
 *   <li><b>Withheld results</b>: a {@code [[L1]]} placeholder must be one the server issued.</li>
 *   <li><b>Plain names</b> (a person written out): only where capitalisation is a usable signal. In a language that
 *       capitalises every noun (German) a capital means nothing, so that check is switched off by measuring how many
 *       words in the answer are capitalised, not by naming languages; the handle rule above then carries the people check.</li>
 * </ul>
 *
 * It cannot prove a sentence true: a figure that is in the evidence but attached to the wrong thing passes. It rejects
 * what the evidence cannot support, which is the class of error that fabricates facts.
 */
final class WaviAnswerVerifier {

    enum Kind { NUMBER, DATE, UNKNOWN_HANDLE, UNKNOWN_PLACEHOLDER, NAME, UNRESOLVED_HANDLE }

    /** One unsupported claim: its kind and the exact text, safe to show the model and to record for diagnostics. */
    record Problem(Kind kind, String token) {
        @Override
        public String toString() {
            return kind + ":" + token;
        }
    }

    /** Evidence the answer is checked against. */
    static final class Evidence {
        final Set<String> numbers = new HashSet<>();
        final Set<LocalDate> dates = new HashSet<>();
        final Set<Integer> structuralCounts = new HashSet<>();
        final Set<String> handles = new LinkedHashSet<>();
        final Set<String> placeholders = new LinkedHashSet<>();
        final Set<String> words = new HashSet<>();
        int maxRows;
        /** The user wrote in a language that capitalises its nouns, so a capital says nothing about a name. */
        boolean capitalNounLanguage;

        /**
         * The user's own words (this message, or an earlier one). They are evidence like any context, and a sample of
         * the language the answer will be in: where capitals stand alone on nouns, name detection by capitals is off.
         */
        void addUserText(String text) {
            addContext(text);
            if (text != null && !capitalSignalUsable(text)) capitalNounLanguage = true;
        }

        /** A tool result AS THE MODEL SAW IT (handles in place of names): figures, dates, handles, rows, distinct people. */
        void addResult(String shown) {
            addContext(shown);
            if (shown == null) return;
            // Only the server's own withheld-result notice ("[[L1]] - this result is withheld...") issues a placeholder;
            // the same characters inside a result's free text do not.
            Matcher notice = WITHHELD_NOTICE.matcher(shown);
            if (notice.find()) placeholders.add(notice.group(1));
            int rows = (int) shown.lines().filter(line -> LIST_ROW.matcher(line).find()).count();
            structuralCounts.add(rows);
            maxRows = Math.max(maxRows, rows);
            structuralCounts.add(WaviPersonHandles.handlesIn(shown).size());
        }

        /** The user's message, earlier verified turns, today's date: anything the user or the server already said. */
        void addContext(String text) {
            if (text == null) return;
            handles.addAll(WaviPersonHandles.handlesIn(text));
            for (Matcher token = NUMBER.matcher(stripMarkers(text)); token.find(); ) {
                addNumberToken(token.group());
            }
            for (LocalDate date : datesIn(text)) {
                dates.add(date);
                numbers.add(normalize(String.valueOf(date.getDayOfMonth())));
                numbers.add(normalize(String.valueOf(date.getMonthValue())));
                numbers.add(normalize(String.valueOf(date.getYear())));
            }
            addWords(text);
        }

        /** Vocabulary that is allowed to appear capitalised (product, page and role names, tool descriptions). */
        void addWords(String text) {
            if (text == null) return;
            Matcher matcher = WORD.matcher(text);
            while (matcher.find()) addForms(matcher.group());
        }

        /** A fact a tool states explicitly (for example a count it computed). */
        void addFact(long value) {
            numbers.add(normalize(Long.toString(value)));
            structuralCounts.add((int) Math.max(0, Math.min(Integer.MAX_VALUE, value)));
        }

        /** A word and its equivalent forms: possessive, apostrophe style, accents, and the parts of a hyphenated name. */
        private void addForms(String word) {
            String form = normalizeWord(word);
            if (form.isEmpty()) return;
            words.add(form);
            if (form.indexOf('-') > 0) {
                for (String part : form.split("-")) {
                    if (!part.isEmpty()) words.add(part);
                }
            }
        }

        boolean knows(String word) {
            String form = normalizeWord(word);
            if (form.isEmpty() || words.contains(form)) return true;
            if (form.indexOf('-') > 0) {
                for (String part : form.split("-")) {
                    if (!part.isEmpty() && !words.contains(part)) return false;
                }
                return true;
            }
            return false;
        }

        private void addNumberToken(String token) {
            numbers.add(normalize(token));
            numbers.add(normalize(token.replace(',', '.')));
            // The parts of a time, a range or a date stand alone ("17:00" supports 17); the fraction of a decimal
            // does not ("38.5" does not support 5).
            if (COMPOSITE.matcher(token).find()) {
                for (String part : token.split("[:/-]")) {
                    if (!part.isEmpty()) numbers.add(normalize(part));
                }
            }
        }
    }

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,:/-]\\d+)*");
    /** A number written with a time, range or date separator (not a decimal point or comma). */
    private static final Pattern COMPOSITE = Pattern.compile("[:/-]");
    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}'\u2019-]*");
    private static final Pattern CAPITALISED = Pattern.compile("\\p{Lu}\\p{Ll}[\\p{L}'\u2019-]*");
    private static final Pattern LIST_ROW = Pattern.compile("^\\s*(?:[-*\u2022]|\\d{1,3}[.)])\\s+\\S");
    private static final Pattern LIST_MARKER = Pattern.compile("(?m)^\\s*(\\d{1,3})[.)](?=\\s)");
    private static final Pattern WITHHELD_NOTICE = Pattern.compile("^(\\[\\[L\\d{1,3}\\]\\]) - this result is withheld");
    private static final Pattern HANDLE_TEXT = Pattern.compile("\\[P\\d{1,4}\\]");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\[\\[L\\d{1,3}\\]\\]");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");
    private static final Pattern DAY_FIRST_DATE = Pattern.compile("(\\d{1,2})[./](\\d{1,2})[./](\\d{4})");
    /** The server's own date format: "Mon Nov 2, 2026" or "Nov 2, 2026". */
    private static final Pattern SERVER_DATE = Pattern.compile("(?:(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun),?\\s+)?(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s+(\\d{1,2}),?\\s+(\\d{4})");
    /** The share of words that are isolated capitalised nouns above which capitalisation says nothing about names. */
    static final double ISOLATED_CAPITAL_LIMIT = 0.20;

    private WaviAnswerVerifier() {
    }

    static List<Problem> verify(String answer, Evidence evidence) {
        List<Problem> problems = new ArrayList<>();
        if (answer == null || answer.isBlank()) {
            problems.add(new Problem(Kind.NUMBER, "(empty answer)"));
            return problems;
        }
        for (Matcher matcher = HANDLE_TEXT.matcher(answer); matcher.find(); ) {
            String handle = matcher.group().substring(1, matcher.group().length() - 1);
            if (!evidence.handles.contains(handle)) problems.add(new Problem(Kind.UNKNOWN_HANDLE, matcher.group()));
        }
        for (Matcher matcher = PLACEHOLDER.matcher(answer); matcher.find(); ) {
            if (!evidence.placeholders.contains(matcher.group())) problems.add(new Problem(Kind.UNKNOWN_PLACEHOLDER, matcher.group()));
        }
        // A list marker ("1.", "2)") numbers a line; it is not a claim.
        String stripped = LIST_MARKER.matcher(stripMarkers(answer)).replaceAll(" ");
        for (Set<LocalDate> readings : dateTokens(answer)) {
            // A day-first or month-first date may be read either way; it is supported if either reading is evidence.
            if (readings.stream().noneMatch(evidence.dates::contains)) {
                problems.add(new Problem(Kind.DATE, readings.iterator().next().toString()));
            }
        }
        // Dates were checked as dates above; their pieces are not checked again as loose numbers.
        String withoutDates = DAY_FIRST_DATE.matcher(ISO_DATE.matcher(SERVER_DATE.matcher(stripped).replaceAll(" "))
                .replaceAll(" ")).replaceAll(" ");
        for (Matcher token = NUMBER.matcher(withoutDates); token.find(); ) {
            String value = token.group();
            if (!numberSupported(value, evidence)) problems.add(new Problem(Kind.NUMBER, value));
        }
        if (!evidence.capitalNounLanguage && capitalSignalUsable(withoutHandles(answer))) {
            problems.addAll(unknownNames(withoutHandles(answer), evidence));
        }
        return dedupe(problems);
    }

    private static boolean numberSupported(String token, Evidence evidence) {
        if (evidence.numbers.contains(normalize(token))) return true;
        if (evidence.numbers.contains(normalize(token.replace(',', '.')))) return true;
        // A time or a range: every part must itself be supported. A decimal is supported only as the whole number.
        String[] parts = token.split("[:/-]");
        if (COMPOSITE.matcher(token).find() && parts.length > 1) {
            boolean all = true;
            for (String part : parts) {
                if (!part.isEmpty() && !evidence.numbers.contains(normalize(part))) {
                    all = false;
                    break;
                }
            }
            if (all) return true;
        }
        // Derived counts: the rows of a result, or the people it names. Not the lines, and never a sum.
        if (token.chars().allMatch(Character::isDigit) && token.length() <= 3) {
            return evidence.structuralCounts.contains(Integer.parseInt(token));
        }
        return false;
    }

    private static List<Problem> unknownNames(String answer, Evidence evidence) {
        List<Problem> problems = new ArrayList<>();
        Matcher words = WORD.matcher(answer);
        List<String> run = new ArrayList<>();
        boolean runStartsSentence = false;
        int previousEnd = 0;
        while (words.find()) {
            String word = words.group();
            boolean contiguous = !run.isEmpty() && answer.substring(previousEnd, words.start()).chars().allMatch(c -> c == ' ');
            if (CAPITALISED.matcher(word).matches()) {
                if (!contiguous) {
                    flush(run, runStartsSentence, evidence, problems);
                    run.clear();
                    runStartsSentence = startsSentence(answer, words.start());
                }
                run.add(word);
            } else {
                flush(run, runStartsSentence, evidence, problems);
                run.clear();
            }
            previousEnd = words.end();
        }
        flush(run, runStartsSentence, evidence, problems);
        return problems;
    }

    private static void flush(List<String> run, boolean startsSentence, Evidence evidence, List<Problem> problems) {
        if (run.size() < 2) return;
        // A sentence's first word is capitalised whatever it is.
        List<String> checked = run.subList(startsSentence ? 1 : 0, run.size());
        if (checked.isEmpty()) return;
        long unknown = checked.stream().filter(word -> !evidence.knows(word)).count();
        // Mostly unknown: a person's name, or a title that appears nowhere the model was allowed to look.
        if (unknown > 0 && unknown * 2 >= checked.size()) problems.add(new Problem(Kind.NAME, String.join(" ", run)));
    }

    /**
     * Whether capital letters tell a name from a common noun in this answer. In a language that capitalises every noun
     * (German) capitals are scattered through the sentence on single words; a person's name is a RUN of capitalised
     * words. So the signal is lost only when several capitalised words stand alone between lowercase ones: a name
     * does not make it unusable (it is a run), and neither does one stray proper noun.
     */
    static boolean capitalSignalUsable(String answer) {
        List<String> words = new ArrayList<>();
        List<Boolean> sentenceStart = new ArrayList<>();
        Matcher matcher = WORD.matcher(answer);
        while (matcher.find()) {
            words.add(matcher.group());
            sentenceStart.add(startsSentence(answer, matcher.start()));
        }
        int considered = 0;
        int isolated = 0;
        for (int index = 0; index < words.size(); index++) {
            String word = words.get(index);
            if (word.length() < 4 || sentenceStart.get(index)) continue;
            considered++;
            boolean capital = Character.isUpperCase(word.codePointAt(0));
            boolean previousCapital = index > 0 && !sentenceStart.get(index) && Character.isUpperCase(words.get(index - 1).codePointAt(0));
            boolean nextCapital = index + 1 < words.size() && !sentenceStart.get(index + 1)
                    && Character.isUpperCase(words.get(index + 1).codePointAt(0));
            if (capital && !previousCapital && !nextCapital) isolated++;
        }
        return !(isolated >= 2 && isolated / (double) Math.max(1, considered) >= ISOLATED_CAPITAL_LIMIT);
    }

    private static boolean startsSentence(String text, int index) {
        for (int position = index - 1; position >= 0; position--) {
            char c = text.charAt(position);
            if (c == ' ' || c == '\t') continue;
            return c == '\n' || c == '.' || c == '!' || c == '?' || c == ':' || c == '-' || c == '*' || c == '\u2022'
                    || c == '\u00bf' || c == '\u00a1' || c == ')';
        }
        return true;
    }

    /** Every date a text can be read as stating (an ambiguous 02.11.2026 contributes both readings): evidence side. */
    static Set<LocalDate> datesIn(String text) {
        Set<LocalDate> dates = new LinkedHashSet<>();
        for (Set<LocalDate> readings : dateTokens(text)) dates.addAll(readings);
        return dates;
    }

    /** One entry per date written in the text, each with the readings it can have: answer side. */
    static List<Set<LocalDate>> dateTokens(String text) {
        List<Set<LocalDate>> tokens = new ArrayList<>();
        if (text == null) return tokens;
        for (Matcher matcher = ISO_DATE.matcher(text); matcher.find(); ) {
            Set<LocalDate> readings = new LinkedHashSet<>();
            add(readings, Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)));
            if (!readings.isEmpty()) tokens.add(readings);
        }
        for (Matcher matcher = DAY_FIRST_DATE.matcher(text); matcher.find(); ) {
            int first = Integer.parseInt(matcher.group(1));
            int second = Integer.parseInt(matcher.group(2));
            int year = Integer.parseInt(matcher.group(3));
            Set<LocalDate> readings = new LinkedHashSet<>();
            add(readings, year, second, first);   // day.month.year
            add(readings, year, first, second);   // month/day/year
            if (!readings.isEmpty()) tokens.add(readings);
        }
        for (Matcher matcher = SERVER_DATE.matcher(text); matcher.find(); ) {
            Set<LocalDate> dates = new LinkedHashSet<>();
            Month month = switch (matcher.group(1)) {
                case "Jan" -> Month.JANUARY;
                case "Feb" -> Month.FEBRUARY;
                case "Mar" -> Month.MARCH;
                case "Apr" -> Month.APRIL;
                case "May" -> Month.MAY;
                case "Jun" -> Month.JUNE;
                case "Jul" -> Month.JULY;
                case "Aug" -> Month.AUGUST;
                case "Sep" -> Month.SEPTEMBER;
                case "Oct" -> Month.OCTOBER;
                case "Nov" -> Month.NOVEMBER;
                default -> Month.DECEMBER;
            };
            add(dates, Integer.parseInt(matcher.group(3)), month.getValue(), Integer.parseInt(matcher.group(2)));
            if (!dates.isEmpty()) tokens.add(dates);
        }
        return tokens;
    }

    private static boolean add(Set<LocalDate> dates, int year, int month, int day) {
        try {
            dates.add(LocalDate.of(year, month, day));
            return true;
        } catch (java.time.DateTimeException invalid) {
            return false;
        }
    }

    /**
     * One comparable form of a word: lower case, every apostrophe style as one, the possessive ending removed
     * ("Teja's", "Teja’s", "Jones'" all become the name), and accents folded ("José" matches "Jose"). Applied to BOTH sides
     * of every name comparison, so a name that is in the evidence or the user's question is never rejected for its
     * ending or its accents, and a name that is not still is.
     */
    static String normalizeWord(String word) {
        if (word == null) return "";
        String form = word.toLowerCase(Locale.ROOT).replace('\u2019', '\'').replace('\u02bc', '\'').replace('\u2018', '\'')
                .replace('`', '\'');
        form = java.text.Normalizer.normalize(form, java.text.Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        if (form.endsWith("'s") && form.length() > 3) form = form.substring(0, form.length() - 2);
        else if (form.endsWith("s'") && form.length() > 3) form = form.substring(0, form.length() - 1);
        form = form.replace("'", "");
        return form.strip();
    }

    private static String stripMarkers(String text) {
        return PLACEHOLDER.matcher(HANDLE_TEXT.matcher(text).replaceAll(" ")).replaceAll(" ");
    }

    private static String withoutHandles(String text) {
        return stripMarkers(text);
    }

    private static String normalize(String number) {
        try {
            return new BigDecimal(number.replace(',', '.')).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notDecimal) {
            return number;
        }
    }

    private static List<Problem> dedupe(List<Problem> problems) {
        return new ArrayList<>(new LinkedHashSet<>(problems));
    }

    /** Kinds only, for logs (never the text). */
    static String kinds(List<Problem> problems) {
        java.util.Map<Kind, Integer> counts = new java.util.EnumMap<>(Kind.class);
        for (Problem problem : problems) counts.merge(problem.kind(), 1, Integer::sum);
        return counts.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(","));
    }
}
