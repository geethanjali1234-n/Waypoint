package com.waypoint.service;

import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase P3A: the exact request count and an upper cost bound for the P3B live evaluation
 * ({@link WaviLiveRouterEvaluation}), computed offline from the real datasets and router prompts. No network.
 *
 * <ul>
 *   <li>Serving requests = the questions that reach the router, counted with a mocked router.</li>
 *   <li>Routing-alone requests = single-turn sealed unseen-v1 questions × roles.</li>
 *   <li>Tokens: characters / 3 (conservative for JSON-heavy text), plus 1,000 tokens per call for the structured-output
 *   system prompt the API adds (not published).</li>
 *   <li>The smoke check is a separate one-attempt run per model. Full evaluation calls retry at most once.</li>
 * </ul>
 * Written to target/wavi-eval/p3b-plan.md.
 */
class WaviLiveEvaluationPlanTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ObjectMapper WRITER = new ObjectMapper();
    /** USD per million tokens (platform.claude.com pricing, checked 2026-11-09; prompts under 100K tokens). */
    private static final Map<String, double[]> PRICES = Map.of(
            "claude-haiku-5-5", new double[]{0.10, 0.50, 0.125, 0.01},
            "claude-sonnet-5-5", new double[]{2.00, 10.00, 2.50, 0.10});
    private static final int STRUCTURED_OUTPUT_OVERHEAD_TOKENS = 1_000;
    private static final int OUTPUT_TOKENS_PER_CALL = 120;
    private static final int SMOKE_LOGICAL_CALLS_PER_MODEL = 1;
    private static final int SMOKE_MAX_HTTP_ATTEMPTS_PER_MODEL = 1;
    private static final int SAMPLE_LOGICAL_CALLS_PER_MODEL = 10;
    private static final int SAMPLE_MAX_HTTP_ATTEMPTS_PER_MODEL = 20;
    private static final int FULL_ROUTER_MAX_ATTEMPTS = 2;

    @Test
    void thePlannedLiveEvaluationHasAnExactRequestCountAndABoundedCost() throws Exception {
        WaviChatbotEvaluationTest evaluation = new WaviChatbotEvaluationTest();
        Map<String, Long> serving = new LinkedHashMap<>();
        long servingTotal = 0;
        for (String dataset : List.of("scenarios", "holdout", "holdout-round2", "unseen-v1")) {
            long reached = evaluation.evaluate("/wavi-eval/" + dataset + ".json",
                            options -> options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.ABSTAIN).stream()
                    .filter(result -> result.calls().contains("Claude.routeJson")).count();
            serving.put(dataset, reached);
            servingTotal += reached;
        }
        JsonNode unseen;
        try (InputStream in = getClass().getResourceAsStream("/wavi-eval/unseen-v1.json")) {
            unseen = JSON.readTree(in);
        }
        long routingAlone = 0;
        long questionChars = 0;
        for (JsonNode scenario : unseen.get("scenarios")) {
            if (scenario.has("turns")) continue;
            routingAlone += scenario.path("roles").size();
            questionChars += (long) scenario.get("message").asString().length() * scenario.path("roles").size();
        }
        long fullLogicalDecisions = servingTotal + routingAlone;
        long fullMaxHttpAttempts = fullLogicalDecisions * FULL_ROUTER_MAX_ATTEMPTS;
        long totalLogicalWithSmoke = fullLogicalDecisions + SMOKE_LOGICAL_CALLS_PER_MODEL;
        long totalMaxHttpAttemptsWithSmoke = fullMaxHttpAttempts + SMOKE_MAX_HTTP_ATTEMPTS_PER_MODEL;
        long totalLogicalWithStages = totalLogicalWithSmoke + SAMPLE_LOGICAL_CALLS_PER_MODEL;
        long totalMaxHttpAttemptsWithStages = totalMaxHttpAttemptsWithSmoke + SAMPLE_MAX_HTTP_ATTEMPTS_PER_MODEL;

        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        List<WaviTool> catalog = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options).service.toolLayer()
                .registry().all();
        WaviRouterPrompt.Request sample = WaviRouterPrompt.build(catalog, UserRole.EMPLOYEE, LocalDate.parse("2026-11-05"),
                null, "x".repeat((int) Math.max(1, questionChars / Math.max(1, routingAlone))));
        long systemTokens = sample.system().length() / 3;
        long schemaTokens = WRITER.writeValueAsString(sample.schema()).length() / 3;
        long userTokens = sample.user().length() / 3;
        long inputPerCall = systemTokens + schemaTokens + userTokens + STRUCTURED_OUTPUT_OVERHEAD_TOKENS;

        StringBuilder report = new StringBuilder("# P3B live evaluation plan (computed offline)\n\n")
                .append("Smoke and full evaluation are separate opt-in runs. Smoke makes one logical decision and "
                        + "one HTTP attempt; full evaluation excludes smoke.\n\n")
                .append("| Part | Logical decisions per model | Maximum HTTP attempts per model |\n|---|---:|---:|\n");
        serving.forEach((dataset, count) -> report.append("| Serving: ").append(dataset).append(" | ").append(count)
                .append(" | ").append(count * FULL_ROUTER_MAX_ATTEMPTS).append(" |\n"));
        report.append("| Routing alone: unseen-v1 single-turn questions | ").append(routingAlone).append(" | ")
                .append(routingAlone * FULL_ROUTER_MAX_ATTEMPTS).append(" |\n")
                .append("| **Full evaluation (excludes smoke)** | **").append(fullLogicalDecisions).append("** | **")
                .append(fullMaxHttpAttempts).append("** |\n")
                .append("| Compatibility smoke (separate run) | ").append(SMOKE_LOGICAL_CALLS_PER_MODEL).append(" | ")
                .append(SMOKE_MAX_HTTP_ATTEMPTS_PER_MODEL).append(" |\n")
                .append("| Compatibility sample (separate run) | ").append(SAMPLE_LOGICAL_CALLS_PER_MODEL).append(" | ")
                .append(SAMPLE_MAX_HTTP_ATTEMPTS_PER_MODEL).append(" |\n")
                .append("| **Full evaluation plus its smoke** | **").append(totalLogicalWithSmoke).append("** | **")
                .append(totalMaxHttpAttemptsWithSmoke).append("** |\n\n")
                .append("| **Sample, full evaluation and smoke** | **").append(totalLogicalWithStages).append("** | **")
                .append(totalMaxHttpAttemptsWithStages).append("** |\n\n")
                .append("Estimated tokens per call: system ").append(systemTokens).append(", schema ").append(schemaTokens)
                .append(", user ").append(userTokens).append(", structured-output overhead ")
                .append(STRUCTURED_OUTPUT_OVERHEAD_TOKENS).append(" = input ").append(inputPerCall).append("; output ")
                .append(OUTPUT_TOKENS_PER_CALL).append(".\n\n| Model | Expected cost (cache hits) | Upper bound (no cache, every call retried once) |\n|---|---|---|\n");
        // Haiku is the approved router: its run is the required evaluation. Sonnet stays an optional comparison the
        // harness supports, approved and budgeted separately, so it is reported but not part of the required total.
        double haikuUpper = 0;
        double sonnetUpper = 0;
        for (String model : List.of("claude-haiku-5-5", "claude-sonnet-5-5")) {
            double[] price = PRICES.get(model);
            double cachedPart = systemTokens + schemaTokens;
            double expected = totalLogicalWithSmoke * ((cachedPart * price[3] + (inputPerCall - cachedPart) * price[0]
                    + OUTPUT_TOKENS_PER_CALL * price[1]) / 1_000_000.0);
            double upper = (fullMaxHttpAttempts + SMOKE_MAX_HTTP_ATTEMPTS_PER_MODEL)
                    * ((inputPerCall * price[2] + OUTPUT_TOKENS_PER_CALL * price[1]) / 1_000_000.0);
            if (model.equals("claude-haiku-5-5")) haikuUpper = upper; else sonnetUpper = upper;
            report.append("| ").append(model).append(" | $").append(String.format(Locale.ROOT, "%.2f", expected))
                    .append(" | $").append(String.format(Locale.ROOT, "%.2f", upper)).append(" |\n");
        }
        report.append("\nRequired evaluation (Haiku only) upper bound, smoke plus full: $")
                .append(String.format(Locale.ROOT, "%.2f", haikuUpper)).append(". Optional Sonnet comparison (separate "
                        + "approval) upper bound: $").append(String.format(Locale.ROOT, "%.2f", sonnetUpper))
                .append(". Hard HTTP cap: smoke=1; full=").append(fullMaxHttpAttempts).append(" per model.\n");
        Path directory = Path.of("target", "wavi-eval");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("p3b-plan.md"), report.toString());
        System.out.println(report);

        // BROAD deterministic routes wait for the router when routing is on (docs/wavi/routing-precedence.md), so
        // more questions reach it: before tiers 100 / 16 / 15 / 103 (234), now 194 / 30 / 30 / 142 (396; exact people, hours and review-list routes answer some without the router).
        assertThat(serving.values()).allMatch(count -> count > 0);
        assertThat(routingAlone).isEqualTo(148);
        // Better deterministic coverage can reduce reach. The exact computed count is in the report; the
        // pre-approved attempt ceiling must never grow as a side effect of a routing change.
        assertThat(fullLogicalDecisions).isLessThanOrEqualTo(544);
        assertThat(fullMaxHttpAttempts).isLessThanOrEqualTo(WaviLiveRouterEvaluation.FULL_MAX_HTTP_ATTEMPTS);
        assertThat(totalMaxHttpAttemptsWithSmoke).isLessThanOrEqualTo(1089);
        assertThat(SAMPLE_LOGICAL_CALLS_PER_MODEL).isEqualTo(WaviLiveRouterEvaluation.SAMPLE_LOGICAL_DECISIONS);
        assertThat(SAMPLE_MAX_HTTP_ATTEMPTS_PER_MODEL).isEqualTo(WaviLiveRouterEvaluation.SAMPLE_MAX_HTTP_ATTEMPTS);
        assertThat(totalMaxHttpAttemptsWithStages).isLessThanOrEqualTo(1109);
        // The required Haiku run must fit inside the FULL spend ceiling the harness enforces (fail-closed above it).
        assertThat(haikuUpper).as("required Haiku upper bound, USD")
                .isLessThan(WaviLiveRouterEvaluation.MAX_SPEND_CEILING_USD.get(WaviLiveRouterEvaluation.Mode.FULL).doubleValue());
        // The optional Sonnet comparison keeps a bounded worst case of its own.
        assertThat(sonnetUpper).as("optional Sonnet upper bound, USD").isLessThan(30.0);
    }
}
