package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrKnowledgeStatus;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Builds policy replies from current authorized source sentences, never from generated factual claims. */
final class WaviPolicyEvidence {
    private static final int MAX_SENTENCES = 3;
    private static final int MAX_SENTENCE_CHARS = 650;
    private static final Pattern SOURCE_NUMBER = Pattern.compile(
            "(?i)(?:\\[\\s*(\\d{1,2})\\s*]|\\b(?:source|citation|reference)\\s*#?\\s*(\\d{1,2})\\b)");
    private static final Set<String> STOP = Set.of("a", "an", "the", "and", "or", "of", "to", "for", "in", "on",
            "by", "with", "from", "at", "is", "are", "was", "were", "be", "been", "do", "does", "did", "can",
            "could", "would", "should", "what", "which", "how", "many", "much", "when", "where", "why", "who",
            "i", "me", "my", "we", "our", "you", "your", "it", "this", "that", "about", "there", "have", "has",
            "please", "tell", "show", "give", "explain", "say", "says", "said", "policy", "policies", "company",
            "waypoint", "source", "citation", "reference", "previous", "earlier", "according", "document");

    private record Match(MyHrKnowledgeGrounding.Source source, String sentence, int score, int position) { }

    private WaviPolicyEvidence() { }

    static boolean isCitationFollowUp(String question) {
        Set<String> tokens = rawTerms(question);
        boolean citation = tokens.stream().anyMatch(Set.of("source", "sources", "citation", "citations", "reference",
                "references", "footnote", "footnotes", "cite", "cites", "cited", "citing", "quotation")::contains)
                || SOURCE_NUMBER.matcher(question == null ? "" : question).find();
        return citation && (tokens.size() <= 10 || tokens.stream().anyMatch(Set.of("previous", "earlier", "last", "above",
                "that", "this", "those", "you")::contains));
    }

    /** An explicit policy subject in this turn supersedes the subject of a previous citation. */
    static boolean hasExplicitPolicySubject(String question) {
        if (!MyHrKnowledgeGrounding.isPolicyQuestion(question)) return false;
        Set<String> meaningful = new LinkedHashSet<>(terms(question));
        meaningful.removeAll(Set.of("cite", "cit", "cited", "citing", "rule", "mean", "answer", "passage",
                "quote", "quoted", "document", "reference", "first", "second", "number"));
        return !meaningful.isEmpty();
    }

    static Integer sourceNumber(String question) {
        Matcher matcher = SOURCE_NUMBER.matcher(question == null ? "" : question);
        if (matcher.find()) return Integer.valueOf(matcher.group(1) == null ? matcher.group(2) : matcher.group(1));
        return WaviContinuation.ordinal(question == null ? "" : question.toLowerCase(Locale.ROOT));
    }

    /** Only a previously validated provider result reaches here; validation failures and refusals stay failures. */
    static MyHrKnowledgeGrounding.GroundedAnswer constrain(MyHrKnowledgeGrounding.Grounding grounding,
                                                           MyHrKnowledgeGrounding.GroundedAnswer answer,
                                                           String evidenceQuestion, String currentQuestion) {
        if (answer.status() != MyHrKnowledgeStatus.GROUNDED) return answer;
        if (!grounding.policyQuestion()) return withSampleNote(answer); // a cited answer from a sample document is labelled too
        MyHrKnowledgeGrounding.GroundedAnswer evidence = answer(grounding.sources(), evidenceQuestion, currentQuestion);
        if (evidence.status() != MyHrKnowledgeStatus.GROUNDED) return evidence;
        // The question is relevant to current authorized text. If the provider's own wording is a cited explanation
        // that the cited sources fully support, show IT (a plain-language answer); otherwise show the quoted evidence.
        String explanation = answer.cited().isEmpty() ? null : supportedExplanation(answer.message(), answer.cited());
        // It must also cover exactly the sources the evidence selection found relevant (so a second relevant, possibly
        // disagreeing, document is never left out and an unrelated one never added) and speak to what was asked.
        if (explanation != null && (!sameSources(evidence.cited(), answer.cited())
                || !addressesQuestion(explanation, evidenceQuestion))) explanation = null;
        if (explanation == null) return evidence;
        return new MyHrKnowledgeGrounding.GroundedAnswer(explanation + sampleNote(answer.cited()),
                MyHrKnowledgeStatus.GROUNDED, answer.cited());
    }

    // --- Plain-language explanation: shown only when every part of it is supported by the cited sources ------------------------

    private static final int MAX_EXPLANATION_CHARS = 1_200;
    /** A small allowance for words that frame an explanation without stating policy ("in simple terms"). */
    private static final Set<String> FRAMING = Set.of("simple", "simply", "basically", "essentially", "mean", "means",
            "word", "term", "short", "summary", "overall", "general", "typically", "usually", "also", "each", "every",
            "any", "some", "other", "new", "need", "ask", "get", "give", "make", "allow", "entitle", "receive", "take",
            "document", "approve", "policy", "company", "employee", "worker", "staff", "per", "include", "following",
            "key", "point", "main", "rule", "heres", "here", "above", "below", "example", "case", "may", "can", "full");
    /** Words that change what a rule means: each one in the explanation must also be in the cited text. */
    private static final Set<String> MEANING_WORDS = Set.of("no", "not", "never", "cannot", "must", "only", "except",
            "unless", "without", "least", "most", "maximum", "minimum", "required", "require", "requires", "prohibited",
            "forbidden", "optional", "always", "unlimited", "none", "mandatory");
    private static final Pattern MARKER = Pattern.compile("\\s*\\[(\\d{1,2})]");

    /**
     * The provider's explanation, cleaned, when it is brief, cites a source, and is fully supported: every number is in the
     * cited text, every meaning-changing word it uses (not, must, only, at least, up to ...) is in the cited text, and
     * at least four in five of its content words occur in the cited text. Otherwise null (the quoted evidence is shown).
     */
    static String supportedExplanation(String message, List<MyHrKnowledgeGrounding.Source> cited) {
        if (message == null || cited == null || cited.isEmpty()) return null;
        String text = message.replaceAll("[*_`#>|]+", " ").replaceAll("[ \\t]+", " ").strip();
        text = text.replaceAll(" ([.,;:!?])", "$1");
        if (text.isBlank() || text.length() > MAX_EXPLANATION_CHARS || !MARKER.matcher(text).find()) return null;
        // never two markers for the same source back to back, never a bare marker with no sentence
        text = text.replaceAll("(\\[\\d{1,2}])(\\s*\\1)+", "$1");
        String claims = MARKER.matcher(text).replaceAll(" ");
        StringBuilder sourceText = new StringBuilder();
        for (MyHrKnowledgeGrounding.Source source : cited) {
            sourceText.append(' ').append(source.title());
            for (String excerpt : source.excerpts()) sourceText.append(' ').append(excerpt);
        }
        Set<String> rawAllowed = rawTerms(sourceText.toString());
        Set<String> allowed = terms(sourceText.toString());
        for (String token : rawTerms(claims)) {
            if (token.chars().allMatch(Character::isDigit) && !rawAllowed.contains(token)) return null;
            if (MEANING_WORDS.contains(token) && !rawAllowed.contains(token)) return null;
        }
        Set<String> used = terms(claims);
        used.removeIf(FRAMING::contains);
        if (used.isEmpty()) return null;
        long unsupported = used.stream().filter(term -> !allowed.contains(term)).count();
        if (unsupported * 5 > used.size()) return null;
        // a sentence the model wrote with no marker anywhere in it would be uncited text: require every line to carry one
        for (String line : text.split("\\n+")) {
            String body = MARKER.matcher(line).replaceAll("").strip();
            if (body.length() > 25 && !MARKER.matcher(line).find() && !line.endsWith(":")) return null;
        }
        return text;
    }

    private static boolean sameSources(List<MyHrKnowledgeGrounding.Source> a, List<MyHrKnowledgeGrounding.Source> b) {
        return a.stream().map(MyHrKnowledgeGrounding.Source::number).collect(java.util.stream.Collectors.toSet())
                .equals(b.stream().map(MyHrKnowledgeGrounding.Source::number).collect(java.util.stream.Collectors.toSet()));
    }

    /** The explanation shares at least half (and at least one) of the question's content terms. */
    static boolean addressesQuestion(String explanation, String question) {
        Set<String> wanted = terms(question);
        wanted.removeIf(FRAMING::contains);
        if (wanted.isEmpty()) return true;
        Set<String> said = terms(MARKER.matcher(explanation == null ? "" : explanation).replaceAll(" "));
        long shared = wanted.stream().filter(said::contains).count();
        return shared >= 1 && shared * 2 >= wanted.size();
    }

    private static MyHrKnowledgeGrounding.GroundedAnswer withSampleNote(MyHrKnowledgeGrounding.GroundedAnswer answer) {
        String note = sampleNote(answer.cited());
        if (note.isEmpty() || answer.message() == null || answer.message().contains("Note: this document is marked")) return answer;
        return new MyHrKnowledgeGrounding.GroundedAnswer(answer.message() + note, answer.status(), answer.cited(),
                answer.unverifiedReason());
    }

    /** A document that describes itself as a demo, sample or fictional is never an official entitlement. */
    private static final Pattern SAMPLE = Pattern.compile(
            "(?i)\\b(demo|fictional|fictitious|sample (?:policy|data|document)|dummy|placeholder|"
                    + "for (?:testing|demonstration)(?: purposes)?|test (?:data|policy|organi[sz]ation|org)|rag test)\\b");

    static boolean isSample(MyHrKnowledgeGrounding.Source source) {
        if (SAMPLE.matcher(source.title() == null ? "" : source.title()).find()) return true;
        for (String excerpt : source.excerpts()) if (SAMPLE.matcher(excerpt).find()) return true;
        return false;
    }

    static String sampleNote(List<MyHrKnowledgeGrounding.Source> used) {
        return used.stream().anyMatch(WaviPolicyEvidence::isSample)
                ? "\n\nNote: this document is marked as a demo or fictional sample, so treat it as sample information, "
                + "not as an official employee entitlement." : "";
    }

    // --- Source text cleaning: one segment per line, no markdown, no metadata, no repeats --------------------------------------

    private static final Pattern METADATA_LINE = Pattern.compile(
            "(?i)^(organi[sz]ation|policy id|policy number|policy owner|owner|effective(?: date)?|version|approved(?: by| on| date)?|"
                    + "document id|last updated|status|department|classification|document type|prepared by|revision)\\s*:.*");

    /** The readable sentences of an excerpt; headings, metadata, markup and bare labels are dropped. */
    static List<String> readableSentences(String excerpt) {
        List<String> out = new ArrayList<>();
        if (excerpt == null) return out;
        for (String raw : excerpt.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.matches("^[*_]{2}[^*_]+[*_]{2}:?$")
                    || line.matches("^[-=_*|\\s]{3,}$")) continue;
            line = line.replaceAll("[*_`>|]+", " ").replaceFirst("^\\s*(?:[-\\u2022]|\\d+[.)])\\s+", "")
                    .replaceAll("\\s+", " ").strip();
            if (line.isEmpty() || METADATA_LINE.matcher(line).matches()) continue;
            BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.US);
            sentences.setText(line);
            int start = sentences.first();
            for (int end = sentences.next(); end != BreakIterator.DONE; start = end, end = sentences.next()) {
                String sentence = line.substring(start, end).strip();
                int words = sentence.split("\\s+").length;
                // a short LINE with no closing punctuation is a heading or label; a short sentence is kept
                if (words >= 4 || (words >= 3 && sentence.matches(".*\\d.*"))
                        || (words >= 2 && sentence.matches(".*[.!?]$"))) out.add(sentence);
            }
        }
        return out;
    }

    /** Sources have already passed current tenant, access-level and active-version checks. */
    static MyHrKnowledgeGrounding.GroundedAnswer answer(List<MyHrKnowledgeGrounding.Source> sources,
                                                        String evidenceQuestion, String currentQuestion) {
        Set<String> terms = terms(evidenceQuestion);
        List<Match> matches = new ArrayList<>();
        Set<String> seenSentences = new LinkedHashSet<>(); // a sentence in two overlapping excerpts is shown once
        for (MyHrKnowledgeGrounding.Source source : sources) {
            // The current turn's explicit subtype wins over an older policy topic in the retrieval query.
            if (MyHrKnowledgeGrounding.uncoveredLeaveType(currentQuestion, List.of(source)) != null) continue;
            int position = 0;
            for (String excerpt : source.excerpts()) {
                for (String sentence : readableSentences(excerpt)) {
                    if (sentence.length() > MAX_SENTENCE_CHARS) continue;
                    if (!seenSentences.add(sentence.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").strip())) continue;
                    Set<String> present = terms(sentence);
                    int score = (int) terms.stream().filter(present::contains).count();
                    if (score > 0) matches.add(new Match(source, sentence, score, position++));
                }
            }
        }
        if (matches.isEmpty()) {
            return new MyHrKnowledgeGrounding.GroundedAnswer(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER,
                    MyHrKnowledgeStatus.UNVERIFIED, List.of(), MyHrKnowledgeGrounding.UnverifiedReason.UNSUPPORTED_CLAIM);
        }
        matches.sort(Comparator.comparingInt(Match::score).reversed()
                .thenComparing(match -> match.source().title(), String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(Match::position));
        int best = matches.getFirst().score();
        // Include similarly relevant independent documents so an existing disagreement is not silently hidden.
        List<Match> selected = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Set<Integer> represented = new LinkedHashSet<>();
        for (int pass = 0; pass < 2 && selected.size() < MAX_SENTENCES; pass++) {
            for (Match match : matches) {
                if (match.score() < Math.max(1, (best + 1) / 2) || selected.size() == MAX_SENTENCES) break;
                if (pass == 0 && represented.contains(match.source().number())) continue;
                String key = match.source().number() + "\u0000" + match.sentence();
                if (seen.add(key)) {
                    selected.add(match);
                    represented.add(match.source().number());
                }
            }
        }
        Map<Integer, MyHrKnowledgeGrounding.Source> cited = new LinkedHashMap<>();
        StringBuilder text = new StringBuilder();
        if (applicationWorkflowRequested(currentQuestion)) {
            text.append("I can't verify that this workflow is available in Waypoint. The approved policy says: ");
        }
        for (Match match : selected) {
            if (!text.isEmpty() && text.charAt(text.length() - 1) != ' ') text.append(' ');
            text.append('“').append(match.sentence()).append("” [").append(match.source().number()).append(']');
            cited.putIfAbsent(match.source().number(), match.source());
        }
        return new MyHrKnowledgeGrounding.GroundedAnswer(text.toString() + sampleNote(List.copyOf(cited.values())),
                MyHrKnowledgeStatus.GROUNDED, List.copyOf(cited.values()));
    }

    static boolean applicationWorkflowRequested(String question) {
        Set<String> tokens = rawTerms(question);
        return tokens.stream().anyMatch(Set.of("waypoint", "app", "application", "system")::contains)
                && tokens.stream().anyMatch(Set.of("submit", "submitting", "file", "filing", "apply", "applying",
                        "create", "request", "requesting", "navigate", "update", "edit", "approve", "reject", "delete")::contains)
                && tokens.stream().anyMatch(Set.of("how", "where", "can", "could", "does", "possible")::contains);
    }

    /** A question about the app's available pages or records, rather than an organization's policy. */
    static boolean asksAboutApplicationAccess(String question) {
        Set<String> tokens = rawTerms(question);
        return tokens.stream().anyMatch(Set.of("waypoint", "app", "application", "system")::contains)
                && tokens.stream().anyMatch(Set.of("see", "view", "find", "access", "locate")::contains)
                && tokens.stream().anyMatch(Set.of("can", "could", "where", "how")::contains)
                && tokens.stream().noneMatch(Set.of("policy", "policies", "rules", "leave", "vacation", "pto")::contains);
    }

    private static Set<String> terms(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (String token : rawTerms(value)) {
            if (token.length() < 3 || STOP.contains(token) || token.chars().allMatch(Character::isDigit)) continue;
            if (token.equals("wfh")) token = "remote";
            if (token.length() > 5 && token.endsWith("ing")) token = token.substring(0, token.length() - 3);
            else if (token.length() > 5 && token.endsWith("ed")) token = token.substring(0, token.length() - 2);
            else if (token.length() > 6 && token.endsWith("ly")) token = token.substring(0, token.length() - 2);
            else if (token.length() > 4 && token.endsWith("s")) token = token.substring(0, token.length() - 1);
            if (token.length() > 3 && token.endsWith("i")) token = token.substring(0, token.length() - 1) + "y";
            result.add(token);
        }
        return result;
    }

    private static Set<String> rawTerms(String value) {
        Set<String> result = new LinkedHashSet<>();
        if (value != null) for (String token : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isBlank()) result.add(token);
        }
        return result;
    }
}
