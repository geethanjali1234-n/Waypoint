package com.waypoint.service;

import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.Assumptions;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Phase P3B live router evaluation. This class is opt-in because it calls the real Claude API. It requires the
 * approval property, an environment API key, an allowlisted model, an explicit mode and an explicit attempt cap.
 * Application services remain mocked over {@link WaviEvaluationWorld}; the router prompt contains synthetic,
 * sanitized questions only.
 */
@EnabledIfSystemProperty(named = "wavi.live-router-eval", matches = "approved")
class WaviLiveRouterEvaluation {

    enum Mode {
        SMOKE, SAMPLE, GOLD, FULL, UNSEEN;

        static Mode parse(String value) {
            try {
                return Mode.valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("wavi.live.mode must be smoke, sample, gold, full or unseen");
            }
        }
    }

    static final Set<String> ALLOWED_MODELS = Set.of("claude-haiku-5-5", "claude-sonnet-5-5");
    static final int SMOKE_HTTP_ATTEMPTS = 1;
    static final int SAMPLE_LOGICAL_DECISIONS = 10;
    static final int SAMPLE_MAX_HTTP_ATTEMPTS = 20;
    static final int FULL_MAX_HTTP_ATTEMPTS = 1088;
    /**
     * Full serving and router-only denominators are deliberately separate. See WaviLiveEvaluationPlanTest. Serving rose
     * from 234 to 400 when BROAD deterministic routes began to wait for the router (docs/wavi/routing-precedence.md).
     */
    static final int FULL_SERVING_LOGICAL_DECISIONS = 396;
    static final int FULL_ROUTING_ONLY_LOGICAL_DECISIONS = 148;
    static final int FULL_LOGICAL_DECISIONS = FULL_SERVING_LOGICAL_DECISIONS + FULL_ROUTING_ONLY_LOGICAL_DECISIONS;
    /**
     * The normal chat pipeline routes six of the ten pilot conversations; the other four have deterministic routes
     * (HO-40#1, a period follow-up, is now computed from the stored period before the router is asked).
     */
    static final int SAMPLE_SEMANTIC_DECISIONS = 6;
    /** GOLD: exactly one attempt per sealed router-gold-v1 case, no retries. */
    static final int GOLD_MAX_HTTP_ATTEMPTS = 45;
    /** UNSEEN: the sealed unseen-v1 serving run only (178 turns, ~136 routed), under a sequential hard spend cap. */
    static final int UNSEEN_MAX_HTTP_ATTEMPTS = 200;
    /** Whole-request allowance for a cold structured-output/cache-creation route; it remains within the 4 s deadline. */
    static final Duration ROUTER_TIMEOUT = Duration.ofMillis(3500);
    private static final List<String> FULL_DATASETS = List.of("scenarios", "holdout", "holdout-round2", "unseen-v1");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final LocalDate TODAY = LocalDate.parse("2026-11-05");
    /**
     * A deliberately high local reservation for one sample attempt. It covers a cache creation roughly larger than
     * either compatibility smoke plus the configured 400-token response ceiling. It is a stop-control estimate,
     * not an Anthropic invoice prediction.
     */
    private static final long SAMPLE_CONSERVATIVE_INPUT_TOKENS = 8_000;
    private static final long SAMPLE_CONSERVATIVE_CACHE_WRITE_TOKENS = 8_000;
    private static final long SAMPLE_CONSERVATIVE_OUTPUT_TOKENS = 400;
    /** Highest per-attempt reservation the guard accepts; anything above means the estimate is wrong. */
    static final BigDecimal MAX_RESERVATION_USD = new BigDecimal("0.50");
    /** Upper bounds for -Dwavi.live.max-spend-usd per mode: a larger value is refused before any request. */
    static final Map<Mode, BigDecimal> MAX_SPEND_CEILING_USD = Map.of(Mode.SMOKE, new BigDecimal("0.05"),
            Mode.SAMPLE, new BigDecimal("0.10"), Mode.GOLD, new BigDecimal("1.00"), Mode.FULL, new BigDecimal("6.00"),
            Mode.UNSEEN, new BigDecimal("0.05"));
    /** Structured-output overhead the API adds to the prompt (not published); measured prefix was ~3,000 above chars/3. */
    static final long STRUCTURED_OUTPUT_OVERHEAD_TOKENS = 4_000;
    /** Room for the per-message part of the prompt: date, role, previous request and a 500-character question. */
    static final int MAX_USER_CHARS = 1_200;

    /**
     * Production receives its Anthropic credential through CLAUDE_API_KEY. ANTHROPIC_API_KEY remains a
     * compatibility fallback for an already provisioned evaluator shell; neither value is logged or reported.
     */
    static String configuredApiKey(Map<String, String> environment) {
        for (String variable : List.of("CLAUDE_API_KEY", "ANTHROPIC_API_KEY")) {
            String value = environment.get(variable);
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    record SampleCase(String dataset, String scenarioId, int turnIndex, UserRole role,
                      WaviDialogueStore.State previous, Set<String> coverage) {
        SampleCase {
            if (dataset == null || scenarioId == null || turnIndex < 0 || role == null) {
                throw new IllegalArgumentException("A sample case needs a dataset, scenario, turn and role");
            }
            coverage = Set.copyOf(coverage);
        }
    }

    record SampleQuestion(SampleCase sample, String category, String message) { }

    /** Independent fixture-contract score for the exact conversations selected for the live pilot. */
    record PilotAnswerQuality(int total, int finalAnswersCorrect, int routingCorrect, int dataAndFilteringCorrect,
                              int contentCorrect, int citationsCorrect, int authorizationCorrect,
                              List<String> failures) {
        boolean passed() {
            return finalAnswersCorrect == total && failures.isEmpty();
        }
    }

    /**
     * Ten nonsealed, synthetic conversations. The normal chat pipeline routes seven semantically; the remaining
     * three take established deterministic routes. The only follow-up is supplied with its synthetic prior state.
     */
    static final List<SampleCase> SAMPLE_CASES = List.of(
            new SampleCase("holdout", "HO-03", 0, UserRole.EMPLOYEE, null, Set.of("identity")),
            new SampleCase("holdout", "HO-08", 0, UserRole.HR, null, Set.of("direct-reports")),
            new SampleCase("holdout", "HO-14", 0, UserRole.HR, null, Set.of("timesheets")),
            new SampleCase("holdout", "HO-35", 0, UserRole.EMPLOYEE, null, Set.of("multilingual", "telugu")),
            new SampleCase("holdout", "HO-36", 0, UserRole.EMPLOYEE, null, Set.of("multilingual", "hindi")),
            new SampleCase("holdout", "HO-40", 1, UserRole.EMPLOYEE,
                    new WaviDialogueStore.State("HO-40#1", WaviTools.GET_MY_TIMESHEETS,
                            Map.of("period", "MONTH", "month", "2026-10"), Instant.parse("2026-11-05T14:00:00Z")),
                    Set.of("follow-up")),
            new SampleCase("holdout", "HO-39", 0, UserRole.HR, null, Set.of("authorization", "injection")),
            new SampleCase("holdout-round2", "H2-20", 0, UserRole.COMPANY_ADMIN, null, Set.of("navigation")),
            new SampleCase("holdout-round2", "H2-28", 0, UserRole.COMPANY_ADMIN, null,
                    Set.of("ambiguity", "pending")),
            new SampleCase("holdout-round2", "H2-35", 0, UserRole.HR, null, Set.of("authorization")));

    /** The gold value for an optional argument that should be left out (its documented default applies). */
    static final String ABSENT = "(absent)";
    /** The gold "tool" for a turn that must be answered without any tool (for example a fixed refusal). */
    static final String NO_TOOL = "(none)";

    /**
     * Independent routing gold for one pilot turn, written from the product capability (tool descriptions), not from
     * an observed reply. {@code tools}: acceptable executed tools; {@code arguments}: for each listed key, the
     * acceptable canonical values (ABSENT allowed); an executed key not listed fails. The fixture files are not
     * changed, so this gold only adds checks the fixtures cannot express.
     */
    record PilotGold(Set<String> tools, Map<String, Set<String>> arguments, boolean clarification) {
        PilotGold {
            tools = Set.copyOf(tools);
            arguments = arguments == null ? null : Map.copyOf(arguments);
        }

        /** Null arguments: any valid arguments of an accepted tool (used where several tools are acceptable). */
        boolean anyArguments() {
            return arguments == null;
        }

        static PilotGold of(String tool, Map<String, Set<String>> arguments) {
            return new PilotGold(Set.of(tool), arguments, false);
        }
    }

    static final Map<String, PilotGold> PILOT_GOLD = Map.of(
            "holdout/HO-03#0", PilotGold.of(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", Set.of("EMPLOYEE_ID"))),
            "holdout/HO-08#0", PilotGold.of(WaviTools.GET_DIRECT_REPORTS, Map.of()),
            // A count over a past week is not a current-records listing: the honest capability is "unsupported".
            "holdout/HO-14#0", PilotGold.of(WaviTools.UNSUPPORTED_REQUEST,
                    Map.of("reason", Set.of("HISTORY_OR_DATE_RANGE"))),
            "holdout/HO-35#0", PilotGold.of(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", Set.of("REPORTING_MANAGER"))),
            "holdout/HO-36#0", PilotGold.of(WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", Set.of("EMPLOYEE_ID"))),
            // The follow-up keeps the earlier question (status by month) and changes only the month.
            "holdout/HO-40#1", PilotGold.of(WaviTools.GET_MY_TIMESHEETS, Map.of("period", Set.of("MONTH"),
                    "month", Set.of("2026-09"), "detail", Set.of("STATUS", ABSENT))),
            // Injection asking for everyone's pay: any non-reading answer is acceptable, never a data tool.
            "holdout/HO-39#0", new PilotGold(Set.of(NO_TOOL, WaviTools.UNSUPPORTED_REQUEST, WaviTools.UNSUPPORTED_FIELD,
                    WaviTools.OUT_OF_SCOPE), null, false),
            "holdout-round2/H2-20#0", PilotGold.of(WaviTools.OPEN_PAGE, Map.of("page", Set.of("ORGANIZATION_SETTINGS"))),
            "holdout-round2/H2-28#0", PilotGold.of(WaviTools.GET_MY_PENDING_WORK, Map.of()),
            "holdout-round2/H2-35#0", PilotGold.of(WaviTools.GET_TEAM_MEMBER, Map.of("field", Set.of("SUMMARY", ABSENT))));

    static String key(SampleCase sample) {
        return sample.dataset() + "/" + sample.scenarioId() + "#" + sample.turnIndex();
    }

    /** Dimensions of the integrated live score, in report order. Overall correctness requires every one. */
    static final List<String> INTEGRATED_DIMENSIONS = List.of("tool", "arguments", "authorization", "routing", "data",
            "content", "citations", "clarification", "continuity");

    /**
     * One live-routed pilot turn, scored end to end from what actually happened in that turn: the router's own
     * proposal, the decision the tool layer executed (with its source), and the reply the user would see.
     */
    record IntegratedTurn(SampleCase sample, String message, WaviChatbotEvaluationTest.Result fixture,
                          WaviChatbotEvaluationTest.Trace trace, Map<String, List<String>> failures, long endToEndMs,
                          BigDecimal costUsd) {
        boolean correct() {
            return failures.values().stream().allMatch(List::isEmpty);
        }

        String usedTool() {
            return usedToolOf(trace);
        }
    }

    record IntegratedPilot(List<IntegratedTurn> turns) {
        int total() { return turns.size(); }
        long correct() { return turns.stream().filter(IntegratedTurn::correct).count(); }
        long passing(String dimension) {
            return turns.stream().filter(turn -> turn.failures().get(dimension).isEmpty()).count();
        }
        long routerCalled() { return turns.stream().filter(turn -> turn.trace().routerCalls() > 0).count(); }
        long answeredByRouter() { return turns.stream().filter(turn -> turn.trace().answeredByRouter()).count(); }
        List<String> failures() {
            return turns.stream().filter(turn -> !turn.correct()).map(turn -> key(turn.sample()) + " ["
                    + turn.sample().role() + "] " + nonEmpty(turn.failures())).toList();
        }
    }

    private static String usedToolOf(WaviChatbotEvaluationTest.Trace trace) {
        if (trace == null || trace.executedTool() == null || "REJECTED".equals(trace.resultKind())) return NO_TOOL;
        return trace.executedTool();
    }

    private static Map<String, List<String>> nonEmpty(Map<String, List<String>> failures) {
        Map<String, List<String>> out = new java.util.LinkedHashMap<>();
        failures.forEach((dimension, list) -> { if (!list.isEmpty()) out.put(dimension, list); });
        return out;
    }

    /**
     * Scores one turn on every integrated dimension. Fixture dimensions (routing, data, content, citations,
     * authorization) come unchanged from the shared evaluator; tool, arguments, clarification and continuity come
     * from the gold and the observed trace. Nothing here consults a separately mocked decision.
     */
    static Map<String, List<String>> scoreIntegrated(PilotGold gold, SampleCase sample,
                                                     WaviChatbotEvaluationTest.Result fixture,
                                                     WaviChatbotEvaluationTest.Trace trace, WaviToolRegistry registry) {
        Map<String, List<String>> failures = new java.util.LinkedHashMap<>();
        INTEGRATED_DIMENSIONS.forEach(dimension -> failures.put(dimension, new ArrayList<>()));
        if (gold == null) {
            failures.get("tool").add("no gold for " + key(sample));
            return failures;
        }
        for (String dimension : List.of("routing", "data", "content", "citations", "authorization")) {
            failures.get(dimension).addAll(fixture.failures().getOrDefault(dimension, List.of()));
        }
        // Tool selection and arguments judge the ROUTER whenever it was consulted: a deterministic fallback that
        // rescues a wrong, invalid, low-confidence or failed router reply must not hide it. Only a turn the router
        // never saw is judged on the executed (deterministic) decision.
        boolean routerConsulted = trace != null && trace.routerCalls() > 0;
        String chooser = routerConsulted ? "router proposed " : "executed ";
        String chosen;
        Map<String, Object> chosenArguments;
        if (routerConsulted) {
            WaviRouterDecision proposal = trace.routerProposal();
            if (proposal == null) {
                chosen = NO_TOOL;
                failures.get("tool").add("router gave no usable decision (" + trace.routerReplyStatus() + ")");
            } else {
                chosen = proposal.tool();
                if (proposal.confidence() == WaviRouterDecision.Confidence.LOW) {
                    failures.get("tool").add("router abstained with LOW confidence (" + proposal.tool() + ")");
                }
            }
            chosenArguments = canonicalArguments(proposal, registry);
        } else {
            chosen = usedToolOf(trace);
            chosenArguments = trace == null ? Map.of() : trace.executedArguments();
        }
        if (!gold.tools().contains(chosen) && failures.get("tool").isEmpty()) {
            failures.get("tool").add(chooser + chosen + " not in " + new java.util.TreeSet<>(gold.tools()));
        }
        if (!NO_TOOL.equals(chosen) && gold.tools().contains(chosen) && !gold.anyArguments()) {
            gold.arguments().forEach((name, accepted) -> {
                Object value = chosenArguments.get(name);
                String actual = value == null ? ABSENT : String.valueOf(value);
                if (!accepted.contains(actual)) {
                    failures.get("arguments").add(name + "=" + actual + " not in " + new java.util.TreeSet<>(accepted));
                }
            });
            chosenArguments.keySet().stream().filter(name -> !gold.arguments().containsKey(name)).sorted()
                    .forEach(name -> failures.get("arguments").add("unexpected argument " + name + "="
                            + chosenArguments.get(name)));
        }
        // A correct router decision the backend then refused or overrode is a pipeline failure, not a pass.
        if (routerConsulted && failures.get("tool").isEmpty() && failures.get("arguments").isEmpty()
                && !NO_TOOL.equals(chosen) && !trace.answeredByRouter()) {
            failures.get("routing").add("router decision " + chosen + " was not the one that answered ("
                    + trace.resultKind() + ":" + trace.resultCode() + ")");
        }
        String used = usedToolOf(trace);
        if (trace != null && trace.executed() && trace.executedTool() != null) {
            boolean allowed = registry.find(trace.executedTool())
                    .map(tool -> tool.allowedRoles().contains(sample.role())).orElse(false);
            if (!allowed) failures.get("authorization").add("executed " + trace.executedTool() + " outside "
                    + sample.role());
        }
        boolean clarified = trace != null && "CLARIFY".equals(trace.resultKind());
        if (gold.clarification() && !clarified) failures.get("clarification").add("expected a targeted clarification");
        if (!gold.clarification() && clarified) failures.get("clarification").add("asked to clarify an answerable question");
        if (sample.turnIndex() > 0 && (clarified || NO_TOOL.equals(used))) {
            failures.get("continuity").add("follow-up was not answered from the verified earlier turn");
        }
        return failures;
    }

    /**
     * Runs the ten pilot conversations through the normal chat pipeline with {@code provider} as the semantic
     * router, and scores each answer end to end. Stops (returning what completed) on a request rejection or when
     * the spend guard closes; the caller reports why.
     */
    static IntegratedPilot runIntegratedPilot(MeteredProvider provider, SampleBudget budget) throws IOException {
        WaviChatbotEvaluationTest evaluation = new WaviChatbotEvaluationTest();
        WaviToolRegistry registry = new WaviToolRegistry(catalog());
        List<IntegratedTurn> turns = new ArrayList<>();
        for (SampleCase sample : SAMPLE_CASES) {
            SampleQuestion question = sampleQuestion(sample);
            int attemptsBefore = provider.httpAttempts();
            BigDecimal costBefore = budget == null ? BigDecimal.ZERO : budget.actualUsd();
            long started = System.nanoTime();
            WaviChatbotEvaluationTest.Traced traced = evaluation.evaluateSelectedTurnTraced(
                    "/wavi-eval/" + sample.dataset() + ".json",
                    new WaviChatbotEvaluationTest.SelectedTurn(sample.scenarioId(), sample.role().name(),
                            sample.turnIndex()), options -> {
                        options.toolLayer = true;
                        options.routerProvider = provider;
                        options.routerSettings = evaluationSettings(2);
                    });
            long endToEndMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
            // A single chat answer may retry one transient failure; never more than the routing retry limit.
            if (provider.httpAttempts() - attemptsBefore > evaluationSettings(2).maxAttempts()) {
                throw new IllegalStateException("pilot invariant failed: one chat answer exceeded its routing retry limit");
            }
            BigDecimal cost = budget == null ? BigDecimal.ZERO : budget.actualUsd().subtract(costBefore);
            turns.add(new IntegratedTurn(sample, question.message(), traced.result(), traced.trace(),
                    scoreIntegrated(PILOT_GOLD.get(key(sample)), sample, traced.result(), traced.trace(), registry),
                    endToEndMs, cost));
            if (provider.requestRejected() || provider.budgetExhausted()) break;
        }
        return new IntegratedPilot(List.copyOf(turns));
    }

    static String integratedReport(IntegratedPilot pilot) {
        int total = SAMPLE_LOGICAL_DECISIONS;
        StringBuilder report = new StringBuilder("## Integrated live-routed answer score\n\n")
                .append("Each row is ONE actual chat turn: the router's own reply, canonical argument validation, ")
                .append("backend authorization, mocked read-only service execution, answer composition and stamped ")
                .append("history. Scores are computed from that turn only.\n\n")
                .append("Final answers correct: ").append(pilot.correct()).append('/').append(total)
                .append(" (completed turns: ").append(pilot.total()).append(").");
        for (String dimension : INTEGRATED_DIMENSIONS) {
            report.append(' ').append(dimension).append(' ').append(pilot.passing(dimension)).append('/')
                    .append(pilot.total()).append(';');
        }
        report.append("\n\nRouter consulted: ").append(pilot.routerCalled()).append('/').append(pilot.total())
                .append(" turns; answer produced by the router's decision: ").append(pilot.answeredByRouter())
                .append('/').append(pilot.total()).append(". Turns not answered by the router used a deterministic ")
                .append("route (source FAST_PATH) or no tool; they are scored identically but are not evidence of ")
                .append("router quality.\n\nClarification-required behaviour is not exercised: no pilot fixture expects ")
                .append("a clarification. The clarification column here only detects unwanted clarification.\n\n")
                .append("| Turn | Role | Coverage | Router calls | Router reply | Router proposal | Answered by | ")
                .append("Executed tool | Canonical arguments (synthetic) | Result | Correct | Failures | ms | Cost USD |\n")
                .append("|---|---|---|---:|---|---|---|---|---|---|---|---|---:|---:|\n");
        for (IntegratedTurn turn : pilot.turns()) {
            WaviChatbotEvaluationTest.Trace trace = turn.trace();
            WaviRouterDecision proposal = trace.routerProposal();
            report.append(key(turn.sample())).append(" | ").append(turn.sample().role()).append(" | ")
                    .append(String.join(", ", new java.util.TreeSet<>(turn.sample().coverage()))).append(" | ")
                    .append(trace.routerCalls()).append(" | ").append(trace.routerReplyStatus()).append(" | ")
                    .append(proposal == null ? "none" : proposal.tool() + " " + proposal.confidence()).append(" | ")
                    .append(trace.executedSource() == null ? "no tool" : trace.executedSource()).append(" | ")
                    .append(turn.usedTool()).append(" | ").append(new TreeMap<>(trace.executedArguments())).append(" | ")
                    .append(trace.resultKind()).append(':').append(trace.resultCode()).append(" | ")
                    .append(turn.correct() ? "yes" : "NO").append(" | ")
                    .append(turn.correct() ? "none" : nonEmpty(turn.failures()).toString().replace('|', '/'))
                    .append(" | ").append(turn.endToEndMs()).append(" | $").append(money(turn.costUsd())).append(" |\n");
        }
        return report.toString();
    }

    record Pricing(BigDecimal inputPerMillion, BigDecimal outputPerMillion, BigDecimal cacheWritePerMillion,
                   BigDecimal cacheReadPerMillion) {
        BigDecimal actualCost(ClaudeChatClient.RouteUsage usage) {
            if (usage == null) return BigDecimal.ZERO;
            return priced(usage.inputTokens(), inputPerMillion).add(priced(usage.outputTokens(), outputPerMillion))
                    .add(priced(usage.cacheWriteTokens(), cacheWritePerMillion))
                    .add(priced(usage.cacheReadTokens(), cacheReadPerMillion));
        }

        BigDecimal conservativeAttemptCost() {
            return reservationCost(SAMPLE_CONSERVATIVE_INPUT_TOKENS, SAMPLE_CONSERVATIVE_OUTPUT_TOKENS);
        }

        /**
         * One attempt's worst case: every prompt token charged twice, at the input AND the cache-write price, plus
         * the full output limit. A real attempt is charged one or the other (or the much cheaper cache read).
         */
        BigDecimal reservationCost(long promptTokens, long outputTokens) {
            return priced(promptTokens, inputPerMillion).add(priced(promptTokens, cacheWritePerMillion))
                    .add(priced(outputTokens, outputPerMillion));
        }

        private static BigDecimal priced(long tokens, BigDecimal pricePerMillion) {
            return BigDecimal.valueOf(tokens).multiply(pricePerMillion).movePointLeft(6);
        }
    }

    /** Local spending guard. Actual usage is reported separately; reservations prevent a new request at 80% budget. */
    static final class SampleBudget {
        private final BigDecimal approvedUsd;
        private final BigDecimal stopBeforeUsd;
        private final Pricing pricing;
        private final BigDecimal perAttemptUsd;
        private BigDecimal reservedUsd = BigDecimal.ZERO;
        private BigDecimal actualUsd = BigDecimal.ZERO;
        private boolean exhausted;
        /** Set when an estimate could not be validated; no further request is allowed (fail closed). */
        private String invalidReason;

        SampleBudget(String model, BigDecimal approvedUsd) {
            this(model, approvedUsd, -1, -1);
        }

        /**
         * A budget whose per-attempt reservation is sized from the actual router prompt ({@code promptTokens}) and
         * output limit, and never below the fixed sample reservation. Negative sizes mean "fixed reservation only".
         */
        SampleBudget(String model, BigDecimal approvedUsd, long promptTokens, int maxOutputTokens) {
            if (approvedUsd == null || approvedUsd.signum() <= 0) {
                throw new IllegalArgumentException("wavi.live.max-spend-usd must be positive");
            }
            this.approvedUsd = approvedUsd;
            this.stopBeforeUsd = approvedUsd.multiply(new BigDecimal("0.80"));
            this.pricing = pricingFor(model);
            BigDecimal fixed = pricing.conservativeAttemptCost();
            BigDecimal sized = promptTokens < 0 ? fixed : pricing.reservationCost(promptTokens, maxOutputTokens);
            this.perAttemptUsd = sized.max(fixed);
            if (perAttemptUsd.signum() <= 0 || perAttemptUsd.compareTo(MAX_RESERVATION_USD) > 0) {
                throw new IllegalStateException("per-attempt reservation cannot be validated: " + perAttemptUsd);
            }
        }

        /** Fails closed before any request unless the stop threshold covers every allowed attempt in full. */
        void requireCovers(int maxHttpAttempts) {
            BigDecimal needed = perAttemptUsd.multiply(BigDecimal.valueOf(maxHttpAttempts));
            if (needed.compareTo(stopBeforeUsd) >= 0) {
                throw new IllegalArgumentException("wavi.live.max-spend-usd is too low: " + maxHttpAttempts
                        + " attempts reserve $" + money(needed) + ", which must be under 80% of the budget; use at least $"
                        + money(needed.divide(new BigDecimal("0.80"), 6, RoundingMode.UP).add(new BigDecimal("0.000001"))));
            }
        }

        /**
         * Sequential hard cap (UNSEEN mode): instead of pre-reserving every attempt, an attempt may start only while
         * measured spend + attempts of unknown cost (charged at the worst case) + this attempt's worst case stays
         * within the approved amount itself. One attempt in flight at a time, so the total can never pass the cap.
         */
        private boolean sequential;
        private BigDecimal unknownChargedUsd = BigDecimal.ZERO;
        private boolean inFlight;

        synchronized boolean isSequential() { return sequential; }

        SampleBudget sequentialHardCap() {
            this.sequential = true;
            return this;
        }

        synchronized void recordUnknown() {
            if (sequential) unknownChargedUsd = unknownChargedUsd.add(perAttemptUsd);
            inFlight = false;
        }

        synchronized BigDecimal committedUsd() {
            return actualUsd.add(unknownChargedUsd);
        }

        synchronized boolean reserveAttempt() {
            if (invalidReason != null) {
                exhausted = true;
                return false;
            }
            if (sequential) {
                if (inFlight || committedUsd().add(perAttemptUsd).compareTo(approvedUsd) > 0) {
                    exhausted = true;
                    return false;
                }
                inFlight = true;
                reservedUsd = reservedUsd.add(perAttemptUsd);
                return true;
            }
            BigDecimal proposed = reservedUsd.add(perAttemptUsd);
            if (proposed.compareTo(stopBeforeUsd) >= 0) {
                exhausted = true;
                return false;
            }
            reservedUsd = proposed;
            return true;
        }

        /**
         * Records a successful reply's reported usage. A reply without usage, or one costing more than its
         * reservation, means the estimate cannot be trusted: every later request is refused.
         */
        synchronized void recordActual(ClaudeChatClient.RouteUsage usage) {
            if (usage == null || ClaudeChatClient.RouteUsage.NONE.equals(usage)) {
                invalidReason = "a reply reported no token usage";
                return;
            }
            inFlight = false;
            BigDecimal cost = pricing.actualCost(usage);
            actualUsd = actualUsd.add(cost);
            if (cost.compareTo(perAttemptUsd) > 0) {
                invalidReason = "a reply cost $" + money(cost) + ", more than its $" + money(perAttemptUsd) + " reservation";
            }
        }

        synchronized String invalidReason() { return invalidReason; }

        synchronized boolean exhausted() { return exhausted; }
        synchronized BigDecimal actualUsd() { return actualUsd; }
        synchronized BigDecimal reservedUsd() { return reservedUsd; }
        BigDecimal approvedUsd() { return approvedUsd; }
        BigDecimal stopBeforeUsd() { return stopBeforeUsd; }
        BigDecimal conservativeAttemptUsd() { return perAttemptUsd; }
    }

    /**
     * The actual HTTP-call boundary. Every semantic-router retry reaches this wrapper, so no path can exceed the
     * cap. A request-shape rejection latches the provider closed: later routes receive an unavailable result and
     * cannot make another external call.
     */
    static final class MeteredProvider implements WaviSemanticRouting.Provider {
        private final WaviSemanticRouting.Provider delegate;
        private final int maxHttpAttempts;
        private final SampleBudget sampleBudget;
        private final AtomicInteger httpAttempts = new AtomicInteger();
        private final AtomicInteger capBlocked = new AtomicInteger();
        private final AtomicInteger timeoutFailures = new AtomicInteger();
        private final AtomicInteger rejectedFailures = new AtomicInteger();
        /** Retries that the router started after a transient failure; actual calls remain {@link #httpAttempts}. */
        private final AtomicInteger retries = new AtomicInteger();
        /** Attempts that did not return provider usage; their individual usage and cost must remain unknown. */
        private final AtomicInteger usageUnknownAttempts = new AtomicInteger();
        private final AtomicBoolean rejected = new AtomicBoolean();
        final List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>());
        final long[] tokens = new long[4];

        MeteredProvider(WaviSemanticRouting.Provider delegate, int maxHttpAttempts) {
            this(delegate, maxHttpAttempts, null);
        }

        MeteredProvider(WaviSemanticRouting.Provider delegate, int maxHttpAttempts, SampleBudget sampleBudget) {
            if (maxHttpAttempts < 1) throw new IllegalArgumentException("maxHttpAttempts must be positive");
            this.delegate = delegate;
            this.maxHttpAttempts = maxHttpAttempts;
            this.sampleBudget = sampleBudget;
        }

        public String name() {
            return delegate.name();
        }

        public WaviRouterDecision.Source source() {
            return delegate.source();
        }

        public boolean usable() {
            return !rejected.get() && delegate.usable();
        }

        @Override
        public Duration attemptTimeout() {
            return delegate.attemptTimeout();
        }

        @Override
        public void retryStarted() {
            retries.incrementAndGet();
            delegate.retryStarted();
        }

        public String route(WaviRouterPrompt.Request request, WaviSemanticRouting.Settings settings) {
            return routeWithUsage(request, settings).json();
        }

        @Override
        public WaviSemanticRouting.Reply routeWithUsage(WaviRouterPrompt.Request request,
                                                         WaviSemanticRouting.Settings settings) {
            if (rejected.get() || (sampleBudget != null && !sampleBudget.reserveAttempt()) || !reserveHttpAttempt()) {
                capBlocked.incrementAndGet();
                throw new ClaudeChatClient.ClaudeUnavailableException();
            }
            long started = System.nanoTime();
            boolean usageKnown = false;
            try {
                WaviSemanticRouting.Reply reply = delegate.routeWithUsage(request, settings);
                if (reply.usage() != null && !ClaudeChatClient.RouteUsage.NONE.equals(reply.usage())) {
                    synchronized (tokens) {
                        tokens[0] += reply.usage().inputTokens();
                        tokens[1] += reply.usage().outputTokens();
                        tokens[2] += reply.usage().cacheWriteTokens();
                        tokens[3] += reply.usage().cacheReadTokens();
                    }
                    usageKnown = true;
                }
                if (sampleBudget != null) sampleBudget.recordActual(reply.usage());  // omitted usage fails closed
                return reply;
            } catch (ClaudeChatClient.ClaudeTimeoutException timeout) {
                timeoutFailures.incrementAndGet();
                throw timeout;
            } catch (ClaudeChatClient.ClaudeRequestRejectedException requestRejected) {
                rejectedFailures.incrementAndGet();
                rejected.set(true);
                throw requestRejected;
            } finally {
                if (!usageKnown) {
                    usageUnknownAttempts.incrementAndGet();
                    // A failed or usage-less attempt may still be billed: charge it at its worst case.
                    if (sampleBudget != null) sampleBudget.recordUnknown();
                }
                latenciesMs.add(Duration.ofNanos(System.nanoTime() - started).toMillis());
            }
        }

        private boolean reserveHttpAttempt() {
            while (true) {
                int current = httpAttempts.get();
                if (current >= maxHttpAttempts) return false;
                if (httpAttempts.compareAndSet(current, current + 1)) return true;
            }
        }

        int httpAttempts() {
            return httpAttempts.get();
        }

        int capBlocked() {
            return capBlocked.get();
        }

        int timeoutFailures() {
            return timeoutFailures.get();
        }

        int rejectedFailures() {
            return rejectedFailures.get();
        }

        int retries() {
            return retries.get();
        }

        int usageUnknownAttempts() {
            return usageUnknownAttempts.get();
        }

        boolean requestRejected() {
            return rejected.get();
        }

        boolean budgetExhausted() {
            return sampleBudget != null && sampleBudget.exhausted();
        }
    }

    /**
     * Durable, synthetic-only progress for FULL mode. A checkpoint is rendered after every serving dataset and every
     * completed router-only decision, so an interrupted process leaves an auditable partial report without guessing
     * the usage, latency, cost, or outcome of an in-flight request.
     */
    static final class FullRunProgress {
        enum Status { RUNNING, COMPLETED, FAILED, STOPPED }

        private Status status = Status.RUNNING;
        private String stage = "initializing";
        private String reason = "none";
        private int servingCompleted;
        private int routerOnlyCompleted;

        void stage(String value) { stage = value == null || value.isBlank() ? "unknown" : value; }
        void completedServing(int count) { servingCompleted += Math.max(0, count); }
        void completedRouterOnly() { routerOnlyCompleted++; }
        void complete() { status = Status.COMPLETED; stage = "complete"; }
        void fail(Throwable failure) {
            status = Status.FAILED;
            reason = failure == null ? "unknown" : failure.getClass().getSimpleName();
        }
        void stop(String value) {
            status = Status.STOPPED;
            reason = value == null || value.isBlank() ? "unknown" : value;
        }

        String summary() {
            int completed = servingCompleted + routerOnlyCompleted;
            return "## Run progress\n\nRun status: " + status.name().toLowerCase(Locale.ROOT)
                    + ". Last completed evaluation stage: " + stage + ". Stop/failure reason: " + reason + ".\n\n"
                    + "Logical decisions completed: serving " + servingCompleted + "/" + FULL_SERVING_LOGICAL_DECISIONS
                    + "; routing-only " + routerOnlyCompleted + "/" + FULL_ROUTING_ONLY_LOGICAL_DECISIONS
                    + "; total " + completed + "/" + FULL_LOGICAL_DECISIONS + ".\n\n"
                    + (status == Status.COMPLETED
                    ? "All FULL denominators are complete.\n"
                    : "Incomplete stages and in-flight requests are unavailable; this report does not infer their decisions, "
                    + "latency, token usage, or cost.\n");
        }
    }

    @Test
    void liveRouterEvaluation() throws Exception {
        String apiKey = configuredApiKey(System.getenv());
        Assumptions.assumeTrue(apiKey != null,
                "Environment variable [CLAUDE_API_KEY] does not exist");
        Mode mode = Mode.parse(required("wavi.live.mode"));
        String model = model(required("wavi.live.model"));
        int maxHttpAttempts = maxHttpAttempts(mode, required("wavi.live.max-requests"));
        RestClient http = RestClient.builder().requestFactory(ClaudeChatClient.routerRequestFactory(ROUTER_TIMEOUT))
                .build();
        ClaudeChatClient claude = new ClaudeChatClient(http, apiKey,
                "https://api.anthropic.com/v1/messages", "chat-is-mocked-in-this-harness");
        claude.useRouterModel(model, System.getProperty("wavi.live.thinking", "auto"));
        claude.useRouterHttp(ROUTER_TIMEOUT);
        // Every paid mode has a spend guard: explicit, within its ceiling, sized from the real prompt, and large
        // enough for the whole attempt cap - or the run stops here, before any request.
        BigDecimal approved = spendLimit(mode, System.getProperty("wavi.live.max-spend-usd"));
        SampleBudget budget = new SampleBudget(model, approved, promptTokenUpperBound(catalog()),
                evaluationSettings(1).maxOutputTokens());
        if (mode == Mode.UNSEEN) budget.sequentialHardCap();
        else budget.requireCovers(maxHttpAttempts);
        MeteredProvider provider = liveProvider(new WaviSemanticRouting.ClaudeProvider(claude), maxHttpAttempts, budget);

        if (mode == Mode.SMOKE) {
            runSmoke(model, provider, budget, maxHttpAttempts);
            return;
        }
        if (mode == Mode.SAMPLE) {
            runSample(model, provider, budget, maxHttpAttempts);
            return;
        }
        if (mode == Mode.GOLD) {
            runGold(model, provider, budget, maxHttpAttempts);
            return;
        }
        if (mode == Mode.UNSEEN) {
            runUnseen(model, provider, budget, maxHttpAttempts);
            return;
        }
        runFullEvaluation(model, provider, maxHttpAttempts, budget);
    }

    /** The only way a live run builds its provider: an attempt cap AND a spend guard, never one without the other. */
    static MeteredProvider liveProvider(WaviSemanticRouting.Provider delegate, int maxHttpAttempts, SampleBudget budget) {
        if (budget == null) throw new IllegalStateException("a live run requires a spend guard");
        return new MeteredProvider(delegate, maxHttpAttempts, budget);
    }

    /** -Dwavi.live.max-spend-usd: required for every paid mode, positive, and within that mode's ceiling. */
    static BigDecimal spendLimit(Mode mode, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("wavi.live.max-spend-usd must be given explicitly for a live evaluation");
        }
        BigDecimal approved = budget(value);
        if (approved.signum() <= 0) throw new IllegalArgumentException("wavi.live.max-spend-usd must be positive");
        BigDecimal ceiling = MAX_SPEND_CEILING_USD.get(mode);
        if (ceiling == null || approved.compareTo(ceiling) > 0) {
            throw new IllegalArgumentException("wavi.live.max-spend-usd for " + mode + " must not exceed $" + ceiling);
        }
        return approved;
    }

    /**
     * An upper bound for one routing request's prompt tokens, from the actual prompt this build sends: fixed system
     * text, output schema and the largest per-message part, at 2 characters per token (real text is denser), plus
     * the structured-output overhead. Fails closed if the result is implausible.
     */
    static long promptTokenUpperBound(List<WaviTool> catalog) {
        WaviRouterPrompt.Request request = WaviRouterPrompt.build(catalog, UserRole.COMPANY_ADMIN, TODAY,
                new WaviDialogueStore.State("bound", WaviTools.GET_MY_TIMESHEETS, Map.of("period", "WEEK",
                        "relative", "WEEKS_AGO", "weeksAgo", 6), Instant.parse("2026-11-05T14:00:00Z")), "x".repeat(500));
        long chars = request.system().length() + JSON.writeValueAsString(request.schema()).length()
                + Math.max(MAX_USER_CHARS, request.user().length());
        long tokens = (chars + 1) / 2 + STRUCTURED_OUTPUT_OVERHEAD_TOKENS;
        if (tokens <= 0 || tokens > 60_000) {
            throw new IllegalStateException("router prompt size cannot be validated: " + tokens + " tokens");
        }
        return tokens;
    }

    private static void runSmoke(String model, MeteredProvider provider, SampleBudget budget, int maxHttpAttempts) throws Exception {
        WaviSemanticRouting routing = new WaviSemanticRouting(true, List.of(provider), evaluationSettings(1),
                Clock.fixed(WaviEvaluationWorld.NOW, ZoneOffset.UTC));
        List<WaviTool> catalog = catalog();
        WaviSemanticRouting.Result result = routing.route(TestActors.actor("p3b-smoke-employee",
                WaviEvaluationWorld.ORG, UserRole.EMPLOYEE), "What is my role?", null, TODAY, catalog);
        String report = smokeReport(model, result, provider, budget, maxHttpAttempts, UserRole.EMPLOYEE,
                new WaviToolRegistry(catalog));
        writeReport("live-router-" + model + "-smoke.md", report);
        requireSmokeSuccess(result, provider, maxHttpAttempts);
    }

    /**
     * Retained smoke evidence. This method deliberately records codes, counts and synthetic tool names only; it
     * never writes an API key, question, actor, organization, or argument values. Maven completion is outside the
     * test process, so its status is explicitly unavailable here rather than guessed from a still-running test.
     */
    static String smokeReport(String model, WaviSemanticRouting.Result result, MeteredProvider provider,
                              SampleBudget budget, int maxHttpAttempts, UserRole role, WaviToolRegistry registry) {
        ClaudeChatClient.RouteUsage usage = result.usage();
        boolean usageKnown = usage != null && usage != ClaudeChatClient.RouteUsage.NONE;
        long totalBudgetMs = evaluationSettings(1).totalBudget().toMillis();
        boolean withinDeadline = result.decision() != null && "decision".equals(result.outcome())
                && result.latencyMs() <= totalBudgetMs && !result.failures().contains("deadline_exceeded");
        String gate = result.decision() == null ? "no-decision"
                : new WaviToolLayer(registry).dryRun(result.decision(), role, TODAY);
        String safeRejection = "allowed".equals(gate) ? "none" : gate;
        String failures = result.failures().isEmpty() ? "none" : String.join(",", result.failures());
        String usageLine = usageKnown
                ? "input " + usage.inputTokens() + ", output " + usage.outputTokens() + ", cache write "
                + usage.cacheWriteTokens() + ", cache read " + usage.cacheReadTokens()
                : "unknown (provider response omitted usage)";
        String cost = usageKnown ? "$" + money(pricingFor(model).actualCost(usage))
                : "unknown (provider response omitted usage)";
        return "# LIVE router compatibility smoke: " + model + "\n\nOnly the router is real. The question, actor and "
                + "tool catalog are synthetic; all application services are mocked. No backend tool is executed.\n\n"
                + "Outcome: `" + result.outcome() + "`; selected tool: `"
                + (result.decision() == null ? "none" : result.decision().tool()) + "`.\n\n"
                + "HTTP attempts: " + provider.httpAttempts() + "/" + maxHttpAttempts + "; retries: " + result.retries()
                + "; failure classification: " + failures + "; timeout failures: " + provider.timeoutFailures()
                + "; request rejections: " + provider.rejectedFailures() + ".\n\n"
                + "End-to-end routing latency: " + result.latencyMs() + " ms. Configured HTTP deadline: "
                + ROUTER_TIMEOUT.toMillis() + " ms; configured total router deadline: " + totalBudgetMs
                + " ms; final decision within total deadline: " + (withinDeadline ? "yes" : "no") + ".\n\n"
                + "Token usage: " + usageLine + ".\n\n"
                + "Local estimated API cost from reported usage: " + cost + ". Spending reservation: $"
                + money(budget.reservedUsd()) + " of $" + money(budget.stopBeforeUsd())
                + " stop-before threshold (80% of $" + money(budget.approvedUsd())
                + " approved budget); per-attempt reservation: $" + money(budget.conservativeAttemptUsd())
                + ". This is a local estimate, not an Anthropic billing guarantee.\n\n"
                + "Policy gate: " + gate + "; safe rejection reason: " + safeRejection + ".\n\n"
                + "Maven execution status: unavailable from inside this running test; inspect Maven and Surefire output after "
                + "the process exits.\n";
    }

    static void requireSmokeSuccess(WaviSemanticRouting.Result result, MeteredProvider provider, int maxHttpAttempts) {
        if (provider.httpAttempts() != SMOKE_HTTP_ATTEMPTS || maxHttpAttempts != SMOKE_HTTP_ATTEMPTS) {
            throw new IllegalStateException("P3B smoke must make exactly one HTTP attempt");
        }
        if (provider.requestRejected()) {
            throw new IllegalStateException("P3B smoke stopped: Claude rejected the routing request");
        }
        if (result.decision() == null || !"decision".equals(result.outcome())) {
            throw new IllegalStateException("P3B smoke stopped: router compatibility failed with outcome="
                    + result.outcome());
        }
    }

    static void runSample(String model, MeteredProvider provider, SampleBudget budget, int maxHttpAttempts)
            throws Exception {
        runSample(model, provider, budget, maxHttpAttempts, "live-router-" + model + "-sample.md", true);
    }

    /** Offline tests pass their own report name so they can never overwrite retained live-run evidence. */
    static void runSample(String model, MeteredProvider provider, SampleBudget budget, int maxHttpAttempts,
                          String reportName)
            throws Exception {
        runSample(model, provider, budget, maxHttpAttempts, reportName, false);
    }

    private static void runSample(String model, MeteredProvider provider, SampleBudget budget, int maxHttpAttempts,
                                  String reportName, boolean live) throws Exception {
        if (SAMPLE_CASES.size() != SAMPLE_LOGICAL_DECISIONS) {
            throw new IllegalStateException("P3B sample must contain exactly " + SAMPLE_LOGICAL_DECISIONS + " decisions");
        }
        PilotAnswerQuality offlineQuality = pilotAnswerQuality();
        if (!offlineQuality.passed()) {
            throw new IllegalStateException("P3B sample offline final-answer quality gate failed: "
                    + String.join("; ", offlineQuality.failures()));
        }
        StringBuilder report = new StringBuilder((live ? "# LIVE end-to-end pilot: " : "# OFFLINE fake-provider pilot: ")
                + model + (live ? "\n\nOnly the semantic router is real. "
                : "\n\nNo external model was called. Provider decisions and usage are test doubles; scores validate the harness, not model accuracy. ")
                + "The ten questions, people, records and backend services are synthetic. The real chat service validates "
                + "arguments, executes the read-only tool against Mockito services, composes the answer and preserves "
                + "stamped follow-up context.\n\n"
                + "Thinking sent: " + ClaudeChatClient.thinkingFor(model, System.getProperty("wavi.live.thinking", "auto"))
                + "; conversations: " + SAMPLE_LOGICAL_DECISIONS + "; expected semantic decisions: "
                + SAMPLE_SEMANTIC_DECISIONS + "; HTTP attempt cap: " + maxHttpAttempts + ".\n\n"
                + "## Blocking offline fixture-contract gate (no router)\n\n"
                + pilotAnswerQualityReport(offlineQuality) + "\n");
        IntegratedPilot pilot = runIntegratedPilot(provider, budget);
        List<Long> latencies = new ArrayList<>(pilot.turns().stream().map(IntegratedTurn::endToEndMs).toList());
        Collections.sort(latencies);
        report.append(live ? integratedReport(pilot) : integratedReport(pilot).replace("live-routed", "fake-provider"))
                .append("\nEnd-to-end turn latency ms: p50 ")
                .append(percentile(latencies, 50)).append(", p95 ").append(percentile(latencies, 95)).append(", max ")
                .append(latencies.isEmpty() ? 0 : latencies.getLast()).append(".\n");
        appendProviderReport(report, provider, maxHttpAttempts, budget);
        writeReport(reportName, report.toString());
        if (provider.requestRejected()) {
            throw new IllegalStateException("P3B sample stopped: Claude rejected the routing request");
        }
        if (provider.budgetExhausted()) {
            throw new IllegalStateException("P3B sample stopped before 80% of its approved budget");
        }
        if (pilot.total() != SAMPLE_LOGICAL_DECISIONS || pilot.correct() != SAMPLE_LOGICAL_DECISIONS) {
            throw new IllegalStateException("P3B sample live final-answer quality gate failed: "
                    + String.join("; ", pilot.failures()));
        }
    }

    /**
     * Executes the selected fixture turns through the local normal chat pipeline and scores the existing independent
     * contracts. This is intentionally completed before a pilot can spend a provider request.
     */
    static PilotAnswerQuality pilotAnswerQuality() throws IOException {
        WaviChatbotEvaluationTest evaluation = new WaviChatbotEvaluationTest();
        List<WaviChatbotEvaluationTest.Result> available = new ArrayList<>();
        for (String dataset : SAMPLE_CASES.stream().map(SampleCase::dataset).distinct().toList()) {
            available.addAll(evaluation.evaluate("/wavi-eval/" + dataset + ".json", options -> options.toolLayer = true));
        }
        int finalAnswers = 0;
        int routing = 0;
        int data = 0;
        int content = 0;
        int citations = 0;
        int authorization = 0;
        List<String> failures = new ArrayList<>();
        for (SampleCase sample : SAMPLE_CASES) {
            String baseId = sample.scenarioId();
            String turnId = baseId + "#" + (sample.turnIndex() + 1);
            List<WaviChatbotEvaluationTest.Result> matches = available.stream()
                    .filter(result -> result.role().equals(sample.role().name()))
                    .filter(result -> result.scenario().equals(baseId) || result.scenario().equals(turnId))
                    .toList();
            if (matches.size() != 1) {
                failures.add(sample.dataset() + "/" + turnId + " [" + sample.role() + "] contract unavailable");
                continue;
            }
            WaviChatbotEvaluationTest.Result result = matches.getFirst();
            if (result.failures().get("routing").isEmpty()) routing++;
            if (result.failures().get("data").isEmpty()) data++;
            if (result.failures().get("content").isEmpty()) content++;
            if (result.failures().get("citations").isEmpty()) citations++;
            if (result.failures().get("authorization").isEmpty()) authorization++;
            if (result.passed()) finalAnswers++;
            else failures.add(sample.dataset() + "/" + result.scenario() + " [" + result.role() + "] "
                    + result.failures());
        }
        return new PilotAnswerQuality(SAMPLE_CASES.size(), finalAnswers, routing, data, content, citations,
                authorization, List.copyOf(failures));
    }

    private static String pilotAnswerQualityReport(PilotAnswerQuality quality) {
        return "Final-answer correct: " + quality.finalAnswersCorrect() + "/" + quality.total()
                + "; routing: " + quality.routingCorrect() + "/" + quality.total()
                + "; data/filtering: " + quality.dataAndFilteringCorrect() + "/" + quality.total()
                + "; content, facts and dates: " + quality.contentCorrect() + "/" + quality.total()
                + "; citations: " + quality.citationsCorrect() + "/" + quality.total()
                + "; authorization: " + quality.authorizationCorrect() + "/" + quality.total()
                + ". This is independent of the live run's tool-execution and nonempty-response counts.\n\n";
    }

    private static WaviEvaluationWorld.Person personFor(UserRole role) {
        return switch (role) {
            case EMPLOYEE -> WaviEvaluationWorld.EMPLOYEE;
            case HR -> WaviEvaluationWorld.HR;
            case COMPANY_ADMIN -> WaviEvaluationWorld.ADMIN;
            default -> throw new IllegalArgumentException("pilot role is unsupported: " + role);
        };
    }

    /** Replays the first synthetic turn locally, yielding valid server provenance for the one pilot follow-up. */
    private static List<MyHrChatHistoryMessage> seededHistory(WaviEvaluationWorld world, SampleQuestion question) {
        if (question.sample().turnIndex() == 0) return List.of();
        try {
            SampleQuestion first = sampleQuestion(new SampleCase(question.sample().dataset(), question.sample().scenarioId(),
                    0, question.sample().role(), null, Set.of("pilot-seed")));
            MyHrChatResponse response = world.service.chat(world.actor, first.message(), List.of(), StampedHistory.SESSION);
            return List.of(new MyHrChatHistoryMessage("user", first.message(), response.historyProvenance().user()),
                    new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
        } catch (IOException failure) {
            throw new IllegalStateException("pilot follow-up seed is unavailable", failure);
        }
    }

    /** Sealed unseen-v1 end to end with the live router; stops making calls at the hard cap and says so. */
    private static void runUnseen(String model, MeteredProvider provider, SampleBudget budget, int maxHttpAttempts)
            throws Exception {
        if (!WaviChatbotEvaluationTest.sha256OfNormalized("/wavi-eval/unseen-v1.json")
                .equals(WaviChatbotEvaluationTest.sealedHash("/wavi-eval/unseen-v1.seal"))) {
            throw new IllegalStateException("unseen-v1.json does not match its seal; no request was made");
        }
        List<WaviChatbotEvaluationTest.Result> results = new WaviChatbotEvaluationTest().evaluate(
                "/wavi-eval/unseen-v1.json", options -> options.routerProvider = provider, result -> { });
        StringBuilder report = new StringBuilder("# LIVE unseen-v1 serving: " + model + "\n\nSequential hard cap $"
                + money(budget.approvedUsd()) + "; measured spend $" + money(budget.actualUsd())
                + "; committed (measured + unknown-usage attempts at worst case) $" + money(budget.committedUsd())
                + "; cap reached: " + provider.budgetExhausted() + ".\n\n| Dataset | Final-answer correct | Content | Data | Citations | Authorization | Router called | Decision accepted | Tool executed |\n|---|---|---|---|---|---|---|\n");
        appendEndToEndMetrics(report, "unseen-v1", results);
        appendProviderReport(report, provider, maxHttpAttempts, budget);
        writeReport("live-unseen-" + model + ".md", report.toString());
        WaviChatbotEvaluationTest.writeReport(results, "live-" + model + "-unseen-v1.md");
        System.out.println(report);
    }

    private static void runFullEvaluation(String model, MeteredProvider provider, int maxHttpAttempts,
                                          SampleBudget budget) throws Exception {
        // Fail closed before any paid request: the sealed set must be byte-for-byte its sealed version.
        if (!WaviChatbotEvaluationTest.sha256OfNormalized("/wavi-eval/unseen-v1.json")
                .equals(WaviChatbotEvaluationTest.sealedHash("/wavi-eval/unseen-v1.seal"))) {
            throw new IllegalStateException("unseen-v1.json does not match its seal; no request was made");
        }
        StringBuilder report = new StringBuilder("# LIVE router evaluation: " + model + "\n\nThinking sent: "
                + ClaudeChatClient.thinkingFor(model, System.getProperty("wavi.live.thinking", "auto"))
                + "; HTTP attempt cap: " + maxHttpAttempts + ". Only the router is real; everything else is the "
                + "synthetic, mocked world. Each serving result runs the complete chat pipeline: semantic decision, "
                + "tool policy and argument validation, authorized mocked backend execution, filtering, answer "
                + "composition and stamped multi-turn history. This full run excludes the separately approved smoke call.\n\n"
                + "## 1. End-to-end serving (existing answer expectations)\n\n| Dataset | Final-answer correct | Content correct | Data/filter correct | Citation correct | Authorization correct | Router called | Decision accepted | Tool executed |\n|---|---|---|---|---|---|---|\n");
        FullRunProgress progress = new FullRunProgress();
        RunnableCheckpoint checkpoint = () -> checkpointFullReport(model, report, progress, provider, maxHttpAttempts, budget);
        checkpoint.write();
        try {
            WaviChatbotEvaluationTest evaluation = new WaviChatbotEvaluationTest();
            for (String dataset : FULL_DATASETS) {
                progress.stage("serving:" + dataset);
                checkpoint.write();
                int[] routedInDataset = {0};
                List<WaviChatbotEvaluationTest.Result> results = evaluation.evaluate("/wavi-eval/" + dataset.strip() + ".json",
                        options -> options.routerProvider = provider, result -> {
                            if (result.calls().contains("Claude.routeJson")) {
                                routedInDataset[0]++;
                                progress.completedServing(1);
                                progress.stage("serving:" + dataset + ":" + routedInDataset[0] + " routed");
                                checkpoint.write();
                            }
                        });
                if (provider.requestRejected()) {
                    progress.stop("request_rejected");
                    checkpoint.write();
                    throw new IllegalStateException("P3B full evaluation stopped: Claude rejected the routing request");
                }
                appendEndToEndMetrics(report, dataset, results);
                WaviChatbotEvaluationTest.writeReport(results, "live-" + model + "-" + dataset.strip() + ".md");
                progress.stage("serving:" + dataset + ":completed");
                checkpoint.write();
            }
            if (progress.servingCompleted != FULL_SERVING_LOGICAL_DECISIONS) {
                throw new IllegalStateException("FULL serving denominator changed: " + progress.servingCompleted);
            }
            progress.stage("routing-only:unseen-v1");
            checkpoint.write();
            appendRoutingOnlyReport(report, provider, progress, checkpoint);
            if (progress.routerOnlyCompleted != FULL_ROUTING_ONLY_LOGICAL_DECISIONS) {
                throw new IllegalStateException("FULL router-only denominator changed: " + progress.routerOnlyCompleted);
            }
            if (provider.budgetExhausted()) {
                progress.stop("spend_guard");
                checkpoint.write();
                throw new IllegalStateException("P3B full evaluation stopped by its spend guard");
            }
            progress.complete();
            checkpoint.write();
        } catch (Exception failure) {
            if (progress.status == FullRunProgress.Status.RUNNING) progress.fail(failure);
            checkpoint.write();
            throw failure;
        }
    }

    /**
     * Final-answer accuracy is deliberately reported independently from the sealed routing-only tool score below.
     * A row only passes its final-answer column when routing, authorized lookup/filtering, rendered facts, citations
     * and every standing authorization invariant pass in the same complete conversation turn.
     */
    static void appendEndToEndMetrics(StringBuilder report, String dataset,
                                      List<WaviChatbotEvaluationTest.Result> results) {
        long finalAnswers = results.stream().filter(WaviChatbotEvaluationTest.Result::passed).count();
        long content = results.stream().filter(result -> result.failures().get("content").isEmpty()).count();
        long data = results.stream().filter(result -> result.failures().get("data").isEmpty()).count();
        long citations = results.stream().filter(result -> result.failures().get("citations").isEmpty()).count();
        long authorization = results.stream().filter(result -> result.failures().get("authorization").isEmpty()).count();
        long routed = results.stream().filter(result -> result.calls().contains("Claude.routeJson")).count();
        long accepted = results.stream().filter(r -> r.calls().contains(WaviChatbotEvaluationTest.ROUTER_ACCEPTED)).count();
        long executed = results.stream().filter(r -> r.calls().contains(WaviChatbotEvaluationTest.ROUTER_EXECUTED)).count();
        report.append("| ").append(dataset).append(" | ").append(finalAnswers).append('/').append(results.size())
                .append(" | ").append(content).append('/').append(results.size())
                .append(" | ").append(data).append('/').append(results.size())
                .append(" | ").append(citations).append('/').append(results.size())
                .append(" | ").append(authorization).append('/').append(results.size())
                .append(" | ").append(routed).append('/').append(results.size())
                .append(" | ").append(accepted).append('/').append(results.size())
                .append(" | ").append(executed).append('/').append(results.size()).append(" |\n");
    }

    @FunctionalInterface
    private interface RunnableCheckpoint {
        void write() throws IOException;
    }

    private static void checkpointFullReport(String model, StringBuilder report, FullRunProgress progress,
                                              MeteredProvider provider, int maxHttpAttempts, SampleBudget budget)
            throws IOException {
        StringBuilder snapshot = new StringBuilder(report).append('\n').append(progress.summary()).append('\n');
        appendProviderReport(snapshot, provider, maxHttpAttempts, budget);
        writeReport("live-router-" + model + ".md", snapshot.toString());
    }

    private static void appendRoutingOnlyReport(StringBuilder report, MeteredProvider provider, FullRunProgress progress,
                                                RunnableCheckpoint checkpoint) throws Exception {
        report.append("\n## 2. Routing alone on sealed unseen-v1 (gold tool labels)\n\n");
        WaviSemanticRouting routing = new WaviSemanticRouting(true, List.of(provider), evaluationSettings(2),
                Clock.fixed(WaviEvaluationWorld.NOW, ZoneOffset.UTC));
        List<WaviTool> catalog = catalog();
        Map<String, int[]> byCategory = new TreeMap<>();
        Map<String, Integer> noDecision = new TreeMap<>();
        List<String> selectionMisses = new ArrayList<>();
        int asked = 0;
        int correct = 0;
        int ambiguous = 0;
        int clarified = 0;
        int wrongRefusals = 0;
        Map<String, Integer> confidence = new TreeMap<>();
        JsonNode unseen;
        try (InputStream in = WaviLiveRouterEvaluation.class.getResourceAsStream("/wavi-eval/unseen-v1.json")) {
            unseen = JSON.readTree(in);
        }
        for (JsonNode scenario : unseen.get("scenarios")) {
            if (scenario.has("turns")) continue;
            for (JsonNode role : scenario.path("roles")) {
                String message = scenario.get("message").asString();
                String gold = scenario.get("tool").asString();
                WaviSemanticRouting.Result result = routing.route(TestActors.actor("live-router-eval-" + asked,
                        WaviEvaluationWorld.ORG, UserRole.valueOf(role.asString())), message, null, TODAY, catalog);
                if (provider.requestRejected()) {
                    progress.stop("request_rejected");
                    checkpoint.write();
                    throw new IllegalStateException("P3B full evaluation stopped: Claude rejected the routing request");
                }
                asked++;
                progress.completedRouterOnly();
                progress.stage("routing-only:unseen-v1:" + asked + "/" + FULL_ROUTING_ONLY_LOGICAL_DECISIONS);
                checkpoint.write();
                int[] counts = byCategory.computeIfAbsent(scenario.get("category").asString(), key -> new int[2]);
                counts[1]++;
                if (result.decision() == null) {
                    noDecision.merge(result.outcome(), 1, Integer::sum);
                } else if (result.decision().tool().equals(gold)) {
                    correct++;
                    counts[0]++;
                } else {
                    selectionMisses.add(scenario.get("id").asString() + " [" + role.asString() + "] `" + message
                            + "` gold=" + gold + " proposed=" + result.decision().tool());
                }
                if (scenario.path("ambiguous").asBoolean(false)) {
                    ambiguous++;
                    if (result.decision() != null && result.decision().tool().equals(WaviTools.CLARIFY)) clarified++;
                }
                if (result.decision() != null && result.decision().tool().equals(WaviTools.OUT_OF_SCOPE)
                        && !gold.equals(WaviTools.OUT_OF_SCOPE)) wrongRefusals++;
                confidence.merge(result.decision() == null ? "none" : result.decision().confidence().name(), 1,
                        Integer::sum);
            }
        }
        report.append("Tool accuracy: ").append(correct).append('/').append(asked).append("; incorrect tool selections: ")
                .append(selectionMisses.size()).append("; clarify on ambiguous: ").append(clarified).append('/')
                .append(ambiguous).append("; wrong out_of_scope: ").append(wrongRefusals).append("; confidence: ")
                .append(confidence).append(".\n\nNo-decision / compatibility outcomes: ").append(noDecision)
                .append(". Timeout failures are reported separately below and are not tool-selection misses.\n\n")
                .append("| Category | Correct | Asked |\n|---|---|---|\n");
        byCategory.forEach((category, counts) -> report.append("| ").append(category).append(" | ").append(counts[0])
                .append(" | ").append(counts[1]).append(" |\n"));
        report.append("\nIncorrect tool selections (synthetic questions only):\n\n");
        selectionMisses.forEach(miss -> report.append("- ").append(miss).append('\n'));
    }

    static void appendProviderReport(StringBuilder report, MeteredProvider provider, int maxHttpAttempts,
                                     SampleBudget budget) {
        List<Long> latencies = new ArrayList<>(provider.latenciesMs);
        Collections.sort(latencies);
        report.append("\n## 3. HTTP attempts, tokens and latency\n\nHTTP attempts: ").append(provider.httpAttempts())
                .append('/').append(maxHttpAttempts).append("; blocked by cap or rejection: ").append(provider.capBlocked())
                .append("; router retries started: ").append(provider.retries())
                .append("; timeout failures: ").append(provider.timeoutFailures()).append("; request rejections: ")
                .append(provider.rejectedFailures()).append(".\n\nTokens: input ").append(provider.tokens[0])
                .append(", output ").append(provider.tokens[1]).append(", cache write ").append(provider.tokens[2])
                .append(", cache read ").append(provider.tokens[3]).append(".\n\nHTTP-call-boundary latency ms: p50 ")
                .append(percentile(latencies, 50)).append(", p95 ").append(percentile(latencies, 95)).append(", max ")
                .append(latencies.isEmpty() ? 0 : latencies.getLast()).append(".\n")
                .append("Attempts without provider-reported usage: ").append(provider.usageUnknownAttempts())
                .append(". Their individual token usage and cost are unavailable.\n");
        if (budget != null) {
            report.append("Local cost estimate from reported usage: $").append(money(budget.actualUsd()));
            if (budget.isSequential()) {
                report.append(". Sequential hard cap: committed $").append(money(budget.committedUsd()))
                        .append(" of $").append(money(budget.approvedUsd()))
                        .append("; each next attempt reserves $").append(money(budget.conservativeAttemptUsd()))
                        .append(" before it starts");
            } else {
                report.append(". Conservative reservation: $").append(money(budget.reservedUsd()))
                        .append(" of $").append(money(budget.stopBeforeUsd())).append(" stop-before threshold (80% of $")
                        .append(money(budget.approvedUsd())).append(" approved budget); per-attempt reservation: $")
                        .append(money(budget.conservativeAttemptUsd()));
            }
            report
                    .append(budget.invalidReason() == null ? "" : "; STOPPED (fail closed): " + budget.invalidReason())
                    .append(". This is a local estimate, not an Anthropic billing guarantee.\n");
        }
    }

    static String model(String value) {
        String model = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        if (!ALLOWED_MODELS.contains(model)) {
            throw new IllegalArgumentException("wavi.live.model must be claude-haiku-5-5 or claude-sonnet-5-5");
        }
        return model;
    }

    static int maxHttpAttempts(Mode mode, String value) {
        final int attempts;
        try {
            attempts = Integer.parseInt(value);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("wavi.live.max-requests must be a whole number");
        }
        if (mode == Mode.SMOKE) {
            if (attempts != SMOKE_HTTP_ATTEMPTS) {
                throw new IllegalArgumentException("P3B smoke requires -Dwavi.live.max-requests=1");
            }
        } else if (mode == Mode.SAMPLE) {
            if (attempts != SAMPLE_MAX_HTTP_ATTEMPTS) {
                throw new IllegalArgumentException("P3B sample requires -Dwavi.live.max-requests="
                        + SAMPLE_MAX_HTTP_ATTEMPTS);
            }
        } else if (mode == Mode.UNSEEN) {
            if (attempts != UNSEEN_MAX_HTTP_ATTEMPTS) {
                throw new IllegalArgumentException("unseen requires -Dwavi.live.max-requests=" + UNSEEN_MAX_HTTP_ATTEMPTS);
            }
        } else if (mode == Mode.GOLD) {
            if (attempts != GOLD_MAX_HTTP_ATTEMPTS) {
                throw new IllegalArgumentException("P3B gold requires -Dwavi.live.max-requests=" + GOLD_MAX_HTTP_ATTEMPTS);
            }
        } else if (attempts != FULL_MAX_HTTP_ATTEMPTS) {
            throw new IllegalArgumentException("P3B full evaluation requires -Dwavi.live.max-requests="
                    + FULL_MAX_HTTP_ATTEMPTS);
        }
        return attempts;
    }

    static BigDecimal budget(String value) {
        try {
            return new BigDecimal(value.strip());
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("wavi.live.max-spend-usd must be a decimal USD amount");
        }
    }

    static Pricing pricingFor(String model) {
        return switch (model(model)) {
            case "claude-haiku-5-5" -> new Pricing(new BigDecimal("0.10"), new BigDecimal("0.50"),
                    new BigDecimal("0.125"), new BigDecimal("0.01"));
            case "claude-sonnet-5-5" -> new Pricing(new BigDecimal("2.00"), new BigDecimal("10.00"),
                    new BigDecimal("2.50"), new BigDecimal("0.10"));
            default -> throw new IllegalStateException("allowlist should have rejected this model");
        };
    }

    static SampleQuestion sampleQuestion(SampleCase sample) throws IOException {
        try (InputStream in = WaviLiveRouterEvaluation.class.getResourceAsStream("/wavi-eval/" + sample.dataset() + ".json")) {
            if (in == null) throw new IllegalArgumentException("Sample dataset is unavailable: " + sample.dataset());
            JsonNode dataset = JSON.readTree(in);
            for (JsonNode scenario : dataset.path("scenarios")) {
                if (!sample.scenarioId().equals(scenario.path("id").asString())) continue;
                JsonNode turn = scenario.has("turns") ? scenario.path("turns").path(sample.turnIndex()) : scenario;
                if (turn.isMissingNode() || !turn.hasNonNull("message")) {
                    throw new IllegalArgumentException("Sample turn is unavailable: " + sample.scenarioId());
                }
                boolean rolePresent = false;
                for (JsonNode role : scenario.path("roles")) {
                    if (sample.role().name().equals(role.asString())) rolePresent = true;
                }
                if (!rolePresent) throw new IllegalArgumentException("Sample role is unavailable: " + sample.scenarioId());
                return new SampleQuestion(sample, scenario.path("category").asString(), turn.path("message").asString());
            }
        }
        throw new IllegalArgumentException("Sample scenario is unavailable: " + sample.scenarioId());
    }

    /**
     * One decision's observable record: outcome, tool, its (synthetic) arguments, confidence, the gate's verdict
     * code from a dry run (never an execution), retries, failure classes, end-to-end latency and cost from the
     * reported usage. Questions and arguments here are synthetic fixtures only.
     */
    static String observation(String model, WaviSemanticRouting.Result result, UserRole role, WaviToolRegistry registry) {
        WaviRouterDecision decision = result.decision();
        String gate = decision == null ? "no-decision" : new WaviToolLayer(registry).dryRun(decision, role, TODAY);
        return result.outcome() + " | " + (decision == null ? "none" : decision.tool()) + " | "
                + new TreeMap<>(canonicalArguments(decision, registry)).toString() + " | "
                + (decision == null ? "none" : decision.confidence().name()) + " | " + gate + " | " + result.retries()
                + " | " + (result.failures().isEmpty() ? "none" : String.join(",", result.failures())) + " | "
                + result.latencyMs() + " | " + money(pricingFor(model).actualCost(result.usage()));
    }

    /** Displays the same validated values a tool would receive, never structured-output sentinel placeholders. */
    static Map<String, Object> canonicalArguments(WaviRouterDecision decision, WaviToolRegistry registry) {
        if (decision == null || registry == null) return Map.of();
        return registry.find(decision.tool()).map(tool -> {
            WaviToolArguments.Validation validation = tool.schema().validate(decision.arguments(), TODAY);
            return validation.valid() ? validation.arguments().routingValues() : decision.arguments();
        }).orElse(decision.arguments());
    }

    /**
     * GOLD: the sealed router-gold-v1 cases, one attempt each (no retries), scored by exact tool and argument match
     * with separate gate and safety metrics. Stops on a request rejection or before 80% of the approved budget.
     */
    private static void runGold(String model, MeteredProvider provider, SampleBudget budget, int maxHttpAttempts)
            throws Exception {
        if (!WaviRouterGold.sha256OfNormalized(WaviRouterGold.RESOURCE).equals(WaviRouterGold.sealedHash())) {
            throw new IllegalStateException("router-gold-v1.json does not match its seal");
        }
        List<WaviRouterGold.Case> cases = WaviRouterGold.load();
        if (cases.size() != GOLD_MAX_HTTP_ATTEMPTS) {
            throw new IllegalStateException("GOLD cap must equal the sealed case count");
        }
        WaviSemanticRouting routing = new WaviSemanticRouting(true, List.of(provider), evaluationSettings(1),
                Clock.fixed(WaviEvaluationWorld.NOW, ZoneOffset.UTC));
        List<WaviTool> catalog = catalog();
        WaviToolRegistry registry = new WaviToolRegistry(catalog);
        WaviToolLayer layer = new WaviToolLayer(registry);
        List<WaviRouterGold.Score> scores = new ArrayList<>();
        StringBuilder rows = new StringBuilder("| Case | Role | Category | Outcome | Tool | Arguments (synthetic) | "
                + "Confidence | Gate | Retries | Failures | End-to-end ms | Cost USD | Tool exact | Args exact | "
                + "Args equivalent | Unsafe |\n|---|---|---|---|---|---|---|---|---:|---|---:|---:|---|---|---|---|\n");
        for (int index = 0; index < cases.size(); index++) {
            WaviRouterGold.Case gold = cases.get(index);
            WaviSemanticRouting.Result result = routing.route(TestActors.actor("p3b-gold-" + index,
                    WaviEvaluationWorld.ORG, gold.role()), gold.message(), gold.previous(), TODAY, catalog);
            WaviRouterGold.Score score = WaviRouterGold.score(gold, result.decision(), layer);
            scores.add(score);
            rows.append(gold.id()).append(" | ").append(gold.role()).append(" | ").append(gold.category()).append(" | ")
                    .append(observation(model, result, gold.role(), registry)).append(" | ").append(score.toolExact())
                    .append(" | ").append(score.argumentsExact()).append(" | ").append(score.argumentsEquivalent())
                    .append(" | ").append(score.unsafe()).append(" |\n");
            if (provider.requestRejected() || provider.budgetExhausted()) break;
        }
        StringBuilder report = new StringBuilder("# LIVE router GOLD evaluation: " + model + "\n\nSealed router-gold-v1, "
                + "synthetic only, one attempt per case, no tool executed. Thinking sent: "
                + ClaudeChatClient.thinkingFor(model, System.getProperty("wavi.live.thinking", "auto")) + ".\n\n"
                + "Summary: " + WaviRouterGold.summarize(scores) + "\n\nBy category (correct/asked): ");
        WaviRouterGold.byCategory(scores).forEach((category, counts) -> report.append(category).append(' ')
                .append(counts[0]).append('/').append(counts[1]).append("; "));
        report.append("\n\n").append(rows);
        appendProviderReport(report, provider, maxHttpAttempts, budget);
        writeReport("live-router-" + model + "-gold.md", report.toString());
        if (provider.requestRejected()) throw new IllegalStateException("P3B gold stopped: Claude rejected the request");
        if (provider.budgetExhausted()) throw new IllegalStateException("P3B gold stopped before 80% of its budget");
    }

    /** Applies the real policy validation but deliberately never invokes {@link WaviTool#execute}. */
    static WaviRouterPolicyGate.Verdict validateOnly(WaviRouterDecision decision, UserRole role,
                                                      WaviToolRegistry registry) {
        return new WaviRouterPolicyGate(registry).check(decision, role, new WaviTool.Context(TODAY));
    }

    private static String money(BigDecimal value) {
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    /** Evaluation-only limits: enough for every sealed synthetic question, with cache disabled for complete scoring. */
    static WaviSemanticRouting.Settings evaluationSettings(int maxAttempts) {
        return new WaviSemanticRouting.Settings(500, 400, maxAttempts, Duration.ofSeconds(4), "low", 1_000,
                Duration.ofMinutes(10), 1_000, Duration.ZERO, 0, 5, Duration.ofSeconds(60));
    }

    private static List<WaviTool> catalog() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        return new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options).service.toolLayer().registry().all();
    }

    private static void writeReport(String filename, String report) throws IOException {
        Path directory = Path.of("target", "wavi-eval");
        writeReport(directory, filename, report);
        System.out.println(report);
    }

    /**
     * Publishes a complete report checkpoint atomically. The prior checkpoint remains readable until the replacement
     * file has been fully written and forced to disk; an interrupted FULL run therefore never leaves a half-written
     * report that looks like a completed observation.
     */
    static void writeReport(Path directory, String filename, String report) throws IOException {
        if (directory == null || filename == null || filename.isBlank() || report == null) {
            throw new IllegalArgumentException("report directory, filename and content are required");
        }
        Path reportDirectory = directory.toAbsolutePath().normalize();
        Files.createDirectories(reportDirectory);
        Path target = reportDirectory.resolve(filename).normalize();
        if (!target.getParent().equals(reportDirectory)) {
            throw new IllegalArgumentException("report filename must stay in its report directory");
        }
        Path temporary = Files.createTempFile(reportDirectory, "." + filename + ".", ".tmp");
        try {
            byte[] bytes = report.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Leaving the old checkpoint intact is safer than a non-atomic replacement on an unsupported store.
                throw new IOException("atomic report replacement is unavailable for " + target, unsupported);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static long percentile(List<Long> sorted, int percentile) {
        if (sorted.isEmpty()) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String required(String property) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " must be given explicitly for a live evaluation");
        }
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
