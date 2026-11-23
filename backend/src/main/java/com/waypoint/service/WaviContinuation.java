package com.waypoint.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns a short follow-up into full candidate questions, using only the previous turn's validated tool and enum
 * arguments from the server-side {@link WaviDialogueStore} (never the browser's history text).
 *
 * <p>Structural, not phrase-based: it knows a closed set of grammatical words (pronouns that refer back, discourse
 * markers such as "and" or "what about") and one question frame per tool. It never decides an answer itself. The
 * caller asks the existing routes to answer each candidate and accepts one only if the SAME tool answered it, so a
 * follow-up can change a detail (an aspect, a queue, a profile field) but never drift to another capability. New
 * domain wording still belongs to the semantic router, not here.
 *
 * <p>Examples: after {@code get_my_requests{kind=DOCUMENT_REQUEST}}, "when was it submitted?" becomes "when was my
 * document request submitted"; after {@code get_review_queue{queue=TIMESHEETS}}, "and profile changes?" becomes "how
 * many profile changes are waiting for my approval".
 */
final class WaviContinuation {

    private WaviContinuation() {
    }

    /** A follow-up is short; a longer message is a question of its own and is never rewritten. */
    static final int MAX_WORDS = 8;
    /** A replaced subject is a short noun phrase ("profile changes", "my employee id"). */
    static final int MAX_SUBJECT_WORDS = 4;

    /** Discourse markers that open an elliptical follow-up. Longest first. */
    private static final List<String> LEADING_MARKERS = List.of("what about", "how about", "and also", "and",
            "also", "ok", "okay", "then");
    private static final List<String> TRAILING_MARKERS = List.of("as well", "too", "then");
    /** Words that refer back to the previous request. Longest first. */
    private static final List<String> BACK_REFERENCES = List.of("that one", "this one", "the request", "it", "that",
            "this");
    private static final List<String> SUBJECT_DETERMINERS = List.of("my ", "the ", "our ");

    /** Said when asked why: Waypoint records statuses and dates, not the reason behind them. */
    static final String WHY_LIMITATION = "Waypoint records statuses and dates, not the reason behind them, so I can't "
            + "tell you why; HR or the reviewer can.";

    /** Pronouns that need an antecedent. */
    private static final List<String> REFERRING_PRONOUNS = List.of("it", "it's", "its", "them");
    /** "that"/"this" refer back when they end the message or are followed by one of these words. */
    private static final List<String> DEMONSTRATIVE_FOLLOWERS = List.of("one", "is", "was", "has", "had", "been",
            "got", "get", "gets", "go", "went", "happen", "happened");
    /** After "it", these start a clause the pronoun stands for ("is it possible to …", "is it ok if …"). */
    private static final List<String> EXPLETIVE_CLAUSE_STARTS = List.of("to", "if", "whether");

    /** The noun phrase a back-reference stands for, per request kind. */
    private static final Map<String, String> REQUEST_SUBJECT = Map.of(
            "PROFILE_CHANGE", "my profile change request",
            "DOCUMENT_REQUEST", "my document request",
            "OFFER_LETTER", "my offer letter request");

    /** The full question a replaced subject is asked in, per tool. */
    private static final Map<String, String> SUBJECT_FRAME = Map.of(
            WaviTools.GET_REVIEW_QUEUE, "how many %s are waiting for my approval",
            WaviTools.GET_MY_REQUESTS, "what is the status of my %s",
            WaviTools.GET_MY_PROFILE_FIELD, "what is my %s");

    /**
     * Candidate full questions, most specific first; empty when the message is not a short follow-up to something
     * this class can continue.
     *
     * @param normalized the message, lower-cased, trimmed, without trailing punctuation
     */
    static List<String> candidates(String normalized, WaviDialogueStore.State previous) {
        if (normalized == null || previous == null || previous.tool() == null) return List.of();
        String text = normalized.replace('’', '\'').strip();
        if (text.isEmpty() || words(text) > MAX_WORDS) return List.of();
        List<String> candidates = new ArrayList<>();
        // "when was it submitted?" -> the same request, a different aspect.
        String subject = WaviTools.GET_MY_REQUESTS.equals(previous.tool())
                ? REQUEST_SUBJECT.get(String.valueOf(previous.arguments().get("kind"))) : null;
        if (subject != null) {
            String referred = replaceBackReference(text, subject);
            if (referred != null) candidates.add(referred);
        }
        // "and profile changes?" -> the same question, a different subject.
        String frame = SUBJECT_FRAME.get(previous.tool());
        String replaced = frame == null ? null : bareSubject(text);
        if (replaced != null) candidates.add(frame.formatted(replaced));
        return List.copyOf(candidates);
    }

    /**
     * Whether a short message refers back to something it does not name ("when was it submitted?", "what about that
     * one?"). Grammatical only: an "it" that introduces its own clause ("is it possible to work remotely?") does not
     * count. Used to continue a known subject, or to ask which one is meant when no verified context exists.
     */
    static boolean refersBack(String normalized) {
        if (normalized == null) return false;
        String text = normalized.replace('’', '\'').strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || words(text) > MAX_WORDS) return false;
        String[] tokens = text.split("[^a-z']+");
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            if (REFERRING_PRONOUNS.contains(token)) {
                boolean expletive = false;
                for (int later = index + 1; later < tokens.length; later++) {
                    if (EXPLETIVE_CLAUSE_STARTS.contains(tokens[later])) expletive = true;
                }
                if (!expletive) return true;
            } else if (token.equals("other") && index + 1 < tokens.length && tokens[index + 1].equals("one")) {
                return true;
            } else if (token.equals("those") || token.equals("these")) {
                // "what are those?", "those two", "these are": a reference. "these days" is a time phrase.
                if (index == tokens.length - 1 || DEMONSTRATIVE_FOLLOWERS.contains(tokens[index + 1])
                        || COUNT_WORDS.contains(tokens[index + 1]) || tokens[index + 1].matches("\\d+")) return true;
            } else if (token.equals("that") || token.equals("this")) {
                if (index == tokens.length - 1 || DEMONSTRATIVE_FOLLOWERS.contains(tokens[index + 1])) return true;
            }
        }
        return false;
    }

    private static final List<String> SCOPE_INTERROGATIVES = List.of("which", "what");
    private static final List<String> SCOPE_NOUNS = List.of("date", "dates", "day", "days", "period", "periods",
            "range", "week", "weeks", "month");
    private static final List<String> SCOPE_REFERENCES = List.of("that", "it", "this", "those", "these", "they");
    private static final List<String> TIME_ANCHORS = List.of("today", "tomorrow", "yesterday", "now", "currently",
            "next", "last", "ago");

    /**
     * Whether a short message asks which date or period the previous answer covered ("which date did that cover?",
     * "what period was it for?", "what dates are those?"): an interrogative directly before a period noun, a
     * back-reference, and no time of its own. Grammatical only; the period comes from the dialogue state, never
     * from the message, so "which day did I work the most?" (no back-reference) is not this question.
     */
    static boolean asksPreviousScope(String normalized) {
        if (normalized == null) return false;
        String text = normalized.replace('’', '\'').strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || words(text) > 8 || text.matches(".*\\d.*")) return false;
        List<String> tokens = List.of(text.split("[^a-z']+"));
        if (tokens.stream().anyMatch(TIME_ANCHORS::contains)) return false;
        boolean asked = false;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (SCOPE_INTERROGATIVES.contains(tokens.get(index)) && SCOPE_NOUNS.contains(tokens.get(index + 1))) {
                asked = true;
            }
        }
        return asked && tokens.stream().anyMatch(SCOPE_REFERENCES::contains);
    }

    /**
     * Words that make what follows a continuation marker a question or request of its own ("and what is my employee
     * id", "also show my timesheets", "and I need help") rather than a bare noun phrase: wh-words, auxiliaries and
     * modals, subject pronouns and request verbs. A closed grammatical class; no domain words.
     */
    private static final List<String> CLAUSE_STARTS = List.of("what", "what's", "who", "who's", "whom", "whose",
            "when", "where", "where's", "why", "how", "how's", "which", "is", "are", "was", "were", "am", "do", "does",
            "did", "can", "could", "will", "would", "should", "shall", "may", "might", "must", "have", "has", "had",
            "i", "i'm", "i've", "i'd", "you", "we", "they", "please", "show", "tell", "give", "list", "open", "take",
            "check", "find", "get", "help", "explain", "write", "draft");

    /**
     * Whether a short message is an ellipsis that only makes sense as a continuation: a marker ("and", "what about",
     * "also" …) followed by a bare noun phrase that is not itself a question or request and names no owner ("and
     * profile changes?", "what about the queue?"). "and my manager?" names its owner and "and what is my employee id"
     * is a question of its own, so neither is.
     */
    static boolean isElliptical(String normalized) {
        if (normalized == null) return false;
        String text = normalized.replace('’', '\'').strip().toLowerCase(Locale.ROOT);
        if (text.isEmpty() || words(text) > MAX_WORDS) return false;
        String rest = stripLeadingMarker(text);
        if (rest.equals(text) || rest.isEmpty()) return false;
        for (String marker : TRAILING_MARKERS) {
            if (rest.endsWith(" " + marker)) {
                rest = rest.substring(0, rest.length() - marker.length() - 1).strip();
                break;
            }
        }
        if (rest.isEmpty() || words(rest) > MAX_SUBJECT_WORDS) return false;
        String first = rest.split("[^a-z']+")[0];
        return !first.equals("my") && !CLAUSE_STARTS.contains(first);
    }

    /** A short message that cannot be understood without the previous turn: a back-reference or an ellipsis. */
    static boolean dependsOnContext(String normalized) {
        // A bare "for whom?" or "which ones?" asks about a previous answer; a longer who-question names its own subject.
        return refersBack(normalized) || isElliptical(normalized)
                || (asksWhichOnes(normalized) && words(normalized) <= 3);
    }

    /** Conditional subordinators: what follows describes a hypothetical, not an actual record. */
    private static final List<String> CONDITIONAL_WORDS = List.of("if", "unless", "whenever", "supposing");

    /**
     * Whether a message frames something as a hypothetical ("what happens to my pay if my timesheet is rejected?"),
     * i.e. asks about a rule rather than the status of an actual record. Grammatical: a conditional subordinator
     * ("if", "unless", "in case" …). "When" is excluded: "when was my timesheet approved?" is a real lookup.
     */
    static boolean isConditional(String normalized) {
        if (normalized == null) return false;
        String text = " " + normalized.replace('’', '\'').toLowerCase(Locale.ROOT) + " ";
        if (text.contains(" in case ")) return true;
        for (String token : text.strip().split("[^a-z']+")) {
            if (CONDITIONAL_WORDS.contains(token)) return true;
        }
        return false;
    }

    private static final List<String> COUNT_WORDS = List.of("two", "three", "four", "five", "ones", "are", "were");

    /** Interrogatives asking for people ("who needs my approval?", "for whom?", "whose are they?"). */
    private static final List<String> PERSON_INTERROGATIVES = List.of("who", "who's", "whom", "whose");

    /** Whether a message asks who (a person interrogative), whatever its length. */
    static boolean asksForPeople(String normalized) {
        if (normalized == null) return false;
        for (String token : normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+")) {
            if (PERSON_INTERROGATIVES.contains(token)) return true;
        }
        return false;
    }

    /**
     * A short follow-up asking which items or people a previous count was made of: "for whom?", "who submitted
     * them?", "which ones?". It names no new owner ("my ..."), so it is about the previous answer.
     */
    static boolean asksWhichOnes(String normalized) {
        if (normalized == null) return false;
        String text = normalized.toLowerCase(Locale.ROOT).replace('’', '\'').strip();
        if (text.isEmpty() || words(text) > MAX_WORDS) return false;
        List<String> tokens = List.of(text.split("[^a-z']+"));
        // A first-person word names the user as the subject ("who manages me?"): a question of its own.
        for (String self : List.of("my", "i", "me", "mine", "myself", "i'm")) {
            if (tokens.contains(self)) return false;
        }
        return asksForPeople(text) || (tokens.contains("which") && (tokens.contains("ones") || tokens.contains("them")
                || tokens.contains("they") || tokens.size() <= 2));
    }

    private static final List<String> HOUR_UNITS = List.of("hours", "hour", "hrs");
    /** Timesheet states and actions: with one of these, "hours" names the timesheet ("did I submit my hours?"). */
    private static final List<String> TIMESHEET_STATE_WORDS = List.of("submit", "submitted", "submitting", "approve",
            "approved", "reject", "rejected", "pending", "status", "review", "reviewed", "draft");
    private static final List<String> QUANTITY_WORDS = List.of("total", "sum", "number");

    /**
     * Whether the user asks for the hours recorded rather than a timesheet's status. "Hours" is also everyday wording
     * for the timesheet itself, so it counts only with a quantity question ("how many/much", "total") or with no
     * timesheet-state word at all ("oct 12 timesheet hours").
     */
    static boolean asksForHours(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.stream().noneMatch(HOUR_UNITS::contains)) return false;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals("how") && (tokens.get(index + 1).equals("many")
                    || tokens.get(index + 1).equals("much"))) return true;
        }
        if (tokens.stream().anyMatch(QUANTITY_WORDS::contains)) return true;
        return tokens.stream().noneMatch(TIMESHEET_STATE_WORDS::contains);
    }

    /** The message without a "how many"/"how much" interrogative (used only for hours questions). */
    static String withoutQuantityInterrogative(String normalized) {
        String text = normalized == null ? "" : normalized.strip();
        for (String lead : List.of("how many ", "how much ")) {
            int at = text.indexOf(lead);
            if (at >= 0) return (text.substring(0, at) + text.substring(at + lead.length())).strip();
        }
        return text;
    }

    /** Nouns that mean "a person in a list" ("the second employee"): positions refer to the reviewer's people list. */
    static final List<String> PERSON_NOUNS = List.of("employee", "employees", "person", "people", "report", "reports",
            "reportee", "member", "one");

    /**
     * Whether the raw message contains a name-shaped token: a capitalised word other than the first word or "I", or a
     * token containing both letters and digits (an employee ID). Structural only; it decides whether a reviewer's
     * people list is worth reading, never who is meant. An all-capitals message carries no such signal.
     */
    static boolean hasNameShapedToken(String raw) {
        if (raw == null || raw.isBlank() || raw.equals(raw.toUpperCase(Locale.ROOT))) return false;
        String[] tokens = raw.strip().split("\\s+");
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index].replaceAll("[^A-Za-z0-9'-]", "");
            if (token.isEmpty()) continue;
            if (token.matches(".*[A-Za-z].*") && token.matches(".*\\d.*")) return true;
            // A possessive of a non-pronoun word ("ravi's") refers to someone, typed in any case; contractions don't.
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.endsWith("'s") && lower.length() > 3 && !CONTRACTIONS.contains(lower)) return true;
            if (index == 0 || token.equals("I") || token.startsWith("I'")) continue;
            // "HR", "Waypoint", "October": capitalised vocabulary, not a person's name.
            if (Character.isUpperCase(token.charAt(0)) && !NOT_NAMES.contains(lower)) return true;
        }
        return false;
    }

    /** "'s" forms that are contractions or pronoun possessives, not a reference to a person. */
    private static final List<String> CONTRACTIONS = List.of("what's", "that's", "who's", "where's", "when's", "how's",
            "there's", "here's", "it's", "he's", "she's", "let's", "one's", "today's", "week's", "month's", "year's");

    private static final List<String> ORDINAL_WORDS = List.of("first", "second", "third", "fourth", "fifth", "sixth",
            "seventh", "eighth", "ninth", "tenth");

    /**
     * The position a short message refers to in a list Wavi just showed: "the second person" = 2, "the 3rd one" = 3,
     * "the last one" = -1. Null when it names none.
     */
    static Integer ordinal(String normalized) {
        if (normalized == null) return null;
        for (String token : normalized.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            int word = ORDINAL_WORDS.indexOf(token);
            if (word >= 0) return word + 1;
            if (token.equals("last")) return -1;
            if (token.matches("\\d{1,2}(st|nd|rd|th)")) return Integer.parseInt(token.replaceAll("\\D", ""));
        }
        return null;
    }

    /** Third-person pronouns that refer to a person (never to a record). */
    private static final List<String> PERSON_PRONOUNS = List.of("he", "she", "him", "her", "his", "hers", "they",
            "their", "theirs", "he's", "she's");
    /** Nouns that name a person, after a demonstrative ("this employee", "that person", "the same report"). */
    private static final List<String> PERSON_HEAD_NOUNS = List.of("employee", "person", "member", "report", "reportee",
            "user", "individual", "colleague", "worker");

    /**
     * Whether a message refers back to a PERSON rather than naming one: a third-person pronoun, or a demonstrative
     * with a person noun ("this employee", "that person", "the same employee"). Grammatical only; it never says who.
     */
    static boolean refersToPerson(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+"));
        if (tokens.stream().anyMatch(PERSON_PRONOUNS::contains)) return true;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            String determiner = tokens.get(index);
            String next = tokens.get(index + 1);
            boolean demonstrative = determiner.equals("this") || determiner.equals("that") || determiner.equals("same");
            if (demonstrative && PERSON_HEAD_NOUNS.contains(next)) return true;
        }
        return false;
    }

    /**
     * An unambiguous reference to one person: a singular third-person pronoun or a demonstrative with a person noun.
     * "they/their" are excluded: they often mean the records just listed.
     */
    static boolean refersToSpecificPerson(String normalized) {
        if (!refersToPerson(normalized)) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+"));
        return tokens.stream().anyMatch(token -> PERSON_PRONOUNS.contains(token)
                        && !List.of("they", "their", "theirs").contains(token))
                || tokens.stream().anyMatch(PERSON_HEAD_NOUNS::contains);
    }

    /** Words that name a kind of work. "all/everything/both" mean every kind. */
    private static final List<String> ALL_WORDS = List.of("all", "everything", "both", "every", "anything", "overall",
            "total", "whole");

    /**
     * The kind of work a message names, or null when it names none: TIMESHEETS (timesheet, hours), PROFILE_CHANGES
     * (profile change), DOCUMENT_REQUESTS (document), ALL ("all", "everything", or more than one kind). Used for a
     * pending-work filter and to complete a "which kind?" clarification.
     */
    static WaviTools.WorkArea workArea(String normalized) {
        if (normalized == null) return null;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        java.util.Set<WaviTools.WorkArea> named = new java.util.LinkedHashSet<>();
        for (String token : tokens) {
            if (token.startsWith("timesheet") || token.equals("hours") || token.equals("sheet") || token.equals("sheets")) {
                named.add(WaviTools.WorkArea.TIMESHEETS);
            } else if (token.equals("profile")) {
                named.add(WaviTools.WorkArea.PROFILE_CHANGES);
            } else if ((token.equals("change") || token.equals("changes"))
                    && tokens.stream().anyMatch(other -> other.startsWith("request"))) {
                // "A change request" / "what did she request to change": profile changes are the only change
                // requests in Waypoint.
                named.add(WaviTools.WorkArea.PROFILE_CHANGES);
            } else if (token.startsWith("document") || token.equals("documents") || token.equals("letter")) {
                named.add(WaviTools.WorkArea.DOCUMENT_REQUESTS);
            }
        }
        if (named.size() > 1) return WaviTools.WorkArea.ALL;
        if (named.size() == 1) return named.iterator().next();
        return tokens.stream().anyMatch(ALL_WORDS::contains) ? WaviTools.WorkArea.ALL : null;
    }

    /** The kind of work the message NAMES, ignoring "all/total/everything" words: null when it names no kind. */
    static WaviTools.WorkArea namedKind(String normalized) {
        if (normalized == null) return null;
        StringBuilder kept = new StringBuilder();
        for (String token : normalized.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (!ALL_WORDS.contains(token)) kept.append(token).append(' ');
        }
        return workArea(kept.toString());
    }

    /** Whether a message asks about work still waiting: "pending", "waiting", "outstanding", "to approve/review". */
    static boolean asksPending(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.stream().anyMatch(List.of("pending", "waiting", "awaiting", "outstanding", "queue", "assigned")::contains)) {
            return true;
        }
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals("to") && List.of("approve", "review").contains(tokens.get(index + 1))) return true;
        }
        return false;
    }

    /**
     * Whether a pending-work question names one kind of work and nothing else about whose or which: no owner ("my",
     * "I"), no reviewer role ("my approval", "to review"), no count. "Pending work for timesheets" is; "how many
     * timesheets await my approval" and "is my timesheet pending" are not (their own routes answer those).
     */
    static boolean isBareKindOfPendingWork(String normalized) {
        if (normalized == null || !asksPending(normalized) || asksCount(normalized)) return false;
        WaviTools.WorkArea area = workArea(normalized);
        if (area == null || area == WaviTools.WorkArea.ALL) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        boolean owner = tokens.stream().anyMatch(List.of("my", "i", "me", "mine")::contains);
        boolean reviewerRole = tokens.stream().anyMatch(List.of("approval", "approve", "review", "reviews", "approvals",
                "reviewer", "sign")::contains);
        return !owner && !reviewerRole;
    }

    /** How a reviewer's question about one kind of waiting work wants to be answered, if it is one. */
    enum QueueForm { NONE, LIST, COUNT }

    private static final List<String> REVIEWER_ROLE_WORDS = List.of("approval", "approve", "review", "reviews",
            "approvals", "reviewer", "sign");
    private static final List<String> YES_NO_OPENERS = List.of("any", "anything", "do", "does", "did", "is", "are",
            "have", "has", "got", "there");

    /**
     * The form of a reviewer's question about waiting work of ONE kind, from its grammar: NONE when it is not one (no
     * waiting/pending wording or kind, or the user's own record - "my timesheet" with no review wording); LIST when
     * it asks for the records ("which ...", "list", "show"); COUNT for a yes/no or how-many question that names no
     * review role ("any profile changes waiting for me?"). Review-role yes/no and count questions ("how many need my
     * approval?") are left to the queue-count routes that already answer them.
     */
    static QueueForm reviewerQueueForm(String normalized) {
        if (normalized == null || !asksPending(normalized)) return QueueForm.NONE;
        WaviTools.WorkArea area = workArea(normalized);
        if (area == null || area == WaviTools.WorkArea.ALL) return QueueForm.NONE;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        boolean reviewerRole = tokens.stream().anyMatch(REVIEWER_ROLE_WORDS::contains);
        boolean ownSubject = tokens.stream().anyMatch(List.of("my", "mine", "i")::contains);
        if (ownSubject && !reviewerRole) return QueueForm.NONE;
        if (asksForRecords(normalized)) return QueueForm.LIST;
        // "Are there timesheets I still have to review?" asks whether work exists, not how to review.
        if (reviewerRole) return asksExistence(normalized) ? QueueForm.COUNT : QueueForm.NONE;
        return asksCount(normalized) || isYesNo(normalized) ? QueueForm.COUNT : QueueForm.LIST;
    }

    // --- Whose records a question is about (grammar only; never who a person is) -----------------------------------

    private static final List<String> FIRST_PERSON = List.of("my", "i", "me", "mine", "i've", "i'm");

    /** Whether the message names the user as owner or subject ("my", "I", "me", "mine"). */
    static boolean hasFirstPerson(String normalized) {
        if (normalized == null) return false;
        for (String token : normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+")) {
            if (FIRST_PERSON.contains(token)) return true;
        }
        return false;
    }

    /** A how-to or where question by its opening ("how do I ...", "where can I ..."); "how many/much" is not. */
    static boolean isHowTo(String normalized) {
        if (normalized == null) return false;
        String[] tokens = normalized.toLowerCase(Locale.ROOT).strip().split("[^a-z']+");
        if (tokens.length == 0) return false;
        // "Where do/can I ..." asks how; "where is my request (at)?" asks for a status.
        if (tokens[0].equals("where")) {
            return tokens.length > 1 && List.of("do", "can", "should", "would", "to", "could", "will").contains(tokens[1]);
        }
        return tokens[0].equals("how") && (tokens.length < 2 || !List.of("many", "much").contains(tokens[1]));
    }

    /** Nouns for everyone a reviewer oversees, as a group. */
    private static final List<String> GROUP_NOUNS = List.of("team", "teams", "everyone", "everybody", "employees", "staff",
            "reports", "reportees", "organization", "organisation", "company", "people", "workforce");

    /** Whether the message is about a whole group ("my team", "everyone", "the organization"), not one person. */
    static boolean namesGroup(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("'s\\b", "")
                .split("[^a-z]+"));
        return tokens.stream().anyMatch(GROUP_NOUNS::contains);
    }

    /** Nouns that name another person by their relationship to the user ("my manager", "my colleague"). */
    private static final List<String> RELATION_NOUNS = List.of("manager", "boss", "supervisor", "colleague", "coworker",
            "teammate", "peer", "director", "lead", "reviewer", "approver");
    private static final List<String> REVIEW_VERB_STEMS = List.of("approv", "reject", "review", "sign", "process");

    /**
     * Whether the records asked about belong to someone named only by a relationship to the user: "my manager's
     * hours", "how many hours did my boss log". The relation noun follows "my"/"our" and is the subject or possessor;
     * a question about what that person did to the user's OWN record ("did my manager approve my timesheet?") is
     * not one. Grammar only: it never says who the person is.
     */
    static boolean namesRelatedPerson(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+"));
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (!tokens.get(index).equals("my") && !tokens.get(index).equals("our")) continue;
            String noun = tokens.get(index + 1);
            boolean possessive = noun.endsWith("'s");
            if (!RELATION_NOUNS.contains(possessive ? noun.substring(0, noun.length() - 2) : noun)) continue;
            if (possessive) return true;
            // "did my manager approve ...": about a review of the user's own record, not the manager's records.
            boolean reviewAction = tokens.stream().anyMatch(token -> REVIEW_VERB_STEMS.stream().anyMatch(token::startsWith));
            boolean ownElsewhere = false;
            for (int other = 0; other < tokens.size(); other++) {
                if (other != index && FIRST_PERSON.contains(tokens.get(other))) ownElsewhere = true;
            }
            if (!reviewAction && !ownElsewhere) return true;
        }
        return false;
    }

    private static final List<String> SUBMISSION_VERBS = List.of("submitted", "submit", "submits", "submitting", "filed",
            "logged", "entered", "sent", "turned", "handed", "missing", "missed");

    /** "Who submitted ...", "who hasn't sent ...", "whose timesheet is missing": people, by a submission. */
    static boolean asksWhoSubmitted(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+"));
        // "Who ...", or "which of my reports / which employees ...": a question about people.
        boolean aboutPeople = asksForPeople(normalized)
                || (tokens.contains("which") && tokens.stream().anyMatch(PEOPLE_WORDS::contains));
        if (!aboutPeople) return false;
        return tokens.stream().anyMatch(SUBMISSION_VERBS::contains)
                && tokens.stream().noneMatch(token -> REVIEW_VERB_STEMS.stream().anyMatch(token::startsWith));
    }

    private static final List<String> NEGATIONS = List.of("not", "hasn't", "haven't", "didn't", "hasnt", "havent", "didnt",
            "isn't", "isnt", "never", "without", "missing", "missed", "yet", "no", "failed", "forgot", "still");

    /** Whether the message asks about what has NOT happened ("who hasn't submitted", "missing timesheets"). */
    static boolean isNegated(String normalized) {
        if (normalized == null) return false;
        for (String token : normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+")) {
            if (NEGATIONS.contains(token)) return true;
        }
        return false;
    }

    private static final List<String> WORK_VERBS = List.of("work", "worked", "working", "log", "logged", "clock",
            "clocked", "record", "recorded", "spent", "spend");

    /**
     * Whether the message asks what someone worked ("what did Ravi work between ...", "did she work last week"): with
     * a period, an hours question that names no unit. Not a status or review question.
     */
    static boolean asksWorkDone(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.stream().anyMatch(WORK_VERBS::contains) && !tokens.contains("status")
                && !tokens.contains("overtime") && !isHowTo(normalized)
                && tokens.stream().noneMatch(token -> REVIEW_VERB_STEMS.stream().anyMatch(token::startsWith));
    }

    private static final List<String> TOTAL_WORDS = List.of("total", "totals", "sum", "altogether", "overall", "tally");

    /**
     * Whether the message asks for a total without saying of what ("what's my total for October?", "what's the
     * total?"). The caller decides what can be totalled; here only the grammar: a total word, not a how-to.
     */
    static boolean asksBareTotal(String normalized) {
        if (normalized == null || isHowTo(normalized)) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        for (int index = 0; index < tokens.size(); index++) {
            if (!TOTAL_WORDS.contains(tokens.get(index))) continue;
            // "Total staff", "total number of employees": a headcount, not a total of hours.
            int next = index + 1;
            while (next < tokens.size() && List.of("number", "of", "count", "the").contains(tokens.get(next))) next++;
            if (next < tokens.size() && (PEOPLE_WORDS.contains(tokens.get(next)) || tokens.get(next).equals("hr"))) return false;
            return true;
        }
        return false;
    }

    /**
     * "When was ... approved?", "who rejected ...?": asks about a past review decision (its date or its reviewer),
     * not the current status. Returns APPROVED, REJECTED or ANY (reviewed), or null when it is not such a question.
     */
    static String askedReviewDecision(String normalized) {
        if (normalized == null) return null;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        boolean whenOrWho = tokens.contains("when") || tokens.contains("who") || tokens.contains("date")
                || tokens.contains("whom");
        if (!whenOrWho) return null;
        if (tokens.contains("approved")) return "APPROVED";
        if (tokens.contains("rejected") || tokens.contains("returned")) return "REJECTED";
        return tokens.contains("reviewed") || tokens.contains("processed") ? "ANY" : null;
    }

    /** "Latest", "newest", "most recent", "last timesheet/submission": one record, the newest. */
    static boolean asksLatest(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.contains("latest") || tokens.contains("newest")) return true;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            String next = tokens.get(index + 1);
            if (tokens.get(index).equals("most") && next.equals("recent")) return true;
            if ((tokens.get(index).equals("last") || tokens.get(index).equals("recent"))
                    && (next.startsWith("timesheet") || next.startsWith("submi"))) return true;
        }
        return false;
    }

    /**
     * "Who reviews/approves my timesheet?": who acts on the user's own submissions, in the present tense. A past
     * decision ("who approved my timesheet?") is a question about one record and is not this.
     */
    static boolean asksWhoReviewsOwnTimesheets(String normalized) {
        if (normalized == null || !asksForPeople(normalized)) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        boolean own = false;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals("my") && tokens.get(index + 1).startsWith("timesheet")) own = true;
        }
        // Any present-tense review verb ("approves", "approving", "signs off"); a past decision ends in "-ed".
        return own && tokens.stream().anyMatch(token -> !token.endsWith("ed")
                && REVIEW_VERB_STEMS.stream().anyMatch(token::startsWith));
    }

    /** "What happens if ...", "what if ...", "what would happen ...": asks about a consequence, not a record. */
    static boolean asksConsequence(String normalized) {
        if (normalized == null) return false;
        String[] tokens = normalized.toLowerCase(Locale.ROOT).strip().split("[^a-z']+");
        return tokens.length >= 2 && tokens[0].equals("what")
                && List.of("happens", "if", "would", "will", "happen").contains(tokens[1]);
    }

    /** In a short follow-up, a bare status adjective that means "still waiting for review". */
    static boolean namesAwaitingStatus(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.stream().anyMatch(List.of("submitted", "pending", "waiting", "awaiting", "unreviewed")::contains);
    }

    private static final List<String> EXISTENCE_WORDS =List.of("any", "anything", "there", "have", "got", "left");

    /** A yes/no question about whether something exists ("are there ...", "do I have any ...", "anything left ..."). */
    static boolean asksExistence(String normalized) {
        if (!isYesNo(normalized)) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.stream().anyMatch(EXISTENCE_WORDS::contains);
    }

    private static final List<String> SUBJECT_LEADS = List.of("for", "of", "by", "from", "did", "does", "has", "is",
            "was", "about");
    private static final List<String> NOT_A_SUBJECT = List.of("the", "a", "an", "it", "its", "that", "this", "these",
            "those", "there", "we", "us", "you", "your", "our", "each", "every", "any", "some", "day", "days", "date",
            "now", "today", "yesterday", "far", "review", "approval", "anyone", "someone", "work", "which", "not");

    /**
     * Whether a message typed without capitals may still name a person, from its grammar: a word that is not
     * Waypoint, calendar or function vocabulary directly after a subject lead ("hours FOR ravi last week", "DID asha
     * work ..."). Structural only: it decides whether the reviewer's people list is worth reading, never who is meant.
     */
    static boolean mayNamePersonInLowerCase(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).replace('’', '\'').replaceAll("'s\\b", "")
                .split("[^a-z]+"));
        // A message that opens with such a word ("ravi teja hours oct 5 to 18"), or has one after a subject lead.
        if (!tokens.isEmpty() && nameCandidate(tokens.getFirst()) && !OPENING_VERBS.contains(tokens.getFirst())) return true;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (SUBJECT_LEADS.contains(tokens.get(index)) && nameCandidate(tokens.get(index + 1))) return true;
        }
        return false;
    }

    private static final List<String> OPENING_VERBS = List.of("list", "get", "display", "view", "check", "compare",
            "count", "find", "see", "open", "need", "want", "any", "anything", "have", "has", "was", "were", "will",
            "would", "could", "should", "why", "whose", "whom", "daily", "weekly", "monthly", "outstanding", "waiting",
            "awaiting", "and", "but", "also", "just", "only", "okay", "for", "from", "about", "with");

    private static boolean nameCandidate(String token) {
        return token.length() >= 3 && !NOT_NAMES.contains(token) && !WORK_WORDS.contains(token)
                && !GROUP_NOUNS.contains(token) && !NOT_A_SUBJECT.contains(token) && !FIRST_PERSON.contains(token)
                && !PERSON_PRONOUNS.contains(token) && !SUBJECT_LEADS.contains(token) && workArea(token) == null;
    }

    /** Capitalised words that are Waypoint's own vocabulary, not a person's name ("Profile Change Requests"). */
    private static final List<String> WORK_WORDS = List.of("change", "changes", "request", "requests", "review", "reviews",
            "queue", "payroll", "readiness", "team", "pending", "documents", "submitted", "approved", "rejected", "draft",
            "hours", "week", "month", "last", "this", "next", "organization", "settings", "employees", "all", "total");

    /**
     * Whether the message names a specific person by a name-shaped phrase that is not Waypoint vocabulary: used only
     * to say "I can't find that person in your scope" instead of answering about someone else or guessing.
     */
    static boolean namesUnlistedPerson(String raw) {
        if (!namesThirdParty(raw)) return false;
        String[] tokens = raw.strip().replace('’', '\'').split("\\s+");
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index].replaceAll("[^A-Za-z'-]", "");
            String lower = token.toLowerCase(Locale.ROOT).replaceAll("'s$", "");
            if (lower.isEmpty() || WORK_WORDS.contains(lower) || NOT_NAMES.contains(lower)) continue;
            boolean possessive = token.toLowerCase(Locale.ROOT).endsWith("'s") && !CONTRACTIONS.contains(token.toLowerCase(Locale.ROOT));
            if (possessive) return true;
            if (index + 1 < tokens.length) {
                String next = tokens[index + 1].replaceAll("[^A-Za-z'-]", "").replaceAll("'s$", "");
                if (capitalisedName(token) && capitalisedName(next) && !WORK_WORDS.contains(next.toLowerCase(Locale.ROOT))) return true;
            }
        }
        return false;
    }

    /** Whether a message asks for the records themselves: "which ...", "who ...", "list ...", "show ...". */
    static boolean asksForRecords(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).strip().split("[^a-z]+"));
        if (tokens.isEmpty()) return false;
        return tokens.contains("which") || tokens.contains("list") || List.of("who", "whose", "show", "give")
                .contains(tokens.getFirst());
    }

    private static final List<String> RECORD_NOUNS = List.of("timesheets", "submissions", "requests");

    // --- Scope, authority and audience grammar (never who someone is, only how the message is framed) -------------

    private static final List<String> NAVIGATION_VERBS = List.of("open", "bring", "take", "show", "go", "navigate",
            "launch", "pull", "get", "jump", "load", "display");
    private static final List<String> NAVIGATION_FILLER = List.of("please", "pls", "up", "me", "for", "to", "the", "my",
            "can", "could", "you", "would", "will", "kindly", "now", "quickly", "just", "page", "screen", "section", "tab");

    /**
     * A navigation command in any phrasing ("bring up org settings for me", "can you pull up the employees page"):
     * the target words only, behind the canonical verb "open", for the established page matcher to resolve; null
     * when the message does not open with a navigation verb or holds a question about records.
     */
    static String canonicalNavigation(String normalized) {
        if (normalized == null) return null;
        List<String> tokens = new java.util.ArrayList<>(List.of(normalized.toLowerCase(Locale.ROOT).strip().split("[^a-z]+")));
        tokens.removeIf(String::isEmpty);
        while (!tokens.isEmpty() && List.of("please", "pls", "can", "could", "would", "will", "you", "kindly").contains(tokens.getFirst())) {
            tokens.removeFirst();
        }
        if (tokens.isEmpty() || !NAVIGATION_VERBS.contains(tokens.getFirst())) return null;
        // "Show my profile" / "get my details" ask for the record itself; these verbs navigate only with a page word.
        if (List.of("show", "get", "display", "pull", "load").contains(tokens.getFirst())
                && tokens.stream().noneMatch(token -> List.of("page", "screen", "section", "tab", "settings", "dashboard")
                .contains(token))) return null;
        if (tokens.stream().anyMatch(token -> List.of("how", "many", "much", "status", "hours", "which", "who", "when", "why")
                .contains(token))) return null;
        List<String> target = tokens.subList(1, tokens.size()).stream().filter(token -> !NAVIGATION_FILLER.contains(token)).toList();
        if (target.isEmpty() || target.size() > 4) return null;
        return "open " + String.join(" ", target);
    }

    /** Verbs of obtaining a document for oneself. "Request" is left to the established workflow classifier. */
    private static final List<String> REQUEST_VERBS = List.of("ask", "get", "obtain", "apply", "receive", "need");
    /** Verbs of handling someone else's request: a reviewer workflow, never "how do I get a document". */
    private static final List<String> HANDLING_VERBS = List.of("fulfil", "fulfill", "process", "approve", "review",
            "complete", "upload", "reject", "handle", "send");

    /**
     * "How do I ask HR for an experience letter?", "how can I get a salary certificate": how to obtain a document,
     * which in Waypoint is a document request. Grammar only: a how-to, a verb of obtaining and a document noun.
     */
    static boolean asksHowToObtainDocument(String normalized) {
        if (normalized == null || !isHowTo(normalized)) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.stream().anyMatch(REQUEST_VERBS::contains) && tokens.stream().anyMatch(DOCUMENT_NOUNS::contains)
                && tokens.stream().noneMatch(HANDLING_VERBS::contains)
                && !tokens.contains("policy") && !tokens.contains("offer");
    }

    private static final List<String> TIME_LEADS = List.of("this", "last", "next", "current", "for", "in", "during");
    private static final List<String> TIME_NOUNS = List.of("month", "week", "year", "quarter", "today", "now", "currently",
            "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
            "november", "december");

    /** The message without trailing time qualifiers ("this month", "for october", "right now"); else unchanged. */
    static String withoutTimeQualifiers(String text) {
        if (text == null) return null;
        List<String> words = new java.util.ArrayList<>(List.of(text.strip().replaceAll("[?.!]+$", "").split("\\s+")));
        boolean changed = false;
        while (words.size() > 2) {
            String last = words.getLast().toLowerCase(Locale.ROOT);
            if (List.of("today", "now", "currently").contains(last)) {
                words.removeLast();
                if (!words.isEmpty() && words.getLast().equalsIgnoreCase("right")) words.removeLast();
                changed = true;
            } else if (TIME_NOUNS.contains(last) && words.size() > 3
                    && TIME_LEADS.contains(words.get(words.size() - 2).toLowerCase(Locale.ROOT))) {
                words.removeLast();
                words.removeLast();
                changed = true;
            } else {
                break;
            }
        }
        return changed ? String.join(" ", words) : text;
    }

    /** "We", "our", "us": the speaker's organization or team as a group. */
    static boolean firstPersonPlural(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.contains("we") || tokens.contains("our") || tokens.contains("us");
    }

    private static final List<String> DETERMINERS =List.of("my", "the", "our", "his", "her", "their", "its");

    /**
     * The second half of "what is my role and my job title" asked with the first half's frame: the words of the
     * first half before its determiner ("what is") + the second half ("my job title"). Null unless the second half is
     * a bare noun phrase (opens with a determiner) and the first half opens with a question word.
     */
    static String borrowQuestionFrame(String first, String second) {
        if (first == null || second == null) return null;
        String[] a = first.strip().toLowerCase(Locale.ROOT).split("\\s+");
        String[] b = second.strip().toLowerCase(Locale.ROOT).split("\\s+");
        if (a.length < 2 || b.length < 2 || !DETERMINERS.contains(b[0])) return null;
        if (!List.of("what", "what's", "whats", "who", "who's", "when", "where", "which", "show", "tell", "give", "list")
                .contains(a[0])) return null;
        int cut = -1;
        for (int index = 1; index < a.length; index++) {
            if (DETERMINERS.contains(a[index])) {
                cut = index;
                break;
            }
        }
        if (cut <= 0) return null;
        return String.join(" ", java.util.Arrays.copyOfRange(a, 0, cut)) + " " + second.strip();
    }

    private static final List<String> CONJUNCTIONS = List.of("and", "&", "plus", "also");

    /** The two halves of a message joined by a conjunction ("... and ..."), stripped; null when it has none. */
    static String[] splitConjunction(String message) {
        if (message == null) return null;
        String[] words = message.strip().split("\\s+");
        for (int index = 1; index + 1 < words.length; index++) {
            if (!CONJUNCTIONS.contains(words[index].toLowerCase(Locale.ROOT))) continue;
            // "Ravi Teja and Asha Rao": names joined, one question about two people, not two questions.
            String before = words[index - 1].replaceAll("[^A-Za-z]", "");
            String after = words[index + 1].replaceAll("[^A-Za-z]", "");
            if (capitalisedName(before) && capitalisedName(after)) return null;
            return new String[]{String.join(" ", java.util.Arrays.copyOfRange(words, 0, index)),
                    String.join(" ", java.util.Arrays.copyOfRange(words, index + 1, words.length))};
        }
        return null;
    }

    /** "How many employees", "number of people", "headcount": a count OF people, not of their records. */
    static boolean countsPeople(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.contains("headcount")) return true;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            boolean lead = (tokens.get(index).equals("how") && tokens.get(index + 1).equals("many"))
                    || (tokens.get(index).equals("number") && tokens.get(index + 1).equals("of"))
                    || (tokens.get(index).equals("count") && tokens.get(index + 1).equals("of"));
            if (!lead) continue;
            for (int look = index + 2; look < Math.min(tokens.size(), index + 5); look++) {
                if (PEOPLE_WORDS.contains(tokens.get(look)) || tokens.get(look).equals("hr")) return true;
                if (workArea(tokens.get(look)) != null) return false;
            }
        }
        return false;
    }

    private static final List<String> ORGANIZATION_NOUNS =List.of("organization", "organisation", "org", "company",
            "tenant", "companies", "organizations", "organisations", "tenants");

    /** "Another organization", "a different company", "other tenants": records outside the actor's own organization. */
    static boolean namesOtherOrganization(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        for (int index = 0; index < tokens.size(); index++) {
            if (!List.of("another", "other", "different", "every", "all").contains(tokens.get(index))) continue;
            for (int look = index + 1; look < Math.min(tokens.size(), index + 3); look++) {
                if (ORGANIZATION_NOUNS.contains(tokens.get(look))) return true;
            }
        }
        return false;
    }

    private static final List<String> AUTHORITY_ROLES = List.of("hr", "admin", "administrator", "manager", "supervisor",
            "reviewer", "approver", "superuser", "owner");

    /**
     * Whether the message asserts a role or permission in words ("as an HR admin", "I am the manager", "I authorize
     * you", "you are allowed to"). Such a claim is never acted on: the verified sign-in decides the role.
     */
    static boolean claimsAuthority(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).replace('’', '\'').split("[^a-z']+"));
        for (int index = 0; index + 1 < tokens.size(); index++) {
            String word = tokens.get(index);
            String next = tokens.get(index + 1);
            if (word.equals("as") && (next.equals("an") || next.equals("a") || next.equals("the"))
                    && index + 2 < tokens.size() && AUTHORITY_ROLES.contains(tokens.get(index + 2))) return true;
            if ((word.equals("i'm") || (word.equals("i") && (next.equals("am") || next.equals("m"))))
                    && tokens.subList(index + 1, Math.min(tokens.size(), index + 4)).stream().anyMatch(AUTHORITY_ROLES::contains)) {
                return true;
            }
            if (word.equals("i") && (next.startsWith("authori") || next.equals("permit") || next.equals("allow"))) return true;
            if (word.equals("you") && (next.equals("are") || next.equals("have"))
                    && tokens.subList(index + 2, Math.min(tokens.size(), index + 5)).stream()
                    .anyMatch(token -> token.startsWith("authori") || token.equals("allowed") || token.equals("permission")
                            || token.equals("permitted") || token.equals("cleared"))) return true;
        }
        return false;
    }

    /** Whether the message asks about review work in general ("my queue", "what's waiting for approval"). */
    static boolean asksQueueOverview(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.contains("queue") || tokens.contains("inbox")) return true;
        boolean waiting = tokens.stream().anyMatch(List.of("waiting", "pending", "awaiting", "outstanding", "assigned")::contains);
        boolean review = tokens.stream().anyMatch(List.of("approval", "approvals", "review", "reviews", "approve", "sign")::contains);
        return waiting && review;
    }

    private static final List<String> CONTACT_WORDS = List.of("email", "mail", "phone", "mobile", "cell", "contact",
            "address", "extension", "whatsapp", "telephone");

    /** Whether the message asks for a contact detail (email, phone, address). */
    static boolean asksContactDetail(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.stream().anyMatch(CONTACT_WORDS::contains);
    }

    /** Whether the raw message holds an employee-ID-shaped token: letters and digits together ("WPT-1002", "EMP007"). */
    static boolean hasEmployeeIdToken(String raw) {
        if (raw == null) return false;
        for (String token : raw.split("\\s+")) {
            String cleaned = token.replaceAll("[^A-Za-z0-9-]", "");
            if (cleaned.length() >= 4 && cleaned.matches(".*[A-Za-z].*") && cleaned.matches(".*\\d.*")
                    && !cleaned.matches("\\d{4}-\\d{2}-\\d{2}") && !cleaned.matches("\\d+(st|nd|rd|th)")) return true;
        }
        return false;
    }

    /** The role a people question narrows to ("the HR users", "which employees"), or null for everyone. */
    static com.waypoint.enums.UserRole askedPeopleRole(String normalized) {
        if (normalized == null) return null;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        boolean hr = tokens.contains("hr") || tokens.contains("hrs");
        boolean employees = tokens.contains("employee") || tokens.contains("employees");
        if (hr && !employees) return com.waypoint.enums.UserRole.HR;
        if (employees && !hr) return com.waypoint.enums.UserRole.EMPLOYEE;
        return null;
    }

    private static final List<String> PEOPLE_WORDS = List.of("people", "employees", "staff", "users", "accounts",
            "members", "reports", "reportees", "headcount", "team", "workforce");

    /** Whether the message is about the people themselves ("how many employees", "who are the HR users"). */
    static boolean aboutPeople(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.stream().anyMatch(PEOPLE_WORDS::contains) || tokens.contains("hr");
    }

    /** "What timesheets did I submit ...", "what are my submissions for ...": asks for the records themselves. */
    static boolean asksWhatRecords(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).strip().split("[^a-z]+"));
        if (tokens.size() < 2 || !tokens.getFirst().equals("what")) return false;
        int next = 1;
        if (tokens.get(next).equals("are") || tokens.get(next).equals("were")) next++;
        while (next < tokens.size() && List.of("my", "all", "the", "of").contains(tokens.get(next))) next++;
        return next < tokens.size() && RECORD_NOUNS.contains(tokens.get(next));
    }

    /**
     * A bare noun phrase naming the user's records, with no question or status verb: "my timesheets for October",
     * "all my submissions last month". Short, plural, not a yes/no or how-to question.
     */
    static boolean isBareRecordPhrase(String normalized) {
        if (normalized == null || isYesNo(normalized) || isHowTo(normalized) || words(normalized) > 7) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).strip().split("[^a-z]+"));
        if (tokens.isEmpty() || !List.of("my", "all", "the").contains(tokens.getFirst())) return false;
        return tokens.stream().anyMatch(RECORD_NOUNS::contains) && !tokens.contains("status")
                && tokens.stream().noneMatch(List.of("is", "are", "was", "were", "did", "does", "has", "have")::contains);
    }

    /** Whether a message is a yes/no question by its opening word ("any ...?", "is there ...", "do I have ..."). */
    static boolean isYesNo(String normalized) {
        if (normalized == null || normalized.isBlank()) return false;
        String first = normalized.toLowerCase(Locale.ROOT).strip().split("[^a-z]+")[0];
        return YES_NO_OPENERS.contains(first);
    }

    /**
     * Whether hours are asked day by day rather than as a period total: "daily", or "each/every/per/by" + "day".
     * The granularity slot of an hours question.
     */
    static boolean asksDaily(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.contains("daily") || tokens.contains("everyday") || tokens.contains("daywise")) return true;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            // "day-wise", "day wise": the same granularity word written as two tokens.
            if (tokens.get(index).equals("day") && tokens.get(index + 1).equals("wise")) return true;
            if (List.of("each", "every", "per", "by").contains(tokens.get(index))
                    && List.of("day", "days").contains(tokens.get(index + 1))) return true;
        }
        return false;
    }

    private static final List<String> NOT_NAMES = List.of("january", "february", "march", "april", "may", "june",
            "july", "august", "september", "october", "november", "december", "jan", "feb", "mar", "apr", "jun", "jul",
            "aug", "sep", "sept", "oct", "nov", "dec", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
            "sunday", "waypoint", "wavi", "wavy", "hr", "admin", "company", "employee", "employees", "timesheet",
            "timesheets", "profile", "document", "i", "pto", "id", "what", "how", "when", "where", "who", "which", "is",
            "are", "my", "the", "show", "please", "can", "do", "does", "did", "hi", "hello", "thanks", "tell", "give");

    /**
     * Whether the message names a THIRD person as its subject, structurally: a possessive of a word that is not a
     * pronoun or time word ("tomasz's", "Aiyana Begay's"), or two consecutive capitalised words that are not month,
     * weekday or product words ("Tomasz Nowak"). It never says who; it only stops an own-record answer from being
     * given about the wrong person.
     */
    static boolean namesThirdParty(String raw) {
        if (raw == null || raw.isBlank()) return false;
        String[] tokens = raw.strip().replace('’', '\'').split("\\s+");
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index].replaceAll("[^A-Za-z'-]", "");
            String lower = token.toLowerCase(Locale.ROOT);
            if (lower.endsWith("'s") && lower.length() > 3 && !CONTRACTIONS.contains(lower)
                    && !NOT_NAMES.contains(lower.substring(0, lower.length() - 2))) return true;
            if (index + 1 < tokens.length) {
                String next = tokens[index + 1].replaceAll("[^A-Za-z'-]", "");
                if (capitalisedName(token) && capitalisedName(next.replaceAll("'s$", ""))) return true;
            }
        }
        return false;
    }

    private static boolean capitalisedName(String token) {
        return token.length() >= 2 && Character.isUpperCase(token.charAt(0)) && token.substring(1).chars()
                .allMatch(Character::isLowerCase) && !NOT_NAMES.contains(token.toLowerCase(Locale.ROOT));
    }

    /** Whether a message asks for a number ("how many", "count", "number of"). */
    static boolean asksCount(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.contains("count") || tokens.contains("number")) return true;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals("how") && tokens.get(index + 1).equals("many")) return true;
        }
        return false;
    }

    /** Whether a message asks WHEN something was submitted ("when did I submit it?", "submission date of that"). */
    static boolean asksSubmissionTime(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        boolean submit = tokens.stream().anyMatch(token -> token.startsWith("submit") || token.equals("submission")
                || token.equals("sent") || token.equals("send"));
        boolean when = tokens.contains("when") || tokens.contains("date") || tokens.contains("time")
                || tokens.contains("day");
        return submit && when;
    }

    /**
     * The timesheet status a message filters on, or null for none: REJECTED, APPROVED, DRAFT, or SUBMITTED ("submitted
     * and awaiting review"). A bare "submit" verb is the act of submitting, not a filter, so only adjective forms count.
     */
    static String timesheetStatusFilter(String normalized) {
        if (normalized == null) return null;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        if (tokens.contains("rejected") || tokens.contains("returned")) return WaviTools.TimesheetStatusFilter.REJECTED.name();
        // "Sent back (for fixes)", "need corrections", "kicked back": a rejection the employee must correct.
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (List.of("sent", "send", "kicked", "bounced", "handed").contains(tokens.get(index))
                    && tokens.get(index + 1).equals("back")) return WaviTools.TimesheetStatusFilter.REJECTED.name();
        }
        if (tokens.contains("fixes") || tokens.contains("corrections") || tokens.contains("redo")) {
            return WaviTools.TimesheetStatusFilter.REJECTED.name();
        }
        // "Approved or not": a status question about approval, not a filter that keeps only approved records.
        boolean eitherWay = false;
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals("or") && tokens.get(index + 1).equals("not")) eitherWay = true;
        }
        if (tokens.contains("approved") && !eitherWay) return WaviTools.TimesheetStatusFilter.APPROVED.name();
        // "Anything I still need to do / to fix / to submit", "action needed": drafts and rejections both wait on the user.
        for (int index = 0; index + 2 < tokens.size(); index++) {
            if (List.of("need", "needs", "have", "has").contains(tokens.get(index)) && tokens.get(index + 1).equals("to")
                    && List.of("do", "fix", "finish", "complete", "submit", "resubmit", "correct").contains(tokens.get(index + 2))) {
                return WaviTools.TimesheetStatusFilter.NEEDS_ACTION.name();
            }
        }
        if (tokens.contains("action") && (tokens.contains("needed") || tokens.contains("required") || tokens.contains("need"))) {
            return WaviTools.TimesheetStatusFilter.NEEDS_ACTION.name();
        }
        if (tokens.contains("draft") || tokens.contains("drafts") || tokens.contains("unsubmitted")) {
            return WaviTools.TimesheetStatusFilter.DRAFT.name();
        }
        // "not submitted yet", "not yet submitted": still a draft.
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals("not") && (tokens.get(index + 1).equals("submitted")
                    || (index + 2 < tokens.size() && tokens.get(index + 1).equals("yet") && tokens.get(index + 2).equals("submitted")))) {
                return WaviTools.TimesheetStatusFilter.DRAFT.name();
            }
        }
        return null;
    }

    private static final List<String> DOCUMENT_NOUNS = List.of("document", "documents", "letter", "letters",
            "certificate", "certificates", "certification", "verification", "payslip", "payslips", "form", "forms");
    private static final List<String> PROFILE_NOUNS = List.of("profile", "phone", "address", "name", "email");

    /** The kind of the user's request from its subject: PROFILE_CHANGE, DOCUMENT_REQUEST, or null when unclear. */
    static String requestKindOf(String normalized) {
        if (normalized == null) return null;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        boolean document = tokens.stream().anyMatch(DOCUMENT_NOUNS::contains);
        boolean profile = tokens.stream().anyMatch(PROFILE_NOUNS::contains)
                || (tokens.contains("change") || tokens.contains("changes"));
        if (document == profile) return null;
        return document ? WaviTools.RequestListKind.DOCUMENT_REQUEST.name() : WaviTools.RequestListKind.PROFILE_CHANGE.name();
    }

    private static final List<String> STATUS_QUESTION_WORDS = List.of("status", "where", "update", "updates", "progress",
            "approved", "rejected", "pending", "open", "processed", "done", "ready", "happened", "stand", "stands");

    /** "Where is my ... request at", "any update on ...", "has my ... been processed": the state of a request. */
    static boolean asksRequestStatus(String normalized) {
        if (normalized == null) return false;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        return tokens.stream().anyMatch(STATUS_QUESTION_WORDS::contains);
    }

    /**
     * The decision the USER made as a reviewer ("what have I approved", "timesheets I rejected", "did I review"):
     * APPROVED, REJECTED or ANY; null when the user is not the one deciding ("my approved timesheets").
     */
    static String decisionByUser(String normalized) {
        if (normalized == null) return null;
        List<String> tokens = List.of(normalized.toLowerCase(Locale.ROOT).split("[^a-z]+"));
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (!tokens.get(index).equals("i")) continue;
            int verb = index + 1;
            if (List.of("have", "had", "did", "already", "recently", "just").contains(tokens.get(verb)) && verb + 1 < tokens.size()) verb++;
            String word = tokens.get(verb);
            // Past tense only: "I approved", "have I approved", "did I approve". "I review on Mondays" is not a decision.
            boolean didBefore = index > 0 && tokens.get(index - 1).equals("did");
            if (word.equals("approved") || (didBefore && word.equals("approve"))) return "APPROVED";
            if (word.equals("rejected") || (didBefore && word.equals("reject"))) return "REJECTED";
            if (word.equals("reviewed") || word.equals("processed") || (didBefore && word.equals("review"))) return "ANY";
        }
        return null;
    }

    /** Whether the follow-up asks for a reason ("why is it still pending?"). */
    static boolean asksWhy(String normalized) {
        return normalized != null && (normalized.strip().startsWith("why ") || normalized.strip().equals("why"));
    }

    /** The message with its first back-reference replaced by {@code subject}; null when it has none. */
    private static String replaceBackReference(String text, String subject) {
        String padded = " " + stripLeadingMarker(text) + " ";
        for (String reference : BACK_REFERENCES) {
            int at = padded.indexOf(" " + reference + " ");
            if (at >= 0) {
                return (padded.substring(0, at + 1) + subject + padded.substring(at + 1 + reference.length())).strip();
            }
        }
        return null;
    }

    /**
     * The new subject of an elliptical follow-up ("and profile changes", "what about my employee id too"), without
     * markers or a leading determiner; null unless the message opened with a marker and leaves a short phrase.
     */
    private static String bareSubject(String text) {
        String rest = stripLeadingMarker(text);
        if (rest.equals(text)) return null;
        for (String marker : TRAILING_MARKERS) {
            if (rest.endsWith(" " + marker)) {
                rest = rest.substring(0, rest.length() - marker.length() - 1).strip();
                break;
            }
        }
        for (String determiner : SUBJECT_DETERMINERS) {
            if (rest.startsWith(determiner)) {
                rest = rest.substring(determiner.length()).strip();
                break;
            }
        }
        if (rest.isEmpty() || words(rest) > MAX_SUBJECT_WORDS) return null;
        for (String reference : BACK_REFERENCES) {
            if ((" " + rest + " ").contains(" " + reference + " ")) return null;
        }
        return rest;
    }

    private static String stripLeadingMarker(String text) {
        for (String marker : LEADING_MARKERS) {
            if (text.startsWith(marker + " ")) return text.substring(marker.length() + 1).strip();
        }
        return text;
    }

    static int words(String text) {
        String trimmed = text.strip();
        return trimmed.isEmpty() ? 0 : trimmed.toLowerCase(Locale.ROOT).split("\\s+").length;
    }
}
