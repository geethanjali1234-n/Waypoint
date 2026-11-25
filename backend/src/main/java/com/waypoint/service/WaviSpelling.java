package com.waypoint.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Spelling tolerance for Wavi's own vocabulary ("tmesheet", "titel", "submited", "wat"): a token that is one edit
 * (insertion, deletion, substitution or adjacent transposition) away from a domain word, keeps its first letter and
 * is not itself a known word is read as that word. Only words of five letters or more are corrected this way, so a
 * short name is never turned into something else; a few very short function words have explicit forms.
 *
 * <p>This is normalisation of the question's wording before any route reads it, not a new accepted wording: every
 * route still decides on the corrected words exactly as it would on the correctly spelled ones.
 */
final class WaviSpelling {

    private WaviSpelling() {
    }

    /** Wavi's domain vocabulary: question words, record nouns, statuses, periods and workflow words. */
    private static final Set<String> VOCABULARY = Set.of(
            "about", "address", "after", "again", "already", "approval", "approvals", "approve", "approved",
            "approving", "april", "assigned", "august", "awaiting", "balance", "before", "between", "change", "changed",
            "changes", "check", "company", "complete", "completed", "contact", "count", "covering", "current", "daily",
            "december", "department", "details", "document", "documents", "draft", "drafts", "during", "email",
            "employee", "employees", "entered", "every", "february", "first", "fulfilled", "hours", "january", "july",
            "latest", "leave", "letter", "logged", "manager", "managers", "march", "mentioned", "month", "monthly",
            "needs", "november", "number", "october", "offer", "organization", "organisation", "overtime", "payroll",
            "pending", "people", "period", "phone", "please", "policy", "policies", "previous", "profile", "queue",
            "recent", "recorded", "reject", "rejected", "report", "reports", "reporting", "request", "requested",
            "requests", "review", "reviewed", "reviewer", "salary", "second", "september", "settings", "sheet",
            "sheets", "should", "status", "submission", "submissions", "submit", "submitted", "summary", "there",
            "these", "third", "those", "timesheet", "timesheets", "title", "today", "total", "unsubmitted",
            "verification", "waiting", "weekly", "which", "where", "worked", "yesterday",
            // Correct words one edit from a domain word ("three"/"there", "these"/"there"): never "corrected".
            "three", "seven", "eight", "eleven", "twelve", "fifteen", "fifty", "hundred", "thirty", "twenty", "forty",
            "their", "thank", "thanks", "while", "whose", "write", "wrote", "times",
            "leaves", "manage", "reviews", "requires", "submits", "weeks", "months",
            // A different meaning, not a misspelling: "employers" is never read as "employees".
            "employer", "employers");

    /** The vocabulary indexed by first letter, so each token is compared with a handful of words. */
    private static final java.util.Map<Character, List<String>> BY_FIRST_LETTER = new java.util.HashMap<>();

    static {
        for (String word : VOCABULARY) {
            BY_FIRST_LETTER.computeIfAbsent(word.charAt(0), ignored -> new java.util.ArrayList<>()).add(word);
        }
    }

    /** Very short function words with common misspellings; too short for edit distance to be safe. */
    private static final java.util.Map<String, String> SHORT_FORMS = java.util.Map.of(
            "wat", "what", "wht", "what", "whta", "what", "teh", "the", "hw", "how", "cna", "can", "wich", "which",
            "whn", "when", "whr", "where", "pls", "please");

    private static final int MIN_LENGTH = 5;

    /** Short question and pronoun words a doubled keystroke can hide ("wwho", "whhat", "myy"). */
    private static final Set<String> SHORT_WORDS = Set.of("who", "whom", "whose", "what", "when", "where", "which",
            "why", "how", "my", "me", "is", "are", "can", "the", "please");

    /** {@code normalized} (lower case) with each misspelled domain word replaced by the word it is one edit from. */
    static String correct(String normalized) {
        if (normalized == null || normalized.isEmpty()) return normalized;
        return submissionVerbs(spelled(normalized));
    }

    /** Phrasal verbs that mean submitting a timesheet or request: "send in", "turn in", "hand in", "put in". */
    private static final java.util.Map<String, String> SUBMIT_PARTICLE_VERBS = java.util.Map.of(
            "send", "submit", "sent", "submitted", "turn", "submit", "turned", "submitted", "hand", "submit",
            "handed", "submitted", "file", "submit", "filed", "submitted");

    /** "when did I last send in a timesheet" reads as "when did I last submit a timesheet". */
    private static String submissionVerbs(String text) {
        String[] words = text.split(" ", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < words.length; index++) {
            String word = words[index];
            String verb = SUBMIT_PARTICLE_VERBS.get(word);
            if (verb != null && index + 1 < words.length && words[index + 1].replaceAll("[^a-z]", "").equals("in")
                    && (verb.startsWith("submit") || !word.startsWith("file"))) {
                if (out.length() > 0) out.append(' ');
                out.append(verb).append(words[index + 1].replaceAll("[a-z]", ""));
                index++;
                continue;
            }
            if (out.length() > 0) out.append(' ');
            out.append(word);
        }
        return out.toString();
    }

    private static String spelled(String normalized) {
        StringBuilder out = new StringBuilder(normalized.length());
        int index = 0;
        while (index < normalized.length()) {
            char c = normalized.charAt(index);
            if (!Character.isLetter(c)) {
                out.append(c);
                index++;
                continue;
            }
            int end = index;
            while (end < normalized.length() && Character.isLetter(normalized.charAt(end))) end++;
            // A token glued to digits or an apostrophe ("wpt1002", "ravi's") is an identifier or a possessive: kept.
            boolean glued = end < normalized.length() && (Character.isDigit(normalized.charAt(end))
                    || normalized.charAt(end) == '\'' || normalized.charAt(end) == '’')
                    || (index > 0 && Character.isDigit(normalized.charAt(index - 1)));
            String token = normalized.substring(index, end);
            out.append(glued ? token : correctToken(token));
            index = end;
        }
        return out.toString();
    }

    private static String correctToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        String shortForm = SHORT_FORMS.get(lower);
        if (shortForm != null) return shortForm;
        if (VOCABULARY.contains(lower) || SHORT_WORDS.contains(lower)) return token;
        // A doubled keystroke ("wwho", "timmesheet"): one copy of a repeated letter removed gives a known word.
        for (int i = 1; i < lower.length(); i++) {
            if (lower.charAt(i) != lower.charAt(i - 1)) continue;
            String single = lower.substring(0, i) + lower.substring(i + 1);
            if (SHORT_WORDS.contains(single) || VOCABULARY.contains(single)) return single;
        }
        if (lower.length() < MIN_LENGTH) return token;
        List<String> candidates = BY_FIRST_LETTER.get(lower.charAt(0));
        if (candidates == null) return token;
        String match = null;
        for (String candidate : candidates) {
            if (Math.abs(candidate.length() - lower.length()) > 1) continue;
            if (distance(lower, candidate) == 1) {
                if (match != null) return token; // ambiguous: leave it alone
                match = candidate;
            }
        }
        return match == null ? token : match;
    }

    /** Damerau-Levenshtein distance (adjacent transposition counts as one edit), capped at 2. */
    static int distance(String a, String b) {
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
        return Math.min(d[a.length()][b.length()], 2);
    }
}
