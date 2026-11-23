package com.waypoint.service;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * What of a user's message may reach a semantic router: the words, with personal identifiers and secrets
 * replaced by placeholders, bounded in length. The router only has to understand what is being asked. It never
 * needs an email address, a phone number, an employee ID, a token or a password, so none is ever sent.
 *
 * <p>Works token by token on characters, not with phrase patterns. Dates stay, so "my timesheet for 2026-10-14"
 * can still be routed.
 */
final class WaviRouterPrivacy {

    private WaviRouterPrivacy() {
    }

    static final String EMAIL = "[email]";
    static final String NUMBER = "[number]";
    static final String IDENTIFIER = "[id]";
    static final String SECRET = "[secret]";

    /**
     * The message as a router may see it: control characters removed, whitespace collapsed, identifiers and
     * secrets replaced, at most {@code maxChars} characters.
     */
    static String sanitize(String message, int maxChars) {
        if (message == null) return "";
        StringBuilder cleaned = new StringBuilder();
        for (int index = 0; index < message.length(); index++) {
            char c = message.charAt(index);
            cleaned.append(Character.isISOControl(c) || Character.isWhitespace(c) ? ' ' : c);
        }
        StringBuilder out = new StringBuilder();
        boolean secretFollows = false;
        for (String token : cleaned.toString().trim().split(" +")) {
            if (token.isEmpty()) continue;
            String replaced;
            if (secretFollows && isFiller(token)) {
                // "password is X", "pin: X": the secret is the word after the filler.
                replaced = token;
            } else {
                replaced = secretFollows ? SECRET : replace(token);
                secretFollows = announcesSecret(token);
            }
            if (out.length() > 0) out.append(' ');
            out.append(replaced);
        }
        String text = out.toString();
        if (text.length() > maxChars) {
            int cut = Character.isLowSurrogate(text.charAt(maxChars)) ? maxChars - 1 : maxChars;
            text = text.substring(0, Math.max(0, cut));
        }
        return text;
    }

    private static final java.util.Set<String> HR_FORMS = java.util.Set.of("i-9", "i9", "i-983", "i983", "w-2", "w2",
            "w-4", "w4", "i-20", "i20", "h-1b", "h1b", "f-1", "f1", "401k", "401(k)");

    private static String replace(String token) {
        String core = strip(token);
        if (core.isEmpty()) return token;
        if (core.indexOf('@') > 0 && core.indexOf('.', core.indexOf('@')) > 0) return EMAIL;
        if (looksLikeSecret(core)) return SECRET;
        if (isDate(core)) return token;
        // Names of standard HR forms are words, not identifiers.
        if (HR_FORMS.contains(core.toLowerCase(java.util.Locale.ROOT))) return token;
        int digits = 0;
        int letters = 0;
        for (char c : core.toCharArray()) {
            if (Character.isDigit(c)) digits++;
            else if (Character.isLetter(c)) letters++;
        }
        // Phone numbers, account or national ID numbers: long digit runs, with or without separators.
        if (digits >= 7) return NUMBER;
        // Employee IDs and similar codes ("WPT-1002", "emp_a2", "WVA002"): letters mixed with digits, joined by a
        // separator or with at least two digits. Ordinal days ("14th", "2nd") are kept.
        boolean joined = core.indexOf('-') >= 0 || core.indexOf('_') >= 0;
        if (letters > 0 && (digits >= 2 || (digits >= 1 && joined)) && !isOrdinal(core)) return IDENTIFIER;
        return token;
    }

    private static boolean isFiller(String token) {
        String word = strip(token).toLowerCase(java.util.Locale.ROOT);
        return word.isEmpty() || word.equals("is") || word.equals("was") || word.equals("=") || word.equals("-");
    }

    /** "password: x", "pwd x", "token x", "otp 123456": the next word is never sent. */
    private static boolean announcesSecret(String token) {
        String word = strip(token).toLowerCase(java.util.Locale.ROOT);
        return word.equals("password") || word.equals("passcode") || word.equals("pwd") || word.equals("otp")
                || word.equals("pin") || word.equals("token") || word.equals("apikey");
    }

    /** Bearer tokens, API keys, JWTs: long unbroken runs mixing character classes. */
    private static boolean looksLikeSecret(String core) {
        if (core.length() < 20) return false;
        boolean upper = false;
        boolean lower = false;
        boolean digit = false;
        for (char c : core.toCharArray()) {
            if (Character.isUpperCase(c)) upper = true;
            else if (Character.isLowerCase(c)) lower = true;
            else if (Character.isDigit(c)) digit = true;
        }
        return digit && (upper || lower) || core.startsWith("sk-") || core.chars().filter(c -> c == '.').count() >= 2;
    }

    /** ISO dates (2026-10-14) and short numeric dates (10/14, 14-10-2026, 10/14/26). */
    private static boolean isDate(String core) {
        if (core.length() == 10 && core.charAt(4) == '-') {
            try {
                LocalDate.parse(core);
                return true;
            } catch (DateTimeParseException notIso) {
                return false;
            }
        }
        String[] parts = core.split("[/.-]");
        if (parts.length < 2 || parts.length > 3 || core.length() > 10) return false;
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            int max = index == 2 ? 4 : 2;
            if (part.isEmpty() || part.length() > max) return false;
            for (char c : part.toCharArray()) if (!Character.isDigit(c)) return false;
        }
        return true;
    }

    private static boolean isOrdinal(String core) {
        String lower = core.toLowerCase(java.util.Locale.ROOT);
        if (lower.length() < 3 || lower.length() > 4) return false;
        String suffix = lower.substring(lower.length() - 2);
        String number = lower.substring(0, lower.length() - 2);
        return (suffix.equals("st") || suffix.equals("nd") || suffix.equals("rd") || suffix.equals("th"))
                && number.chars().allMatch(Character::isDigit);
    }

    /** The token without surrounding punctuation (quotes, brackets, trailing ?,.!). */
    private static String strip(String token) {
        int start = 0;
        int end = token.length();
        while (start < end && isEdgePunctuation(token.charAt(start))) start++;
        while (end > start && isEdgePunctuation(token.charAt(end - 1))) end--;
        return token.substring(start, end);
    }

    private static boolean isEdgePunctuation(char c) {
        return "\"'`()[]{}<>,;:!?¿¡.".indexOf(c) >= 0;
    }
}
