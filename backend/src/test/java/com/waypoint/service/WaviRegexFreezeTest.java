package com.waypoint.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase P0 freeze: Wavi's question understanding must stop growing one phrase at a time. New wording, languages and
 * typos belong to the semantic router (Phase P3), not to new regular expressions, alternatives or vocabulary words in
 * the classes below. Each file's rule counts are pinned exactly:
 * <ul>
 *   <li>{@code patterns}: {@code Pattern.compile(...)} calls plus {@code rewrite("...")} rules;</li>
 *   <li>{@code alternatives}: {@code |} characters inside string literals (each one is another accepted wording);</li>
 *   <li>{@code vocabulary}: string literals inside {@code static final Set<String>/List<String> = Set.of/List.of(...)}.</li>
 * </ul>
 * A count above its ceiling fails: remove the new rule, or get the freeze lifted explicitly. A count below its
 * ceiling also fails, so the ceiling is lowered in the same change and a retired rule cannot quietly come back.
 * Reply text and Java logic are not counted, so ordinary fixes stay free. See docs/wavi/p0-baseline.md.
 */
class WaviRegexFreezeTest {

    record Counts(int patterns, int alternatives, int vocabulary) {
    }

    /** Recorded 2026-11-09 from the working tree (b657c91 plus the uncommitted 2026-11-07 routing fixes). */
    private static final Map<String, Counts> CEILINGS = new LinkedHashMap<>();

    static {
        CEILINGS.put("MyHrChatService.java", new Counts(91, 587, 0)); // +2 patterns: approved HR-contact, express-clearance, payroll-readiness and phone-pending workflow rules
        CEILINGS.put("MyHrQuestionInterpreter.java", new Counts(97, 902, 162));
        CEILINGS.put("MyHrConversationIntents.java", new Counts(27, 496, 28));
        CEILINGS.put("MyHrOwnRecordIntent.java", new Counts(27, 391, 0));
        CEILINGS.put("MyHrScopeGuard.java", new Counts(15, 955, 0));
        CEILINGS.put("MyHrKnowledgeGrounding.java", new Counts(31, 225, 73));
    }

    @Test
    void questionUnderstandingRulesAreFrozen() throws IOException {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Counts> entry : CEILINGS.entrySet()) {
            Counts actual = count(Files.readString(source(entry.getKey())));
            if (!actual.equals(entry.getValue())) {
                changed.add(entry.getKey() + ": ceiling " + entry.getValue() + " but found " + actual);
            }
        }
        assertThat(changed)
                .as("Wavi phrase rules are frozen (Phase P0). Above a ceiling: route the new wording through the "
                        + "semantic router instead of a new regex. Below a ceiling: lower it to the new count.")
                .isEmpty();
    }

    @Test
    void theCounterSeesRulesAndIgnoresCommentsAndReplyText() {
        String sample = """
                // Pattern.compile("a|b") in a comment is ignored
                private static final Pattern A = Pattern.compile("^(?:hi|hello|hey)$");
                private static final List<Rewrite> R = List.of(rewrite("\\\\bpay ?roll\\\\b", m -> "payroll"));
                private static final Set<String> WORDS = Set.of("one", "two", "three");
                private static final List<String> MORE = List.of(
                        "four", /* "not counted" */ "five");
                String reply = "Use the Timesheets button below.";
                boolean either = a || b;
                """;
        assertThat(count(sample)).isEqualTo(new Counts(2, 2, 5));
    }

    // --- Counting ------------------------------------------------------------------------------------------------

    private static final Pattern VOCABULARY_DECLARATION = Pattern.compile(
            "static\\s+final\\s+(?:Set|List)<String>\\s+\\w+\\s*=\\s*(?:Set|List)\\.of\\(");
    private static final Pattern PATTERN_CALL = Pattern.compile("\\bPattern\\.compile\\(");
    private static final Pattern REWRITE_RULE = Pattern.compile("\\brewrite\\(\\s*\"");

    static Counts count(String source) {
        // Blank comments and the contents of string literals, keeping positions, and collect the literals.
        StringBuilder code = new StringBuilder(source.length());
        List<int[]> literals = new ArrayList<>();
        int alternatives = 0;
        int index = 0;
        while (index < source.length()) {
            char c = source.charAt(index);
            if (source.startsWith("//", index)) {
                int end = source.indexOf('\n', index);
                end = end < 0 ? source.length() : end;
                code.append(" ".repeat(end - index));
                index = end;
            } else if (source.startsWith("/*", index)) {
                int end = source.indexOf("*/", index + 2);
                end = end < 0 ? source.length() : end + 2;
                code.append(source.substring(index, end).replaceAll("[^\\n]", " "));
                index = end;
            } else if (source.startsWith("\"\"\"", index)) {
                int end = source.indexOf("\"\"\"", index + 3);
                end = end < 0 ? source.length() : end + 3;
                alternatives += pipes(source.substring(index, end));
                literals.add(new int[]{index, end});
                code.append('"').append(source.substring(index + 1, end - 1).replaceAll("[^\\n]", " ")).append('"');
                index = end;
            } else if (c == '"' || c == '\'') {
                int end = index + 1;
                while (end < source.length() && source.charAt(end) != c) {
                    end += source.charAt(end) == '\\' ? 2 : 1;
                }
                end = Math.min(end + 1, source.length());
                if (c == '"') {
                    alternatives += pipes(source.substring(index, end));
                    literals.add(new int[]{index, end});
                }
                code.append(c).append(" ".repeat(Math.max(0, end - index - 2))).append(end - index >= 2 ? c : "");
                index = end;
            } else {
                code.append(c);
                index++;
            }
        }
        String blanked = code.toString();
        int patterns = occurrences(PATTERN_CALL, blanked) + occurrences(REWRITE_RULE, blanked);
        int vocabulary = 0;
        Matcher declaration = VOCABULARY_DECLARATION.matcher(blanked);
        while (declaration.find()) {
            int end = closingParenthesis(blanked, declaration.end() - 1);
            for (int[] literal : literals) {
                if (literal[0] > declaration.end() - 1 && literal[1] <= end) vocabulary++;
            }
        }
        return new Counts(patterns, alternatives, vocabulary);
    }

    private static int pipes(String literal) {
        return (int) literal.chars().filter(ch -> ch == '|').count();
    }

    private static int occurrences(Pattern pattern, String text) {
        int found = 0;
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) found++;
        return found;
    }

    private static int closingParenthesis(String code, int open) {
        int depth = 0;
        for (int index = open; index < code.length(); index++) {
            char c = code.charAt(index);
            if (c == '(') depth++;
            if (c == ')' && --depth == 0) return index;
        }
        return code.length();
    }

    private static Path source(String file) {
        for (Path root : List.of(Path.of("src/main/java/com/waypoint/service"),
                Path.of("backend/src/main/java/com/waypoint/service"))) {
            if (Files.isRegularFile(root.resolve(file))) return root.resolve(file);
        }
        throw new AssertionError("cannot find " + file + " from " + Path.of("").toAbsolutePath());
    }
}
