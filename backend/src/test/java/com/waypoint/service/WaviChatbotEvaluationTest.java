package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.mockito.invocation.Invocation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wavi's automated evaluation: every scenario in {@code wavi-eval/scenarios.json} is asked by each role it names,
 * through the real MyHrChatService routing, grounding and citation validation, against the synthetic world in
 * {@link WaviEvaluationWorld}. Expected behaviour is written in the dataset from the product requirements, not
 * copied from Wavi's replies.
 *
 * <p>Each answer is scored on five dimensions: routing (which path answered: a record lookup, a fixed Waypoint
 * answer, company policy, a general AI answer, a scope refusal or an error), data access (which authorized lookups
 * ran), content (what the reply must and must not say), citations (knowledge status and cited documents) and
 * authorization. Authorization is checked on every answer, whatever the scenario says: every data lookup is scoped
 * to the verified user's own id, retrieval is asked only for the verified actor, no restricted value (another
 * person's record, a personal record answer, HR-only or another organization's policy text) reaches Claude unless
 * the user typed it, Claude never receives policy documents, an Employee never sees HR-only text, and no internal
 * prompt markup is shown.
 *
 * <p>A report is written to target/wavi-eval/report.md. The test fails on any failed check, so the dataset is
 * also a regression suite. No network, database or AI provider is used.
 */
class WaviChatbotEvaluationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Map<String, WaviEvaluationWorld.Person> ROLES = Map.of(
            "EMPLOYEE", WaviEvaluationWorld.EMPLOYEE, "HR", WaviEvaluationWorld.HR,
            "COMPANY_ADMIN", WaviEvaluationWorld.ADMIN);
    private static final List<String> DIMENSIONS = List.of("routing", "data", "content", "citations", "authorization");

    /** The outcome of one question asked by one role. */
    record Result(String scenario, String category, String role, String message, String route, String reply,
                  Map<String, List<String>> failures, Set<String> calls, String selectedTool,
                  Map<String, Object> canonicalArguments, boolean toolExecuted, boolean toolAccepted) {
        boolean passed() {
            return failures.values().stream().allMatch(List::isEmpty);
        }
    }

    /** One target turn within a fixture scenario, used when only that turn should consume a routing provider call. */
    record SelectedTurn(String scenarioId, String role, int turnIndex) {
        SelectedTurn {
            if (scenarioId == null || role == null || turnIndex < 0) throw new IllegalArgumentException("invalid selected turn");
        }
    }

    @Test
    void wavisAnswersMeetTheEvaluationDataset() throws IOException {
        List<Result> results = evaluate("/wavi-eval/scenarios.json");
        Path report = writeReport(results, "report.md");
        List<String> failed = results.stream().filter(result -> !result.passed())
                .map(result -> result.scenario() + " [" + result.role() + "] \"" + result.message() + "\" route="
                        + result.route() + " reply=\"" + abbreviate(result.reply()) + "\" " + compact(result.failures()))
                .toList();
        assertThat(results).as("evaluated questions").hasSizeGreaterThan(300);
        assertThat(failed).as("Wavi evaluation failures (report: " + report + ")").isEmpty();
    }

    /**
     * Fresh phrasings written after the fixes and never used to tune them: a measure of how Wavi generalizes, not a
     * regression gate. Its pass rate is reported (target/wavi-eval/holdout-report.md); only an authorization or
     * leak failure fails the build, since those must hold for any wording.
     */
    @Test
    void heldOutPhrasingsAreMeasuredAndNeverBreakAuthorization() throws IOException {
        // Round 1 informed the 2026-11-07 fixes; round 2 was written after them and has never been used for tuning.
        for (String round : List.of("holdout", "holdout-round2")) {
            List<Result> results = evaluate("/wavi-eval/" + round + ".json");
            Path report = writeReport(results, round + "-report.md");
            List<String> authorization = results.stream()
                    .filter(result -> !result.failures().get("authorization").isEmpty())
                    .map(result -> result.scenario() + " [" + result.role() + "] " + result.failures().get("authorization"))
                    .toList();
            assertThat(results).as(round + " questions").hasSizeGreaterThan(40);
            assertThat(authorization).as(round + " authorization failures (report: " + report + ")").isEmpty();
        }
    }

    /**
     * The sealed unseen set (Phase P0): written before it was first run and never used to tune routing. The test
     * first proves the file is byte-for-byte the sealed version (unseen-v1.seal), so nobody can quietly edit an
     * expectation to match a reply. Its pass rate is a measurement (target/wavi-eval/unseen-v1-report.md); only an
     * authorization or leak failure fails the build.
     */
    @Test
    void sealedUnseenSetIsUnchangedMeasuredAndNeverBreaksAuthorization() throws Exception {
        assertThat(sha256OfNormalized("/wavi-eval/unseen-v1.json"))
                .as("unseen-v1.json no longer matches its seal: publish a new version instead of editing it")
                .isEqualTo(sealedHash("/wavi-eval/unseen-v1.seal"));
        List<Result> results = evaluate("/wavi-eval/unseen-v1.json");
        Path report = writeReport(results, "unseen-v1-report.md");
        List<String> authorization = results.stream()
                .filter(result -> !result.failures().get("authorization").isEmpty())
                .map(result -> result.scenario() + " [" + result.role() + "] " + result.failures().get("authorization"))
                .toList();
        assertThat(results).as("unseen-v1 questions").hasSizeGreaterThan(150);
        assertThat(authorization).as("unseen-v1 authorization failures (report: " + report + ")").isEmpty();
    }

    /** SHA-256 of a resource with CRLF normalized to LF, so a Windows checkout hashes like any other. */
    static String sha256OfNormalized(String resource) throws Exception {
        try (InputStream in = WaviChatbotEvaluationTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            String text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        }
    }

    static String sealedHash(String resource) throws IOException {
        try (InputStream in = WaviChatbotEvaluationTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines()
                    .filter(line -> line.startsWith("sha256=")).map(line -> line.substring("sha256=".length()).trim())
                    .findFirst().orElseThrow(() -> new AssertionError("no sha256= line in " + resource));
        }
    }

    /** Shared by final-answer quality gates; runs the complete synthetic chat-service pipeline. */
    List<Result> evaluate(String resource) throws IOException {
        return evaluate(resource, options -> { });
    }

    /** The same evaluation with scenario options adjusted, e.g. a feature switched on. */
    List<Result> evaluate(String resource, java.util.function.Consumer<WaviEvaluationWorld.Options> adjust)
            throws IOException {
        return evaluate(resource, adjust, result -> { });
    }

    /** Receives each completed synthetic turn so a controlled live harness can checkpoint durable progress. */
    @FunctionalInterface
    interface ResultObserver {
        void completed(Result result) throws IOException;
    }

    List<Result> evaluate(String resource, java.util.function.Consumer<WaviEvaluationWorld.Options> adjust,
                          ResultObserver observer) throws IOException {
        JsonNode dataset = load(resource);
        List<Result> results = new ArrayList<>();
        for (JsonNode scenario : dataset.get("scenarios")) {
            for (String role : roles(scenario)) {
                for (Result result : run(scenario, role, adjust)) {
                    results.add(result);
                    observer.completed(result);
                }
            }
        }
        return results;
    }

    /**
     * Runs one fixture turn end-to-end. Earlier turns establish verified synthetic history locally before the supplied
     * router is attached, so only the selected user message consumes a provider decision.
     */
    Result evaluateSelectedTurn(String resource, SelectedTurn selected,
                                java.util.function.Consumer<WaviEvaluationWorld.Options> adjust) throws IOException {
        return evaluateSelectedTurnTraced(resource, selected, adjust).result();
    }

    /**
     * Provenance of the scored turn, observed rather than assumed: what the attached router proposed (parsed from
     * its own reply), how many provider calls this turn made, and which decision - with its source - the tool layer
     * actually executed. A row whose answer came from a deterministic route reports {@code executedSource}
     * FAST_PATH (or null when no tool ran), so a live score can never be credited to a decision that was not live.
     */
    record Trace(int routerCalls, String routerReplyStatus, WaviRouterDecision routerProposal,
                 WaviRouterDecision.Source executedSource, String executedTool, Map<String, Object> executedArguments,
                 boolean executed, String resultKind, String resultCode) {
        boolean answeredByRouter() {
            return executedSource != null && executedSource != WaviRouterDecision.Source.FAST_PATH
                    && !"REJECTED".equals(resultKind) && !"NO_ANSWER".equals(resultCode);
        }
    }

    record Traced(Result result, Trace trace) { }

    /** Records every reply of the attached provider for this turn; never alters it. */
    /** Result.calls() markers: a model decision passed the gate / its tool ran. */
    static final String ROUTER_ACCEPTED = "Router.decisionAccepted";
    static final String ROUTER_EXECUTED = "Router.toolExecuted";

    /** Marks a decision a model (not the deterministic fast path) made, and whether its tool then ran. */
    private static void markModelDecision(Observation observed, WaviToolLayer.Decided decided) {
        if (decided == null || decided.decision() == null
                || decided.decision().source() == WaviRouterDecision.Source.FAST_PATH) return;
        if (decided.outcome().result().kind() == WaviToolResult.Kind.REJECTED) return;
        observed.calls.add(ROUTER_ACCEPTED);
        if (decided.outcome().executed()) observed.calls.add(ROUTER_EXECUTED);
    }

    private static final class RecordingProvider implements WaviSemanticRouting.Provider {
        private final WaviSemanticRouting.Provider delegate;
        final List<String> replies = new ArrayList<>();
        int calls;
        int failures;

        RecordingProvider(WaviSemanticRouting.Provider delegate) { this.delegate = delegate; }
        public String name() { return delegate.name(); }
        public WaviRouterDecision.Source source() { return delegate.source(); }
        public boolean usable() { return delegate.usable(); }
        @Override public java.time.Duration attemptTimeout() { return delegate.attemptTimeout(); }
        @Override public void retryStarted() { delegate.retryStarted(); }
        public String route(WaviRouterPrompt.Request request, WaviSemanticRouting.Settings settings) {
            return routeWithUsage(request, settings).json();
        }
        @Override
        public WaviSemanticRouting.Reply routeWithUsage(WaviRouterPrompt.Request request,
                                                         WaviSemanticRouting.Settings settings) {
            calls++;
            try {
                WaviSemanticRouting.Reply reply = delegate.routeWithUsage(request, settings);
                replies.add(reply.json());
                return reply;
            } catch (RuntimeException failure) {
                failures++;
                throw failure;
            }
        }
    }

    Traced evaluateSelectedTurnTraced(String resource, SelectedTurn selected,
                                      java.util.function.Consumer<WaviEvaluationWorld.Options> adjust)
            throws IOException {
        JsonNode dataset = load(resource);
        JsonNode scenario = null;
        for (JsonNode candidate : dataset.get("scenarios")) {
            if (selected.scenarioId().equals(candidate.path("id").asText())) {
                scenario = candidate;
                break;
            }
        }
        if (scenario == null || !roles(scenario).contains(selected.role())) {
            throw new IllegalArgumentException("selected fixture turn is unavailable: " + selected);
        }
        List<JsonNode> turns = new ArrayList<>();
        if (scenario.has("turns")) scenario.get("turns").forEach(turns::add);
        else turns.add(scenario);
        if (selected.turnIndex() >= turns.size()) throw new IllegalArgumentException("selected fixture turn is unavailable: " + selected);

        WaviEvaluationWorld.Options options = options(scenario.get("options"));
        adjust.accept(options);
        WaviSemanticRouting.Provider provider = options.routerProvider;
        WaviSemanticRouting.Settings providerSettings = options.routerSettings;
        options.routerProvider = null;
        options.routerSettings = null;
        WaviEvaluationWorld world = new WaviEvaluationWorld(actorFor(selected.role(), options), options);
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        AtomicReference<WaviToolLayer.Outcome> outcome = new AtomicReference<>();
        AtomicReference<WaviToolLayer.Decided> decided = new AtomicReference<>();
        List<WaviToolLayer.Decided> decisions = new java.util.concurrent.CopyOnWriteArrayList<>();
        if (world.service.toolLayer() != null) {
            world.service.toolLayer().observe(outcome::set);
            world.service.toolLayer().observeDecisions(item -> { decided.set(item); decisions.add(item); });
        }
        RecordingProvider recording = provider == null ? null : new RecordingProvider(provider);
        for (int index = 0; index <= selected.turnIndex(); index++) {
            if (index == selected.turnIndex() && recording != null) {
                world.service.useSemanticRouting(new WaviSemanticRouting(true, List.of(recording),
                        providerSettings == null ? WaviSemanticRouting.Settings.defaults() : providerSettings,
                        java.time.Clock.fixed(WaviEvaluationWorld.NOW, java.time.ZoneOffset.UTC)));
            }
            JsonNode turn = turns.get(index);
            String message = turn.get("message").asText();
            Mockito.clearInvocations(world.claude, world.ollama, world.retrieval, world.timesheets,
                    world.profileChanges, world.employees, world.documents);
            outcome.set(null);
            decided.set(null);
            decisions.clear();
            MyHrChatResponse response = null;
            Throwable error = null;
            try {
                response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            } catch (RuntimeException failure) {
                error = failure;
            }
            if (index == selected.turnIndex()) {
                Observation observed = observe(world, response, error);
                // A directly attached (live) provider bypasses the mocked client, so its calls are not in the mock's
                // invocations: record them from the provider boundary, plus whether a model decision was accepted by
                // the tool layer and executed. ROUTER_* markers count only model-sourced decisions.
                if (recording != null && recording.calls > 0) observed.calls.add("Claude.routeJson");
                decisions.forEach(item -> markModelDecision(observed, item));
                JsonNode expect = expectation(turn, selected.role());
                Map<String, List<String>> failures = new LinkedHashMap<>();
                DIMENSIONS.forEach(dimension -> failures.put(dimension, new ArrayList<>()));
                check(expect, observed, failures);
                checkInvariants(world, message, history, observed, failures);
                WaviToolLayer.Outcome selectedOutcome = outcome.get();
                String id = scenario.get("id").asText() + (turns.size() > 1 ? "#" + (index + 1) : "");
                Result result = new Result(id, scenario.get("category").asText(), selected.role(), message, observed.route,
                        observed.reply, failures, Set.copyOf(observed.calls), selectedOutcome == null ? null : selectedOutcome.tool(),
                        selectedOutcome == null ? Map.of() : selectedOutcome.arguments(),
                        selectedOutcome != null && selectedOutcome.executed(), selectedOutcome != null
                                && selectedOutcome.result().kind() != WaviToolResult.Kind.REJECTED);
                return new Traced(result, trace(recording, decided.get()));
            }
            if (response != null) {
                MyHrChatResponse.HistoryProvenance stamps = response.historyProvenance();
                history.add(new MyHrChatHistoryMessage("user", message, stamps == null ? null : stamps.user()));
                history.add(new MyHrChatHistoryMessage("assistant", response.message(),
                        stamps == null ? null : stamps.assistant()));
            }
        }
        throw new IllegalStateException("selected turn did not run: " + selected);
    }

    private static Trace trace(RecordingProvider recording, WaviToolLayer.Decided decided) {
        int calls = recording == null ? 0 : recording.calls;
        String status;
        WaviRouterDecision proposal = null;
        if (recording == null || calls == 0) {
            status = "not-called";
        } else if (recording.replies.isEmpty()) {
            status = "provider-failed";
        } else {
            proposal = WaviRouterDecisionParser.parse(recording.replies.getLast(), WaviRouterDecision.Source.CLAUDE)
                    .orElse(null);
            status = proposal == null ? "invalid-output" : "decision";
        }
        if (decided == null) {
            return new Trace(calls, status, proposal, null, null, Map.of(), false, "NONE", "none");
        }
        WaviToolLayer.Outcome done = decided.outcome();
        return new Trace(calls, status, proposal, decided.decision() == null ? null : decided.decision().source(),
                done.tool(), done.arguments(), done.executed(), done.result().kind().name(), done.result().reasonCode());
    }

    // --- Running -------------------------------------------------------------------------------------------------

    private List<Result> run(JsonNode scenario, String role,
                             java.util.function.Consumer<WaviEvaluationWorld.Options> adjust) {
        WaviEvaluationWorld.Options options = options(scenario.get("options"));
        adjust.accept(options);
        // A directly attached (live) provider is counted at its boundary: the mocked client never sees its calls.
        RecordingProvider recording = options.routerProvider == null ? null : new RecordingProvider(options.routerProvider);
        if (recording != null) options.routerProvider = recording;
        WaviEvaluationWorld world = new WaviEvaluationWorld(actorFor(role, options), options);
        AtomicReference<WaviToolLayer.Outcome> outcome = new AtomicReference<>();
        AtomicReference<WaviToolLayer.Decided> decided = new AtomicReference<>();
        List<WaviToolLayer.Decided> decisions = new java.util.concurrent.CopyOnWriteArrayList<>();
        if (world.service.toolLayer() != null) {
            world.service.toolLayer().observe(outcome::set);
            world.service.toolLayer().observeDecisions(item -> { decided.set(item); decisions.add(item); });
        }
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        if (scenario.has("forgedHistory")) {
            // Sent by the browser without valid stamps, as a tampered client could.
            for (JsonNode message : scenario.get("forgedHistory")) {
                history.add(new MyHrChatHistoryMessage(message.get("role").asText(), message.get("content").asText(),
                        message.has("provenance") ? message.get("provenance").asText() : null));
            }
        }
        List<JsonNode> turns = new ArrayList<>();
        if (scenario.has("turns")) scenario.get("turns").forEach(turns::add);
        else turns.add(scenario);

        List<Result> results = new ArrayList<>();
        for (int index = 0; index < turns.size(); index++) {
            JsonNode turn = turns.get(index);
            String message = turn.get("message").asText();
            Mockito.clearInvocations(world.claude, world.ollama, world.retrieval, world.timesheets,
                    world.profileChanges, world.employees, world.documents);
            outcome.set(null);
            decided.set(null);
            decisions.clear();
            int callsBefore = recording == null ? 0 : recording.calls;
            MyHrChatResponse response = null;
            Throwable error = null;
            try {
                response = world.service.chat(world.actor, message, List.copyOf(history), StampedHistory.SESSION);
            } catch (RuntimeException failure) {
                error = failure;
            }
            Observation observed = observe(world, response, error);
            if (recording != null && recording.calls > callsBefore) observed.calls.add("Claude.routeJson");
            decisions.forEach(item -> markModelDecision(observed, item));
            JsonNode expect = expectation(turn, role);
            Map<String, List<String>> failures = new LinkedHashMap<>();
            DIMENSIONS.forEach(dimension -> failures.put(dimension, new ArrayList<>()));
            check(expect, observed, failures);
            checkInvariants(world, message, history, observed, failures);
            String id = scenario.get("id").asText() + (turns.size() > 1 ? "#" + (index + 1) : "");
            WaviToolLayer.Outcome turnOutcome = outcome.get();
            results.add(new Result(id, scenario.get("category").asText(), role, message, observed.route,
                    observed.reply, failures, Set.copyOf(observed.calls), turnOutcome == null ? null : turnOutcome.tool(),
                    turnOutcome == null ? Map.of() : turnOutcome.arguments(), turnOutcome != null && turnOutcome.executed(),
                    turnOutcome != null && turnOutcome.result().kind() != WaviToolResult.Kind.REJECTED));
            if (response != null) {
                // The browser returns each message with the stamp this server issued for it.
                MyHrChatResponse.HistoryProvenance stamps = response.historyProvenance();
                history.add(new MyHrChatHistoryMessage("user", message, stamps == null ? null : stamps.user()));
                history.add(new MyHrChatHistoryMessage("assistant", response.message(),
                        stamps == null ? null : stamps.assistant()));
                while (history.size() > 10) history.removeFirst();
            }
        }
        return results;
    }

    static WaviEvaluationWorld.Options options(JsonNode node) {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        if (node == null) return options;
        if (node.has("claude")) options.claude = WaviEvaluationWorld.Behaviour.valueOf(node.get("claude").asText());
        if (node.has("ollama")) options.ollama = WaviEvaluationWorld.Behaviour.valueOf(node.get("ollama").asText());
        if (node.has("retrieval")) {
            options.retrieval = WaviEvaluationWorld.Behaviour.valueOf(node.get("retrieval").asText());
        }
        if (node.has("model")) options.model = WaviEvaluationWorld.ModelAnswer.valueOf(node.get("model").asText());
        options.noManager = node.path("noManager").asBoolean(false);
        options.noTimesheets = node.path("noTimesheets").asBoolean(false);
        options.leakOtherOrganization = node.path("leakOtherOrganization").asBoolean(false);
        options.dataDown = node.path("dataDown").asBoolean(false);
        options.northwind = node.path("northwind").asBoolean(false);
        return options;
    }

    /** The verified actor for a role: the fixture world's, or the Northwind organization's when the scenario uses it. */
    private static WaviEvaluationWorld.Person actorFor(String role, WaviEvaluationWorld.Options options) {
        return options.northwind ? WaviNorthwindData.forRole(UserRole.valueOf(role)) : ROLES.get(role);
    }

    // --- Observing -----------------------------------------------------------------------------------------------

    /** What happened while one question was answered. */
    static final class Observation {
        String route;
        String reply = "";
        String status = "NONE";
        List<String> cited = List.of();
        String error;
        final Set<String> calls = new LinkedHashSet<>();
        final List<Invocation> dataInvocations = new ArrayList<>();
        final List<Invocation> retrievalInvocations = new ArrayList<>();
        /** Every text Claude received in this turn: messages, history, prompts and documents. */
        final StringBuilder claudeInput = new StringBuilder();
        /** What a semantic router received in this turn (its per-message input; the system text is fixed). */
        final StringBuilder routerInput = new StringBuilder();
        boolean claudeDocuments;
        boolean providerAnswered;
        boolean policyContextSent;
    }

    private static Observation observe(WaviEvaluationWorld world, MyHrChatResponse response, Throwable error) {
        Observation observed = new Observation();
        for (Object mock : world.dataMocks()) {
            for (Invocation invocation : Mockito.mockingDetails(mock).getInvocations()) {
                observed.dataInvocations.add(invocation);
                observed.calls.add(simpleName(mock) + "." + invocation.getMethod().getName());
            }
        }
        for (Invocation invocation : Mockito.mockingDetails(world.retrieval).getInvocations()) {
            if (invocation.getMethod().getName().equals("retrieve")) {
                observed.retrievalInvocations.add(invocation);
                observed.calls.add("KnowledgeRetrievalService.retrieve");
            }
        }
        for (Invocation invocation : Mockito.mockingDetails(world.claude).getInvocations()) {
            String method = invocation.getMethod().getName();
            if (method.startsWith("routeJson")) {
                observed.calls.add("Claude.routeJson");
                observed.routerInput.append(text(invocation.getArgument(1))).append('\n');
                continue;
            }
            if (method.equals("chatWithDocuments")) observed.claudeDocuments = true;
            if (!method.startsWith("chat")) continue;
            observed.calls.add("Claude." + method);
            for (Object argument : invocation.getArguments()) observed.claudeInput.append(text(argument)).append('\n');
            if (invocation.getArgument(0) instanceof MyHrGroundedPrompt prompt && prompt.containsPolicyContext()) {
                observed.policyContextSent = true;
            } else {
                observed.providerAnswered = true;
            }
        }
        for (Invocation invocation : Mockito.mockingDetails(world.ollama).getInvocations()) {
            if (!invocation.getMethod().getName().equals("chat")) continue;
            observed.calls.add("Ollama.chat");
            if (invocation.getArgument(0) instanceof MyHrGroundedPrompt prompt && prompt.containsPolicyContext()) {
                observed.policyContextSent = true;
            } else {
                observed.providerAnswered = true;
            }
        }
        if (error != null) {
            observed.error = error.getClass().getSimpleName();
            observed.route = "ERROR";
            return observed;
        }
        observed.reply = response.message() == null ? "" : response.message();
        observed.status = response.knowledgeStatus() == null ? "NONE" : response.knowledgeStatus().name();
        observed.cited = response.citations().stream().map(citation -> citation.title()).toList();
        boolean policyFixedReply = observed.reply.equals(MyHrKnowledgeGrounding.POLICY_NOT_FOUND_ANSWER)
                || observed.reply.equals(MyHrKnowledgeGrounding.POLICY_UNAVAILABLE_ANSWER)
                || observed.reply.equals(MyHrKnowledgeGrounding.UNVERIFIED_ANSWER);
        if (observed.reply.equals(MyHrScopeGuard.OUT_OF_SCOPE_REPLY)) observed.route = "OUT_OF_SCOPE";
        else if (observed.reply.equals(MyHrChatService.UNRECOGNIZED_REPLY)) observed.route = "UNRECOGNIZED";
        else if (observed.policyContextSent || (policyFixedReply && !observed.providerAnswered)) observed.route = "POLICY";
        else if (observed.providerAnswered) observed.route = "AI";
        else if (!observed.dataInvocations.isEmpty()) observed.route = "LOOKUP";
        else observed.route = "FIXED";
        return observed;
    }

    private static String simpleName(Object mock) {
        return Mockito.mockingDetails(mock).getMockCreationSettings().getTypeToMock().getSimpleName();
    }

    private static String text(Object argument) {
        if (argument == null) return "";
        if (argument instanceof String text) return text;
        if (argument instanceof Collection<?> items) {
            StringBuilder out = new StringBuilder();
            items.forEach(item -> out.append(text(item)).append('\n'));
            return out.toString();
        }
        if (argument instanceof MyHrChatHistoryMessage message) return message.role() + ": " + message.content();
        if (argument instanceof MyHrGroundedPrompt prompt) {
            return text(prompt.history()) + "\n" + prompt.userMessage();
        }
        if (argument instanceof MyHrGroundedDocumentPrompt prompt) {
            return text(prompt.history()) + "\n" + prompt.question() + "\n" + prompt.documents();
        }
        return String.valueOf(argument);
    }

    // --- Checking ------------------------------------------------------------------------------------------------

    private static JsonNode expectation(JsonNode turn, String role) {
        Map<String, JsonNode> merged = new LinkedHashMap<>();
        if (turn.has("expect")) turn.get("expect").properties().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
        JsonNode byRole = turn.path("byRole").path(role);
        if (!byRole.isMissingNode()) byRole.properties().forEach(entry -> merged.put(entry.getKey(), entry.getValue()));
        return JSON.valueToTree(merged);
    }

    private static void check(JsonNode expect, Observation observed, Map<String, List<String>> failures) {
        String reply = observed.reply.toLowerCase(Locale.ROOT);
        if (expect.has("route")) {
            Set<String> allowed = values(expect.get("route"));
            // An off-topic question is met by either safe non-answer: the scope refusal, or the honest "I didn't
            // understand" reply (no provider, no data). One direction only: UNRECOGNIZED never satisfies an
            // expectation that the question be answered.
            boolean safeNonAnswer = allowed.contains("OUT_OF_SCOPE") && "UNRECOGNIZED".equals(observed.route);
            if (!allowed.contains(observed.route) && !safeNonAnswer) {
                failures.get("routing").add("route " + observed.route + " not in " + allowed);
            }
        }
        if (expect.has("routeNot") && values(expect.get("routeNot")).contains(observed.route)) {
            failures.get("routing").add("route must not be " + observed.route);
        }
        if (expect.has("error") && !expect.get("error").asText().equals(observed.error)) {
            failures.get("routing").add("expected error " + expect.get("error").asText() + " but got " + observed.error);
        }
        if (observed.error != null && !expect.has("error")) {
            failures.get("routing").add("unexpected error " + observed.error);
        }
        for (String call : values(expect.get("calls"))) {
            if (!observed.calls.contains(call)) failures.get("data").add("expected call " + call);
        }
        for (String call : values(expect.get("noCalls"))) {
            if (observed.calls.contains(call)) failures.get("data").add("unexpected call " + call);
        }
        if (expect.path("noData").asBoolean(false) && !observed.dataInvocations.isEmpty()) {
            failures.get("data").add("expected no record lookup but saw " + observed.calls);
        }
        for (String text : values(expect.get("contains"))) {
            if (!reply.contains(text.toLowerCase(Locale.ROOT))) failures.get("content").add("missing \"" + text + "\"");
        }
        if (expect.has("containsAny") && values(expect.get("containsAny")).stream()
                .noneMatch(text -> reply.contains(text.toLowerCase(Locale.ROOT)))) {
            failures.get("content").add("missing any of " + values(expect.get("containsAny")));
        }
        for (String text : values(expect.get("notContains"))) {
            if (reply.contains(text.toLowerCase(Locale.ROOT))) failures.get("content").add("must not say \"" + text + "\"");
        }
        if (expect.has("equals") && !observed.reply.equals(expect.get("equals").asText())) {
            failures.get("content").add("reply differs from the expected exact text");
        }
        if (expect.has("status") && !values(expect.get("status")).contains(observed.status)) {
            failures.get("citations").add("status " + observed.status + " not in " + values(expect.get("status")));
        }
        if (expect.has("cited")) {
            Set<String> wanted = values(expect.get("cited"));
            if (!new LinkedHashSet<>(observed.cited).equals(wanted)) {
                failures.get("citations").add("cited " + observed.cited + " but expected " + wanted);
            }
        }
    }

    /** Authorization and leak checks that hold for every answer, whatever the scenario. */
    private static void checkInvariants(WaviEvaluationWorld world, String message, List<MyHrChatHistoryMessage> history,
                                        Observation observed, Map<String, List<String>> failures) {
        List<String> authorization = failures.get("authorization");
        for (Invocation invocation : observed.dataInvocations) {
            Object first = invocation.getArguments().length == 0 ? null : invocation.getArgument(0);
            if (first instanceof String userId && !userId.equals(world.actor.userId())) {
                authorization.add(invocation.getMethod().getName() + " was called for " + userId);
            }
        }
        for (Invocation invocation : observed.retrievalInvocations) {
            if (invocation.getArgument(0) != world.actor) authorization.add("retrieval used another actor");
        }
        if (observed.claudeDocuments) authorization.add("policy documents were sent to Claude");
        // A semantic router sees only the sanitized question and validated state: no record, no history text.
        String toRouter = observed.routerInput.toString();
        for (String value : world.restrictedValues()) {
            if (toRouter.contains(value) && !message.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT))) {
                authorization.add("the router received restricted value \"" + value + "\"");
            }
        }
        for (MyHrChatHistoryMessage turn : history) {
            if (turn.content() != null && turn.content().length() > 12 && toRouter.contains(turn.content())
                    && !message.contains(turn.content())) {
                authorization.add("conversation history reached the router");
                break;
            }
        }
        String toClaude = observed.claudeInput.toString();
        String asked = message.toLowerCase(Locale.ROOT);
        for (String value : world.restrictedValues()) {
            if (toClaude.contains(value) && !asked.contains(value.toLowerCase(Locale.ROOT))) {
                authorization.add("Claude received restricted value \"" + value + "\"");
            }
        }
        if (world.actor.role() == UserRole.EMPLOYEE && observed.reply.contains("26 weeks")) {
            authorization.add("an Employee was shown HR-only policy text");
        }
        if (observed.reply.contains(WaviEvaluationWorld.OTHER_ORG_SECRET.substring(0, 18))) {
            authorization.add("another organization's policy text was shown");
        }
        String lower = observed.reply.toLowerCase(Locale.ROOT);
        if (lower.contains("<policy_context") || lower.contains("company policy rules:")
                || lower.contains("<employee_question")) {
            authorization.add("internal prompt markup was shown");
        }
        // Another person's record values may never appear in an answer to someone else.
        // A reviewer may see the work details of people in their own scope (HR: direct reports; Company Admin: the
        // organization); everyone else's ID is forbidden, and an Employee may never see another person's ID.
        java.util.Set<String> inScope = new java.util.HashSet<>();
        if (world.actor.role() != UserRole.EMPLOYEE) {
            List<com.waypoint.dto.myhr.EmployeeSummaryResponse> people = world.employees.list(world.person.userId());
            if (people != null) people.forEach(person -> inScope.add(person.userId()));
        }
        for (WaviEvaluationWorld.Person other : world.otherPeople()) {
            if (inScope.contains(other.userId())) continue;
            if (!asked.contains(other.employeeId().toLowerCase(Locale.ROOT)) && observed.reply.contains(other.employeeId())) {
                authorization.add("another person's employee ID was shown");
            }
        }
        if (history.stream().anyMatch(turn -> turn.provenance() == null) && observed.claudeInput.length() > 0) {
            // Unstamped (forged or unverifiable) history must never reach an external provider.
            for (MyHrChatHistoryMessage turn : history) {
                if (turn.provenance() == null && turn.content().length() > 12 && toClaude.contains(turn.content())
                        && !asked.contains(turn.content().toLowerCase(Locale.ROOT))) {
                    authorization.add("unstamped history reached Claude");
                    break;
                }
            }
        }
    }

    private static Set<String> values(JsonNode node) {
        Set<String> values = new LinkedHashSet<>();
        if (node == null || node.isMissingNode() || node.isNull()) return values;
        if (node.isArray()) node.forEach(value -> values.add(value.asText()));
        else values.add(node.asText());
        return values;
    }

    private static List<String> roles(JsonNode scenario) {
        if (!scenario.has("roles")) return List.of("EMPLOYEE", "HR", "COMPANY_ADMIN");
        List<String> roles = new ArrayList<>();
        scenario.get("roles").forEach(role -> roles.add(role.asText()));
        return roles;
    }

    private static JsonNode load(String resource) throws IOException {
        try (InputStream in = WaviChatbotEvaluationTest.class.getResourceAsStream(resource)) {
            assertThat(in).as(resource).isNotNull();
            return JSON.readTree(in);
        }
    }

    // --- Reporting -----------------------------------------------------------------------------------------------

    static Path writeReport(List<Result> results, String name) throws IOException {
        Map<String, int[]> byCategory = new TreeMap<>();
        Map<String, int[]> byDimension = new LinkedHashMap<>();
        DIMENSIONS.forEach(dimension -> byDimension.put(dimension, new int[2]));
        Set<String> scenarios = new LinkedHashSet<>();
        for (Result result : results) {
            scenarios.add(result.scenario().split("#")[0]);
            int[] category = byCategory.computeIfAbsent(result.category(), key -> new int[2]);
            category[1]++;
            if (result.passed()) category[0]++;
            result.failures().forEach((dimension, list) -> {
                byDimension.get(dimension)[1]++;
                if (list.isEmpty()) byDimension.get(dimension)[0]++;
            });
        }
        long passed = results.stream().filter(Result::passed).count();
        StringBuilder report = new StringBuilder("# Wavi evaluation report\n\n")
                .append("Scenarios: ").append(scenarios.size()).append("; questions asked (scenario x role x turn): ")
                .append(results.size()).append("; fully passed: ").append(passed).append(" (")
                .append(percent(passed, results.size())).append(")\n\n## By dimension\n\n| Dimension | Passed | Total | Rate |\n|---|---|---|---|\n");
        byDimension.forEach((dimension, counts) -> report.append("| ").append(dimension).append(" | ").append(counts[0])
                .append(" | ").append(counts[1]).append(" | ").append(percent(counts[0], counts[1])).append(" |\n"));
        report.append("\n## By category\n\n| Category | Passed | Total | Rate |\n|---|---|---|---|\n");
        byCategory.forEach((category, counts) -> report.append("| ").append(category).append(" | ").append(counts[0])
                .append(" | ").append(counts[1]).append(" | ").append(percent(counts[0], counts[1])).append(" |\n"));
        report.append("\n## Failures\n\n");
        results.stream().filter(result -> !result.passed()).forEach(result -> report.append("- **")
                .append(result.scenario()).append("** [").append(result.role()).append("] `").append(result.message())
                .append("` route=").append(result.route()).append(" ").append(compact(result.failures()))
                .append("\n  - reply: ").append(abbreviate(result.reply())).append('\n'));
        Path directory = Path.of("target", "wavi-eval");
        Files.createDirectories(directory);
        Path file = directory.resolve(name);
        Files.writeString(file, report.toString());
        System.out.println(report.substring(0, report.indexOf("## Failures")));
        return file.toAbsolutePath();
    }

    private static String percent(long part, long total) {
        return total == 0 ? "-" : String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }

    private static String compact(Map<String, List<String>> failures) {
        Map<String, List<String>> nonEmpty = new LinkedHashMap<>();
        failures.forEach((dimension, list) -> {
            if (!list.isEmpty()) nonEmpty.put(dimension, list);
        });
        return nonEmpty.toString();
    }

    private static String abbreviate(String text) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ");
        return flat.length() > 160 ? flat.substring(0, 157) + "..." : flat;
    }
}
