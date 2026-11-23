package com.waypoint.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Independent adversarial evaluation on the second synthetic organization ({@link WaviNorthwindData}): a development
 * group that may drive systemic fixes, and a holdout group sealed before its first run and only ever measured.
 * Writes metrics and a per-failure execution trace (route, data calls, executed tool and canonical arguments, reply,
 * failed checks) to target/wavi-eval/adversarial-*.md. Only authorization is a hard gate here: accuracy is reported.
 *
 * <p>Offline this measures the DETERMINISTIC path (semantic routing off, and on with an abstaining router, which must
 * score the same). Normal-mode routing by a real model is not measured here; it needs an approved live run.
 */
class WaviAdversarialEvaluationTest {

    static final List<String> GROUPS = List.of("adversarial-dev", "adversarial-holdout");

    record Metrics(String group, String mode, int conversations, int turns, long correct, Map<String, long[]> rates,
                   long authorizationFailures, List<String> traces) { }

    @Test
    void adversarialGroupsAreMeasuredWithTracesAndNeverBreakAuthorization() throws Exception {
        assertThat(WaviChatbotEvaluationTest.sha256OfNormalized("/wavi-eval/adversarial-holdout.json"))
                .as("adversarial-holdout.json was edited after it was sealed")
                .isEqualTo(WaviChatbotEvaluationTest.sealedHash("/wavi-eval/adversarial-holdout.seal"));
        StringBuilder summary = new StringBuilder("# Adversarial evaluation (Northwind)\n\n");
        long authorization = 0;
        for (String group : GROUPS) {
            Metrics fallback = measure(group, "deterministic fallback (routing off)", options -> {
                options.northwind = true;
                options.toolLayer = true;
            });
            Metrics abstaining = measure(group, "routing on, router abstains", options -> {
                options.northwind = true;
                options.toolLayer = true;
                options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN;
            });
            for (Metrics metrics : List.of(fallback, abstaining)) {
                summary.append(render(metrics));
                authorization += metrics.authorizationFailures();
            }
            writeTraces(fallback);
        }
        Path directory = Path.of("target", "wavi-eval");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("adversarial-summary.md"), summary.toString());
        System.out.println(summary);
        assertThat(authorization).as("authorization failures on the adversarial groups").isZero();
    }

    static Metrics measure(String group, String mode, java.util.function.Consumer<WaviEvaluationWorld.Options> adjust)
            throws IOException {
        List<WaviChatbotEvaluationTest.Result> results = new WaviChatbotEvaluationTest()
                .evaluate("/wavi-eval/" + group + ".json", adjust);
        Map<String, long[]> rates = new LinkedHashMap<>();
        rate(rates, "final answer (all turns)", results, result -> true);
        rate(rates, "record selection", results, result -> result.category().equals("record-selection"));
        rate(rates, "filtering and aggregation", results, result -> result.category().equals("filtering-aggregation"));
        rate(rates, "status and workflow semantics", results, result -> result.category().equals("status-semantics"));
        rate(rates, "multi-turn continuation (follow-up turns)", results,
                result -> result.scenario().matches(".*#[2-9]$"));
        rate(rates, "clarification resolution (scenarios)", results, result -> result.category().equals("clarification"));
        rate(rates, "requests (documents, profile changes)", results, result -> result.category().equals("requests"));
        rate(rates, "policy retrieval", results, result -> result.category().equals("policy"));
        rate(rates, "navigation and workflow guidance", results, result -> result.category().equals("navigation-workflow"));
        rate(rates, "unauthorized and out-of-scope requests", results, result -> result.category().equals("authorization"));
        for (String role : List.of("EMPLOYEE", "HR", "COMPANY_ADMIN")) {
            rate(rates, "role " + role, results, result -> result.role().equals(role));
        }
        long authorizationFailures = results.stream().filter(result -> !result.failures().get("authorization").isEmpty())
                .count();
        List<String> traces = new ArrayList<>();
        for (WaviChatbotEvaluationTest.Result result : results) {
            if (result.passed()) continue;
            Map<String, List<String>> failed = new TreeMap<>();
            result.failures().forEach((dimension, list) -> { if (!list.isEmpty()) failed.put(dimension, list); });
            traces.add("### " + result.scenario() + " [" + result.role() + "] (" + result.category() + ")\n"
                    + "- question: `" + result.message() + "`\n"
                    + "- route: " + result.route() + "; tool: " + (result.selectedTool() == null ? "none (legacy route)"
                    : result.selectedTool()) + " " + new TreeMap<>(result.canonicalArguments())
                    + "; executed: " + result.toolExecuted() + "\n"
                    + "- data calls: " + result.calls() + "\n"
                    + "- reply: " + flatten(result.reply()) + "\n"
                    + "- failed checks: " + failed + "\n");
        }
        long conversations = results.stream().map(result -> result.scenario().split("#")[0] + "|" + result.role())
                .distinct().count();
        return new Metrics(group, mode, (int) conversations, results.size(),
                results.stream().filter(WaviChatbotEvaluationTest.Result::passed).count(), rates, authorizationFailures,
                traces);
    }

    private static void rate(Map<String, long[]> rates, String name, List<WaviChatbotEvaluationTest.Result> results,
                             Predicate<WaviChatbotEvaluationTest.Result> selected) {
        long[] counts = new long[2];
        for (WaviChatbotEvaluationTest.Result result : results) {
            if (!selected.test(result)) continue;
            counts[1]++;
            if (result.passed()) counts[0]++;
        }
        if (counts[1] > 0) rates.put(name, counts);
    }

    static String render(Metrics metrics) {
        StringBuilder out = new StringBuilder("## ").append(metrics.group()).append(" - ").append(metrics.mode())
                .append("\n\nConversations (scenario x role): ").append(metrics.conversations()).append("; turns: ")
                .append(metrics.turns()).append("; fully correct turns: ").append(metrics.correct()).append(" (")
                .append(percent(metrics.correct(), metrics.turns())).append("); authorization failures: ")
                .append(metrics.authorizationFailures()).append("\n\n| Measure | Correct | Total | Rate |\n|---|---|---|---|\n");
        metrics.rates().forEach((name, counts) -> out.append("| ").append(name).append(" | ").append(counts[0])
                .append(" | ").append(counts[1]).append(" | ").append(percent(counts[0], counts[1])).append(" |\n"));
        return out.append('\n').toString();
    }

    private static void writeTraces(Metrics metrics) throws IOException {
        Path directory = Path.of("target", "wavi-eval");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(metrics.group() + "-traces.md"), "# Failure traces: " + metrics.group()
                + " (" + metrics.mode() + ")\n\n" + String.join("\n", metrics.traces()));
    }

    private static String percent(long part, long total) {
        return total == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }

    private static String flatten(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 400 ? flat.substring(0, 397) + "..." : flat;
    }
}
