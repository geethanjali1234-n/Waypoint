package com.waypoint.service;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Server-only pending question. A short answer fills slots; it never supplies an identity or permission. */
record WaviClarification(String question, String subject, boolean submission, boolean date, boolean own) {
    static WaviClarification from(String question, java.util.Map<String, Object> arguments) {
        WaviClarification inferred = from(question);
        String subject = inferred.subject == null && "TIMESHEETS".equals(arguments.get("topic")) ? "timesheets" : inferred.subject;
        return new WaviClarification(inferred.question, subject, inferred.submission, inferred.date,
                inferred.own && !"SCOPE".equals(arguments.get("missing")));
    }

    static String prompt(java.util.Map<String, Object> arguments, com.waypoint.enums.UserRole role) {
        var topic = java.util.Arrays.stream(WaviTools.ClarificationTopic.values()).filter(v -> v.name().equals(arguments.get("topic"))).findFirst().orElse(null);
        var missing = java.util.Arrays.stream(WaviTools.MissingSlot.values()).filter(v -> v.name().equals(arguments.get("missing"))).findFirst().orElse(null);
        return prompt(topic, missing, role);
    }
    static String prompt(WaviTools.ClarificationTopic topic, WaviTools.MissingSlot missing, com.waypoint.enums.UserRole role) {
        if (missing == WaviTools.MissingSlot.PERIOD) return "Which date, week or month do you mean?";
        if (missing == WaviTools.MissingSlot.PERSON) return "Which person in your authorized scope do you mean?";
        if (topic == WaviTools.ClarificationTopic.TIMESHEETS) {
            return role == com.waypoint.enums.UserRole.EMPLOYEE ? "Do you mean your latest timesheet or a particular date or period?"
                    : "Do you mean your own timesheets or submissions assigned for your review?";
        }
        if (topic == WaviTools.ClarificationTopic.REQUESTS) {
            if (role == com.waypoint.enums.UserRole.EMPLOYEE) return "Do you mean your profile change requests or your document requests?";
            return missing == WaviTools.MissingSlot.SUBJECT
                    ? "Which requests do you mean: profile change requests or document requests?"
                    : "Do you mean requests waiting for your review, or your own profile change or document requests?";
        }
        if (topic == WaviTools.ClarificationTopic.WORK) return "Do you mean your recorded job information, pending tasks, or completed work?";
        if (topic == WaviTools.ClarificationTopic.PEOPLE) return "Do you want a person count, a list, or one person's permitted work details?";
        return WaviRouterPolicyGate.clarification(role);
    }
    /** Closed fallback grammar for work identity, excluding tasks, policies, records and other people. */
    static boolean workIdentityQuestion(String question) {
        Set<String> words = words(question);
        Set<String> grammar = Set.of("what", "which", "is", "are", "my", "own", "work", "job", "role",
                "responsibility", "responsibilities", "duties", "assigned", "do", "i", "tell", "me", "about", "the");
        return grammar.containsAll(words) && (words.contains("my") || words.contains("i"))
                && words.stream().anyMatch(w -> Set.of("work", "job", "responsibility", "responsibilities", "duties").contains(w));
    }
    static boolean requestsReviewScope(String question) {
        Set<String> words = words(question);
        return words.size() <= 12 && words.contains("my")
                && (words.contains("approval") || words.contains("review"))
                && (words.contains("awaiting") || words.contains("waiting") || words.contains("pending"))
                && (words.contains("ones") || words.contains("those") || words.contains("timesheets"));
    }

    /** A request for the signed-in person's general profile, without selecting another business record. */
    static boolean asksForOwnProfileOverview(String question) {
        // The general interpreter strips courtesy endings such as "for me"; ownership must use the actual turn.
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        Set<String> terms = Arrays.stream(normalized.split("[^a-z]+"))
                .filter(word -> !word.isBlank()).collect(Collectors.toSet());
        boolean self = terms.contains("my") || terms.contains("mine") || terms.contains("myself") || terms.contains("i")
                || normalized.matches(".*\\b(?:about|for|on|of|regarding) me\\b.*");
        if (!self || WaviContinuation.namesThirdParty(question) || WaviContinuation.hasNameShapedToken(question)
                || WaviContinuation.refersToPerson(question) || WaviContinuation.namesOtherOrganization(question)
                || MyHrScopeGuard.isWritingTask(question) || WaviContinuation.isHowTo(question)) return false;
        if (terms.stream().anyMatch(w -> Set.of("timesheet", "timesheets", "request", "requests", "approval",
                "status", "policy", "policies", "change", "update", "delete", "salary", "pay", "balance",
                "manager", "supervisor", "other", "another", "their", "his", "her", "timestamp", "date",
                "submitted", "submission", "filed", "recent", "recently", "latest", "phone", "address",
                "email", "title", "department", "id", "changes", "updates", "approve", "approved", "reject",
                "rejected", "review", "reviewed", "pending", "awaiting", "open", "report", "reports",
                "reportee", "time", "page", "screen", "tab").contains(w))) return false;
        boolean record = terms.stream().anyMatch(w -> Set.of("profile", "information", "info", "details", "data",
                "record", "records").contains(w));
        boolean lookup = terms.stream().anyMatch(w -> Set.of("what", "which", "show", "list", "tell", "give",
                "display", "provide", "summarize", "summary", "see", "have", "has", "hold", "holds", "store",
                "stores", "keep", "keeps", "know", "knows").contains(w));
        return record && lookup;
    }

    /** A quantity-only continuation is interpreted only against a verified profile or pending profile question. */
    static boolean requestsCompleteOverview(String reply) {
        Set<String> terms = words(reply);
        return !terms.isEmpty() && terms.stream().anyMatch(w -> Set.of("all", "everything", "entire", "complete").contains(w))
                && Set.of("all", "everything", "entire", "complete", "the", "of", "it", "them", "details",
                        "information", "info", "profile", "record", "records", "please", "show", "me").containsAll(terms);
    }
    static boolean needsRecord(String question) {
        Set<String> words = words(question);
        return subject(words) == null && (words.contains("my") || words.contains("i"))
                && (words.contains("submission") || words.contains("request") || words.contains("application"))
                && words.stream().anyMatch(w -> Set.of("approved", "approve", "rejected", "status", "processed", "reviewed").contains(w))
                && words.stream().noneMatch(w -> Set.of("policy", "should", "how", "can", "please").contains(w));
    }
    static WaviClarification from(String question) {
        Set<String> words = words(question);
        return new WaviClarification(WaviRouterPrivacy.sanitize(question, 1000), subject(words),
                words.stream().anyMatch(w -> w.startsWith("submit") || w.equals("submission")),
                words.contains("when") || words.contains("date"), words.contains("my") || words.contains("i"));
    }

    String aspect() {
        Set<String> original = words(question);
        boolean approval = original.contains("approved") || original.contains("approve");
        boolean rejection = original.contains("rejected") || original.contains("reject");
        if (date && approval) return "APPROVED_DATE";
        if (date && rejection) return "REVIEW_DATE";
        if (original.contains("why") && rejection) return "REJECTION_REASON";
        return date ? "SUBMISSION_DATE" : "STATUS";
    }

    /** Null means the reply isn't a slot answer; a new question must stand on its own. */
    String resolve(String reply) {
        Set<String> words = words(reply);
        if (!isSlotAnswer(reply)) return null;
        if (words.contains("no") && subject(words) == null) return null;
        WaviClarification filled = merge(reply);
        String selected = filled.subject;
        boolean review = words.contains("approval") || words.contains("review");
        // A clarified question about "requests": approving one kind, or the requests waiting for the user's review.
        Set<String> original = words(question);
        if (original.contains("request") || original.contains("requests")) {
            if (original.contains("how") && original.contains("approve") && selected != null) {
                return "how do I approve " + (selected.equals("timesheets") ? selected : selected + "s");
            }
            if (selected == null && review) return "how many requests are waiting for my approval";
        }
        if (selected == null) return null;
        if (review) return "which " + selected + " are waiting for my approval";
        if (!filled.own) return null;
        if (aspect().equals("APPROVED_DATE")) return "when was my latest " + selected + " approved";
        if (aspect().equals("REVIEW_DATE")) return null; // No supported generic rejection timestamp lookup.
        if (aspect().equals("REJECTION_REASON")) return "why was my latest " + selected + " rejected";
        if (selected.equals("timesheets") && submission) return "when did I last submit my timesheet";
        return (date ? "when did I submit my " : "what is the status of my latest ") + selected;
    }

    WaviClarification merge(String reply) {
        Set<String> words = words(reply);
        String selected = subject(words);
        return new WaviClarification(question, selected == null ? subject : selected, submission, date,
                own || words.contains("my") || words.contains("own") || words.contains("mine"));
    }

    boolean isSlotAnswer(String reply) {
        Set<String> words = words(reply);
        Set<String> vocabulary = Set.of("yes", "no", "the", "my", "own", "mine", "ones", "one", "a", "i", "mean",
                "meant", "please", "timesheet", "timesheets", "time", "sheet", "sheets", "profile", "change",
                "changes", "request", "requests", "document", "documents", "for", "awaiting", "approval", "review",
                "waiting", "pending");
        return !words.isEmpty() && vocabulary.containsAll(words);
    }

    private static String subject(Set<String> words) {
        if (words.contains("timesheet") || words.contains("timesheets") || words.contains("sheet") || words.contains("sheets")) return "timesheets";
        if (words.contains("profile") || words.contains("changes")) return "profile change request";
        if (words.contains("document") || words.contains("documents")) return "document request";
        return null;
    }

    private static Set<String> words(String value) {
        return Arrays.stream(MyHrQuestionInterpreter.canonicalize(value).toLowerCase(Locale.ROOT).split("[^a-z]+"))
                .filter(w -> !w.isBlank()).collect(Collectors.toSet());
    }
}
