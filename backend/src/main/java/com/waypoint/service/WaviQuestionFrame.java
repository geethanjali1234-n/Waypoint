package com.waypoint.service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The deterministic reading of one message that every capability decision starts from: WHAT KIND of answer is
 * requested (a person, a count, a status, a reason ...), WHICH business concepts the message names, WHOSE records
 * (the user's own, their review scope or their organization), which SELECTION of a result set (latest, approved ...)
 * and whether the message only makes sense as a follow-up to the previous answer.
 *
 * <p>It is grammar plus one concept lexicon, not a list of sentences: the answer type comes from closed-class words
 * (interrogatives, quantifiers, determiners, auxiliaries), concepts from a single table of business nouns and their
 * inflections. It never decides authorization, never names a person and never reads data: the verified actor, the
 * gated tool layer and the scoped services keep that authority. A model-based router may override it; without one,
 * it is what keeps unfamiliar wording answerable.
 */
record WaviQuestionFrame(AnswerType answer, Set<Concept> concepts, Scope scope, Selection selection,
                         NamePart namePart, boolean contextual, int words) {

    /** The shape of the answer the user asked for. */
    enum AnswerType {
        /** "did I", "is it", "are there": a direct yes or no first. */
        YES_NO,
        /** who / whom / whose: people. */
        PERSON,
        /** how many / number of: a count of records. */
        COUNT,
        /** how many employees / people: a count of distinct people. */
        PEOPLE_COUNT,
        /** what is the status / any update. */
        STATUS,
        /** which one / which record. */
        WHICH,
        /** why / how come / reason. */
        REASON,
        /** show / list / what are: the records themselves. */
        LIST,
        /** where can I see it: the page that holds it. */
        LOCATION,
        /** where did you find that / source: provenance of the previous answer. */
        SOURCE,
        /** how do I / steps: a supported workflow. */
        INSTRUCTION,
        /** all / everything / all of them: the complete form of the previous answer. */
        EXPAND,
        /** a value (name, hours, date ...). */
        DETAIL
    }

    enum Concept { TIMESHEET, HOURS, PROFILE, MANAGER, REQUEST, DOCUMENT_REQUEST, PROFILE_CHANGE, APPROVAL, PEOPLE,
        POLICY, KNOWLEDGE_BASE, ORGANIZATION, ACCESS }

    /** Whose records: the user's own, the records they review, their organization, or not said. */
    enum Scope { SELF, TEAM, ORGANIZATION, UNSPECIFIED }

    /** Which part of a result set: the latest submission, the newest by period, or one status. */
    enum Selection { NONE, LATEST_SUBMITTED, LATEST, APPROVED, REJECTED, PENDING, OPEN, COMPLETED }

    enum NamePart { NONE, FIRST, LAST, FULL }

    WaviQuestionFrame {
        concepts = concepts == null || concepts.isEmpty() ? Set.of() : Set.copyOf(concepts);
    }

    /** At most this many words for a message that is only a follow-up. */
    static final int FOLLOW_UP_WORDS = 9;

    // --- The concept lexicon: business nouns and their inflections, one table -------------------------------------

    private static final Map<Concept, Set<String>> LEXICON = Map.ofEntries(
            Map.entry(Concept.TIMESHEET, Set.of("timesheet", "timesheets", "timecard", "timecards", "sheet", "sheets",
                    "submission", "submissions")),
            Map.entry(Concept.HOURS, Set.of("hours", "hour", "hrs")),
            Map.entry(Concept.PROFILE, Set.of("profile", "information", "info", "details", "data", "record", "records")),
            Map.entry(Concept.MANAGER, Set.of("manager", "managers", "supervisor", "boss", "lead")),
            Map.entry(Concept.REQUEST, Set.of("request", "requests")),
            Map.entry(Concept.DOCUMENT_REQUEST, Set.of("document", "documents", "letter", "letters", "certificate")),
            Map.entry(Concept.PROFILE_CHANGE, Set.of("address", "phone", "change", "changes")),
            Map.entry(Concept.APPROVAL, Set.of("approval", "approvals", "approve", "review", "reviews", "awaiting",
                    "pending", "queue")),
            Map.entry(Concept.PEOPLE, Set.of("employee", "employees", "people", "person", "persons", "staff", "members",
                    "member", "team", "reports", "headcount", "everyone")),
            Map.entry(Concept.POLICY, Set.of("policy", "policies", "rule", "rules", "guideline", "guidelines")),
            Map.entry(Concept.KNOWLEDGE_BASE, Set.of("knowledge", "kb")),
            Map.entry(Concept.ORGANIZATION, Set.of("company", "organization", "organisation", "org", "companywide")),
            Map.entry(Concept.ACCESS, Set.of("access", "accessible", "visible", "see")));

    /** Words that only refer back to the previous answer. */
    private static final Set<String> BACK_REFERENCE = Set.of("it", "its", "them", "they", "those", "these", "that",
            "this", "one", "ones", "she", "he", "her", "him", "his", "their");
    /** The complete set, asked as a bare quantifier ("all", "everything please", "all of them"). */
    private static final Set<String> QUANTIFIER = Set.of("all", "everything", "entire", "complete", "full", "every");
    private static final Set<String> QUANTIFIER_FILLER = Set.of("the", "of", "them", "it", "details", "detail", "list",
            "please", "show", "me", "one", "ones", "yes", "give", "info", "information", "records", "items");
    private static final Set<String> FIRST_PERSON = Set.of("i", "me", "my", "mine", "myself", "i'm", "i've");
    /** Nouns that make "last" a period ("last week") rather than "the most recent". */
    private static final Set<String> PERIOD_NOUNS = Set.of("week", "weeks", "month", "months", "year", "years", "day",
            "days", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "time", "night",
            "quarter", "period", "pay", "payroll", "name");
    /** Verbs for handing a record in, besides "submit". */
    private static final Set<String> SUBMISSION_VERBS = Set.of("sent", "send", "filed", "file", "turned", "handed",
            "came", "uploaded");
    /** Negated ability: the user cannot do something (a how-to or problem), as opposed to a record's state. */
    private static final Set<String> ABILITY_NEGATIONS = Set.of("can't", "cant", "cannot", "couldn't", "unable", "won't");
    private static final Set<String> AUXILIARIES = Set.of("did", "do", "does", "is", "are", "was", "were", "has",
            "have", "had", "can", "could", "will", "would", "should", "am");

    static WaviQuestionFrame of(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT).replace('’', '\'').strip();
        List<String> tokens = new ArrayList<>();
        for (String token : text.split("[^a-z0-9']+")) {
            if (token.isBlank()) continue;
            // "manager's" -> "manager"; "team's" -> "team"
            tokens.add(token.endsWith("'s") ? token.substring(0, token.length() - 2) : token);
        }
        Set<Concept> concepts = EnumSet.noneOf(Concept.class);
        for (String token : tokens) {
            for (Map.Entry<Concept, Set<String>> entry : LEXICON.entrySet()) {
                if (entry.getValue().contains(token)) concepts.add(entry.getKey());
            }
        }
        if (bigram(tokens, "report", "to") || bigram(tokens, "reports", "to") || bigram(tokens, "reporting", "manager")) {
            concepts.add(Concept.MANAGER);
        }
        if (bigram(tokens, "knowledge", "base")) concepts.add(Concept.KNOWLEDGE_BASE);
        // "What does Waypoint know / keep / hold about me": the user's own record, named by a verb, not a noun.
        boolean aboutMe = bigram(tokens, "about", "me") || bigram(tokens, "on", "me") || bigram(tokens, "of", "me");
        if (aboutMe && tokens.stream().anyMatch(Set.of("know", "knows", "store", "stores", "keep", "keeps", "hold",
                "holds", "have", "has", "recorded", "saved")::contains)) concepts.add(Concept.PROFILE);
        // "see" names access only next to policy wording; otherwise it is an ordinary verb.
        if (!concepts.contains(Concept.POLICY) && !tokens.contains("access") && !tokens.contains("accessible")
                && !tokens.contains("visible")) concepts.remove(Concept.ACCESS);
        // "request" with a profile detail is a profile change; with a document word a document request.
        if (concepts.contains(Concept.PROFILE_CHANGE) && !concepts.contains(Concept.REQUEST)
                && !tokens.contains("change") && !tokens.contains("changes")) concepts.remove(Concept.PROFILE_CHANGE);

        NamePart namePart = NamePart.NONE;
        if (bigram(tokens, "first", "name") || tokens.contains("firstname")) namePart = NamePart.FIRST;
        else if (bigram(tokens, "last", "name") || tokens.contains("surname") || bigram(tokens, "family", "name")
                || tokens.contains("lastname")) namePart = NamePart.LAST;
        else if (bigram(tokens, "full", "name")) namePart = NamePart.FULL;

        Selection selection = selection(tokens);
        Scope scope = scope(tokens, concepts);
        AnswerType answer = answerType(text, tokens, concepts);
        Set<Concept> named = EnumSet.copyOf(concepts.isEmpty() ? EnumSet.noneOf(Concept.class) : concepts);
        named.remove(Concept.APPROVAL);
        named.remove(Concept.ACCESS);
        boolean refers = tokens.stream().anyMatch(BACK_REFERENCE::contains);
        boolean contextual = tokens.size() <= FOLLOW_UP_WORDS && (named.isEmpty() || (refers && named.size() <= 1
                && !concepts.contains(Concept.POLICY) && namePart == NamePart.NONE && answer != AnswerType.PEOPLE_COUNT));
        // A follow-up's own business noun outranks a pronoun: "which timesheets were approved?" is self-contained.
        if (contextual && !named.isEmpty() && !refers) contextual = false;
        return new WaviQuestionFrame(answer, concepts, scope, selection, namePart, contextual, tokens.size());
    }

    private static AnswerType answerType(String text, List<String> tokens, Set<Concept> concepts) {
        if (tokens.isEmpty()) return AnswerType.DETAIL;
        String first = tokens.getFirst();
        if (isExpansion(tokens)) return AnswerType.EXPAND;
        if (WaviPolicyEvidence.isCitationFollowUp(text) || asksProvenance(tokens)) return AnswerType.SOURCE;
        if (first.equals("why") || bigram(tokens, "how", "come") || tokens.contains("reason")
                || bigram(tokens, "what", "for") && tokens.size() <= 3) {
            // "Why can't I upload ...?": an ability problem (how to do it), not the reason for a record's status.
            return tokens.stream().anyMatch(ABILITY_NEGATIONS::contains) ? AnswerType.INSTRUCTION : AnswerType.REASON;
        }
        if (first.equals("where") || bigram(tokens, "which", "page")) return AnswerType.LOCATION;
        if (WaviContinuation.isHowTo(text) || tokens.contains("steps")) return AnswerType.INSTRUCTION;
        if (WaviContinuation.asksCount(text)) {
            int many = tokens.indexOf("many");
            String counted = many >= 0 && many + 1 < tokens.size() ? tokens.get(many + 1) : "";
            if (Set.of("employees", "people", "persons", "staff", "members", "reports", "users", "colleagues")
                    .contains(counted)) return AnswerType.PEOPLE_COUNT;
            return AnswerType.COUNT;
        }
        if (WaviContinuation.asksForPeople(text)) return AnswerType.PERSON;
        if (first.equals("which") || bigram(tokens, "what", "one")) return AnswerType.WHICH;
        if (Set.of("show", "list", "display", "give", "see", "view").contains(first)
                || bigram(tokens, "what", "are") || bigram(tokens, "what's", "pending") || bigram(tokens, "what", "is")
                && tokens.contains("pending")) return AnswerType.LIST;
        if (AUXILIARIES.contains(first) || bigram(tokens, "any", "update") || first.equals("any")) {
            return tokens.contains("status") ? AnswerType.STATUS : AnswerType.YES_NO;
        }
        if (tokens.contains("status") || tokens.contains("update") || tokens.contains("progress")) return AnswerType.STATUS;
        return AnswerType.DETAIL;
    }

    /** "where did you find/get that", "where does that come from", "what is that based on". */
    private static boolean asksProvenance(List<String> tokens) {
        boolean where = tokens.contains("where") || bigram(tokens, "based", "on");
        boolean origin = tokens.stream().anyMatch(Set.of("find", "found", "get", "got", "come", "comes", "from",
                "based", "learn", "read")::contains);
        boolean you = tokens.contains("you") || tokens.contains("that") || tokens.contains("this")
                || tokens.contains("information") || tokens.contains("answer");
        return where && origin && you && !tokens.contains("can") && !FIRST_PERSON.stream().anyMatch(tokens::contains);
    }

    private static boolean isExpansion(List<String> tokens) {
        return tokens.stream().anyMatch(QUANTIFIER::contains) && tokens.stream()
                .allMatch(token -> QUANTIFIER.contains(token) || QUANTIFIER_FILLER.contains(token));
    }

    private static Selection selection(List<String> tokens) {
        // "last" is a superlative ("which did I send in last") unless it starts a period ("last week").
        int last = tokens.lastIndexOf("last");
        boolean lastSuperlative = last >= 0 && (last + 1 == tokens.size() || !PERIOD_NOUNS.contains(tokens.get(last + 1)));
        boolean latest = tokens.contains("latest") || tokens.contains("newest") || tokens.contains("recently")
                || bigram(tokens, "most", "recent") || lastSuperlative;
        if (latest) {
            return tokens.stream().anyMatch(token -> token.startsWith("submi") || SUBMISSION_VERBS.contains(token))
                    ? Selection.LATEST_SUBMITTED : Selection.LATEST;
        }
        if (tokens.contains("approved")) return Selection.APPROVED;
        if (tokens.contains("rejected") || tokens.contains("returned")) return Selection.REJECTED;
        // "open" is a status only as an adjective ("which requests are open"), never the imperative "open the page".
        int open = tokens.indexOf("open");
        boolean openAdjective = open > 0 && !Set.of("to", "please", "you", "can", "and").contains(tokens.get(open - 1));
        if (openAdjective || tokens.contains("outstanding")) return Selection.OPEN;
        // Not "done": "need something done" is a request for action, not a completed status.
        if (tokens.contains("completed") || tokens.contains("fulfilled") || tokens.contains("finished")) {
            return Selection.COMPLETED;
        }
        if (tokens.contains("pending") || tokens.contains("waiting") || tokens.contains("awaiting")) return Selection.PENDING;
        return Selection.NONE;
    }

    private static Scope scope(List<String> tokens, Set<Concept> concepts) {
        boolean reviewWork = bigram(tokens, "my", "team") || bigram(tokens, "my", "approval")
                || bigram(tokens, "my", "approvals") || bigram(tokens, "my", "review") || bigram(tokens, "my", "reports")
                || bigram(tokens, "direct", "reports") || bigram(tokens, "for", "me") && concepts.contains(Concept.APPROVAL)
                || bigram(tokens, "on", "me") && (concepts.contains(Concept.APPROVAL) || tokens.contains("waiting"))
                || tokens.contains("team") || bigram(tokens, "need", "my")
                || bigram(tokens, "needs", "my") || bigram(tokens, "me", "to") && concepts.contains(Concept.APPROVAL);
        if (reviewWork) return Scope.TEAM;
        if (concepts.contains(Concept.ORGANIZATION) || tokens.contains("across") || tokens.contains("everyone")) {
            return Scope.ORGANIZATION;
        }
        if (tokens.stream().anyMatch(FIRST_PERSON::contains)) return Scope.SELF;
        return Scope.UNSPECIFIED;
    }

    private static boolean bigram(List<String> tokens, String first, String second) {
        for (int index = 0; index + 1 < tokens.size(); index++) {
            if (tokens.get(index).equals(first) && tokens.get(index + 1).equals(second)) return true;
        }
        return false;
    }

    /**
     * The message with possessives removed from business nouns only ("my team's", "the company's", "manager's"), so
     * a check for a named third person is not triggered by a group or role word. A person's possessive ("Ravi's")
     * is kept.
     */
    static String withoutConceptPossessives(String message) {
        if (message == null) return null;
        StringBuilder out = new StringBuilder();
        for (String token : message.replace('’', '\'').split(" ", -1)) {
            String bare = token.replaceAll("[^A-Za-z']", "");
            String lower = bare.toLowerCase(Locale.ROOT);
            if (lower.endsWith("'s") && isConceptWord(lower.substring(0, lower.length() - 2))) {
                token = token.replace(bare, bare.substring(0, bare.length() - 2));
            }
            if (!out.isEmpty()) out.append(' ');
            out.append(token);
        }
        return out.toString();
    }

    private static boolean isConceptWord(String word) {
        for (Set<String> words : LEXICON.values()) {
            if (words.contains(word)) return true;
        }
        return false;
    }

    boolean has(Concept concept) {
        return concepts.contains(concept);
    }

    /** Names none of the record concepts: only a follow-up, or a question about the user themselves. */
    boolean namesNoRecord() {
        return concepts.stream().noneMatch(concept -> concept == Concept.TIMESHEET || concept == Concept.HOURS
                || concept == Concept.REQUEST || concept == Concept.DOCUMENT_REQUEST || concept == Concept.PROFILE_CHANGE
                || concept == Concept.POLICY || concept == Concept.PEOPLE);
    }
}
