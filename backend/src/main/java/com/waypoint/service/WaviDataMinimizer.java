package com.waypoint.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The last scrub of text on its way to an external provider (tool results and replayed history). Classification
 * ({@link WaviExternalDisclosure}) decides WHICH results may go; this removes what no answer needs from those that do:
 * e-mail addresses, phone numbers, employee-ID-shaped codes, database ids, links and secrets. It works on the shapes
 * of the data (characters), not on the wording of any question, so it cannot decide what a question means.
 */
final class WaviDataMinimizer {

    static final String EMAIL = "[email removed]";
    static final String PHONE = "[phone removed]";
    static final String IDENTIFIER = "[id removed]";
    static final String LINK = "[link removed]";

    private static final char MARK = '\u0001';

    private static final Pattern EMAIL_ADDRESS = Pattern.compile("[\\w.+%-]+@[\\w-]+(?:\\.[\\w-]+)+");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern DATABASE_ID = Pattern.compile(
            "\\b[0-9a-fA-F]{24}\\b|\\b[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}\\b");
    /** Letters, a hyphen and digits, like WPT-1001: how employee codes are written. */
    private static final Pattern CODE = Pattern.compile("\\b[A-Z]{2,6}-\\d{2,}\\b");
    private static final Pattern PHONE_CANDIDATE = Pattern.compile("(?<![\\w])\\+?\\(?\\d[\\d ().-]{7,}\\d(?![\\w])");
    /** Dates and times are answer content, and look like numbers with separators. */
    private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}(?:[ T]\\d{2}:\\d{2}(?::\\d{2})?)?");
    private static final Pattern SECRET = Pattern.compile("(?i)\\b(password|passcode|otp|token|secret|api[-_ ]?key)\\b\\s*[:=]?\\s*\\S+");

    private WaviDataMinimizer() {
    }

    /** A long mixed letter-and-digit token: how opaque ids, keyed digests and keys look. */
    private static final Pattern OPAQUE_TOKEN = Pattern.compile("(?<![\\w-])(?=[A-Za-z0-9_-]*\\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{20,}(?![\\w-])");

    /**
     * The first sensitive SHAPE found in text that is about to leave Waypoint, or null. This is the detector the
     * outbound guard runs over the finished request, independent of the scrubbing that was meant to prevent it:
     * if anything above it is ever changed to forget a field, the request is not sent.
     */
    static String sensitiveShape(String text) {
        if (text == null || text.isEmpty()) return null;
        if (EMAIL_ADDRESS.matcher(text).find()) return "EMAIL";
        if (URL.matcher(text).find()) return "LINK";
        if (DATABASE_ID.matcher(text).find()) return "DATABASE_ID";
        if (CODE.matcher(text).find()) return "EMPLOYEE_CODE";
        if (OPAQUE_TOKEN.matcher(text).find()) return "OPAQUE_TOKEN";
        String withoutDates = DATE_TIME.matcher(text).replaceAll(" ");
        Matcher phones = PHONE_CANDIDATE.matcher(withoutDates);
        while (phones.find()) {
            String candidate = phones.group();
            long digits = candidate.chars().filter(Character::isDigit).count();
            if (candidate.startsWith("+") || digits >= 10) return "PHONE";
        }
        return null;
    }

    static String minimize(String text) {
        if (text == null || text.isEmpty()) return "";
        String result = EMAIL_ADDRESS.matcher(text).replaceAll(EMAIL);
        result = URL.matcher(result).replaceAll(LINK);
        result = DATABASE_ID.matcher(result).replaceAll(IDENTIFIER);
        result = CODE.matcher(result).replaceAll(IDENTIFIER);
        result = SECRET.matcher(result).replaceAll("$1 [removed]");
        // Set dates and times aside, scrub phone numbers, put them back.
        List<String> kept = new ArrayList<>();
        Matcher dates = DATE_TIME.matcher(result);
        StringBuilder withoutDates = new StringBuilder();
        while (dates.find()) {
            kept.add(dates.group());
            dates.appendReplacement(withoutDates, Matcher.quoteReplacement(MARK + String.valueOf(kept.size() - 1) + MARK));
        }
        dates.appendTail(withoutDates);
        Matcher phones = PHONE_CANDIDATE.matcher(withoutDates);
        StringBuilder out = new StringBuilder();
        while (phones.find()) {
            String candidate = phones.group();
            long digits = candidate.chars().filter(Character::isDigit).count();
            boolean phone = (candidate.startsWith("+") || digits >= 10) && candidate.indexOf(MARK) < 0;
            phones.appendReplacement(out, Matcher.quoteReplacement(phone ? PHONE : candidate));
        }
        phones.appendTail(out);
        String restored = out.toString();
        for (int index = 0; index < kept.size(); index++) {
            restored = restored.replace(MARK + String.valueOf(index) + MARK, kept.get(index));
        }
        return restored;
    }
}
