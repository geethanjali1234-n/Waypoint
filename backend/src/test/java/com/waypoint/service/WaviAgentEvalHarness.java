package com.waypoint.service;

import com.sun.net.httpserver.HttpServer;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.service.WaviAgentEvalQuestions.Expect;
import com.waypoint.service.WaviAgentEvalQuestions.Phase;
import com.waypoint.service.WaviAgentEvalQuestions.Question;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The evaluation harness for the Wavi agent: the existing integrated chatbot (MyHrChatService + WaviAgentLoop + the
 * real ClaudeChatClient) over the SYNTHETIC evaluation world, driven by {@link WaviAgentEvalQuestions}.
 *
 * <p>Two targets share every line of this code: a local stub Anthropic server (free; the dry-run) and the real API
 * (only through {@code WaviLiveAgentEvaluation}, which needs explicit approval, an environment key and a spend cap).
 *
 * <p>Safety, checked before any request leaves and after every message, each of which STOPS the run:
 * the combined spend budget (a persisted ledger; a request that could exceed what is left is never sent), a
 * forbidden literal or sensitive shape in the outbound payload, a tool executed for a role that lacks it, a forbidden
 * fact in an answer shown to the user, and a shown agent answer that fails an independent grounding re-check.
 */
final class WaviAgentEvalHarness {

    // Claude Haiku 5.5, per million tokens, prompts up to 100K (price cached 2026-11-03; re-check before a live run).
    static final BigDecimal INPUT_PER_MTOK = new BigDecimal("0.10");
    static final BigDecimal OUTPUT_PER_MTOK = new BigDecimal("0.50");
    static final BigDecimal CACHE_WRITE_PER_MTOK = new BigDecimal("0.125");
    static final BigDecimal CACHE_READ_PER_MTOK = new BigDecimal("0.01");
    static final int MAX_OUTPUT_TOKENS = 1024;
    /** Per RUN (a label such as "v1" or "v2"); the SPEND cap is one lifetime total across every run in the ledger. */
    static final int MAX_MESSAGES = 100;
    static final int MAX_REQUESTS = 300;
    /** What one run may spend on its own, on top of what earlier runs already spent. */
    static final BigDecimal DEFAULT_RUN_CAP_USD = new BigDecimal("0.15");
    static final BigDecimal HARD_CAP_USD = new BigDecimal("0.50");
    static final String MODEL = "claude-haiku-5-5";
    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final BigDecimal MILLION = new BigDecimal(1_000_000);

    private WaviAgentEvalHarness() {
    }

    // --- Budget ------------------------------------------------------------------------------------------------------

    static final class BudgetExhausted extends RuntimeException {
        BudgetExhausted(String message) {
            super(message);
        }
    }

    static final class SafetyStop extends RuntimeException {
        SafetyStop(String message) {
            super(message);
        }
    }

    /**
     * ONE spend budget for every run, persisted after every change, so an interrupted run cannot forget what it spent
     * and a later run starts from what the earlier ones cost (the first real run, "v1", is in the ledger). Three limits, all
     * checked BEFORE a request is sent:
     * <ul>
     *   <li>the lifetime cap: everything ever spent + this request's worst case &lt;= the cap (never above $0.50);</li>
     *   <li>the run cap: what THIS run has spent + this request's worst case &lt;= the run's own allowance;</li>
     *   <li>per-run message and request counts (a new run label starts at zero; spend does not).</li>
     * </ul>
     * A failed or usage-less request is charged at its worst case; an unreadable ledger refuses to spend.
     */
    static final class Budget {
        /** Counters of one run label. */
        private static final class Run {
            int messages;
            int requests;
            BigDecimal startSpent;

            Run(BigDecimal startSpent) {
                this.startSpent = startSpent;
            }
        }

        private final BigDecimal cap;
        private final Path ledger;
        private final String label;
        private final BigDecimal runCap;
        private final int maxMessages;
        private final int maxRequests;
        private BigDecimal spent = BigDecimal.ZERO;
        private int unknown;
        private final Map<String, Run> runs = new LinkedHashMap<>();
        private final Run run;

        Budget(BigDecimal cap, Path ledger, int maxMessages, int maxRequests) {
            this(cap, ledger, "v1", null, maxMessages, maxRequests);
        }

        Budget(BigDecimal cap, Path ledger, String label, BigDecimal runCap, int maxMessages, int maxRequests) {
            if (cap == null || cap.signum() <= 0 || cap.compareTo(HARD_CAP_USD) > 0) {
                throw new IllegalArgumentException("the combined spend cap must be positive and at most $" + HARD_CAP_USD);
            }
            if (label == null || !label.matches("[A-Za-z0-9._-]{1,20}")) {
                throw new IllegalArgumentException("the run label must be 1-20 letters, digits, '.', '_' or '-'");
            }
            if (runCap != null && runCap.signum() <= 0) throw new IllegalArgumentException("the run cap must be positive");
            this.cap = cap;
            this.ledger = ledger;
            this.label = label;
            this.runCap = runCap;
            this.maxMessages = maxMessages;
            this.maxRequests = maxRequests;
            load();
            this.run = runs.computeIfAbsent(label, ignored -> new Run(spent));
        }

        private void load() {
            if (ledger == null || !Files.exists(ledger)) return;
            try {
                JsonNode node = JSON.readTree(Files.readString(ledger));
                spent = new BigDecimal(node.path("spentUsd").asString("0"));
                unknown = node.path("unknownCostRequests").asInt(0);
                JsonNode saved = node.path("runs");
                if (saved.isObject()) {
                    for (String name : saved.propertyNames()) {
                        JsonNode entry = saved.path(name);
                        Run loaded = new Run(new BigDecimal(entry.path("startSpentUsd").asString("0")));
                        loaded.messages = entry.path("messages").asInt(0);
                        loaded.requests = entry.path("requests").asInt(0);
                        runs.put(name, loaded);
                    }
                } else if (node.has("messages") || node.has("requests")) {
                    // The first ledger format had one set of counters: that was the first real run, "v1".
                    Run legacy = new Run(BigDecimal.ZERO);
                    legacy.messages = node.path("messages").asInt(0);
                    legacy.requests = node.path("requests").asInt(0);
                    runs.put("v1", legacy);
                }
            } catch (IOException | RuntimeException unreadable) {
                // A ledger that cannot be read means previous spending cannot be established: nothing may be spent.
                throw new IllegalStateException("the spend ledger exists but cannot be read; refusing to spend", unreadable);
            }
        }

        private void save() {
            if (ledger == null) return;
            try {
                Files.createDirectories(ledger.toAbsolutePath().getParent());
                Path temp = ledger.resolveSibling(ledger.getFileName() + ".tmp");
                Map<String, Object> savedRuns = new LinkedHashMap<>();
                int totalMessages = 0;
                int totalRequests = 0;
                for (Map.Entry<String, Run> entry : runs.entrySet()) {
                    Map<String, Object> value = new LinkedHashMap<>();
                    value.put("messages", entry.getValue().messages);
                    value.put("requests", entry.getValue().requests);
                    value.put("startSpentUsd", entry.getValue().startSpent.toPlainString());
                    savedRuns.put(entry.getKey(), value);
                    totalMessages += entry.getValue().messages;
                    totalRequests += entry.getValue().requests;
                }
                Map<String, Object> file = new LinkedHashMap<>();
                file.put("spentUsd", spent.toPlainString());
                file.put("capUsd", cap.toPlainString());
                file.put("unknownCostRequests", unknown);
                file.put("messages", totalMessages);
                file.put("requests", totalRequests);
                file.put("runs", savedRuns);
                Files.writeString(temp, JSON.writeValueAsString(file));
                Files.move(temp, ledger, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                throw new IllegalStateException("cannot persist the spend ledger; stopping", failure);
            }
        }

        /** Before a request: every limit must hold with this request's worst case; otherwise nothing is sent. */
        synchronized void reserve(BigDecimal worstCase) {
            if (run.requests >= maxRequests) throw new BudgetExhausted("request cap " + maxRequests + " reached");
            if (spent.add(worstCase).compareTo(cap) > 0) {
                throw new BudgetExhausted("a request (worst case $" + worstCase.setScale(5, RoundingMode.UP)
                        + ") could exceed the remaining budget $" + remaining().setScale(5, RoundingMode.DOWN));
            }
            if (runCap != null && runSpent().add(worstCase).compareTo(runCap) > 0) {
                throw new BudgetExhausted("a request (worst case $" + worstCase.setScale(5, RoundingMode.UP)
                        + ") could exceed this run's allowance $" + runCap.toPlainString() + " (run '" + label + "' has spent $"
                        + runSpent().setScale(5, RoundingMode.UP) + ")");
            }
            run.requests++;
            save();
        }

        synchronized void recordMessage() {
            if (run.messages >= maxMessages) throw new BudgetExhausted("message cap " + maxMessages + " reached");
            run.messages++;
            save();
        }

        synchronized void recordActual(ClaudeChatClient.RouteUsage usage) {
            spent = spent.add(cost(usage));
            save();
        }

        /** A failed or usage-less request may still have been billed: it is charged at its worst case. */
        synchronized void recordUnknown(BigDecimal worstCase) {
            spent = spent.add(worstCase);
            unknown++;
            save();
        }

        /** Lifetime spend across every run in the ledger. */
        synchronized BigDecimal spent() {
            return spent;
        }

        /** What THIS run has spent. */
        synchronized BigDecimal runSpent() {
            return spent.subtract(run.startSpent).max(BigDecimal.ZERO);
        }

        synchronized BigDecimal remaining() {
            return cap.subtract(spent).max(BigDecimal.ZERO);
        }

        /** This run's requests. */
        synchronized int requests() {
            return run.requests;
        }

        /** This run's messages. */
        synchronized int messages() {
            return run.messages;
        }

        synchronized int unknownCostRequests() {
            return unknown;
        }

        String label() {
            return label;
        }

        BigDecimal cap() {
            return cap;
        }
    }

    static BigDecimal cost(ClaudeChatClient.RouteUsage usage) {
        if (usage == null) return BigDecimal.ZERO;
        return INPUT_PER_MTOK.multiply(BigDecimal.valueOf(usage.inputTokens()))
                .add(CACHE_WRITE_PER_MTOK.multiply(BigDecimal.valueOf(usage.cacheWriteTokens())))
                .add(CACHE_READ_PER_MTOK.multiply(BigDecimal.valueOf(usage.cacheReadTokens())))
                .add(OUTPUT_PER_MTOK.multiply(BigDecimal.valueOf(usage.outputTokens())))
                .divide(MILLION, 8, RoundingMode.UP);
    }

    /** Worst case of one request: every character of the body as 0.4 token charged at the highest input price, plus a full output. */
    static BigDecimal worstCase(int bodyChars) {
        BigDecimal inputTokens = BigDecimal.valueOf(Math.ceil(bodyChars / 2.5));
        return CACHE_WRITE_PER_MTOK.multiply(inputTokens).add(OUTPUT_PER_MTOK.multiply(BigDecimal.valueOf(MAX_OUTPUT_TOKENS)))
                .divide(MILLION, 8, RoundingMode.UP);
    }

    // --- Configuration and results -------------------------------------------------------------------------------------

    record Config(String mode, String apiUrl, String apiKey, BigDecimal capUsd, Set<Phase> phases, Path outDir, Path ledger,
                  int maxMessages, int maxRequests, String tag, String label, BigDecimal runCapUsd) {
        /** The first real run's shape: label "v1", no separate run allowance (the lifetime cap applies). */
        Config(String mode, String apiUrl, String apiKey, BigDecimal capUsd, Set<Phase> phases, Path outDir, Path ledger,
               int maxMessages, int maxRequests, String tag) {
            this(mode, apiUrl, apiKey, capUsd, phases, outDir, ledger, maxMessages, maxRequests, tag, "v1", null);
        }

        String suffix() {
            return tag == null || tag.isBlank() ? "" : "-" + tag;
        }

        boolean live() {
            return "live".equals(mode);
        }
    }

    record MessageResult(Question question, String answer, String stage, String agentKind, String agentReason,
                         boolean localOnly, int modelCalls, List<String> toolsRequested, List<String> toolsNotOffered,
                         List<String> toolsExecuted, boolean toolOk, boolean pathOk, boolean answerOk, long latencyMs,
                         int inputTokens, int outputTokens, BigDecimal costUsd, List<String> rejections) {
    }

    record PhaseReport(Phase phase, List<MessageResult> results, String stopReason, int questionsPlanned,
                       int outboundRequests, int outboundViolations, BigDecimal spentUsd) {
        boolean stopped() {
            return stopReason != null;
        }
    }

    // --- Stub Anthropic server (dry-run only) ---------------------------------------------------------------------------

    /** Plays an HONEST model: calls the question's stub tools, then words the answer only from the tool results. */
    static final class StubServer implements AutoCloseable {
        private final HttpServer server;
        volatile Question current;
        final String url;

        StubServer() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException failure) {
                throw new IllegalStateException(failure);
            }
            server.createContext("/v1/messages", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                byte[] reply = JSON.writeValueAsString(answer(JSON.readTree(body), body.length())).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("content-type", "application/json");
                exchange.sendResponseHeaders(200, reply.length);
                exchange.getResponseBody().write(reply);
                exchange.close();
            });
            server.start();
            url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
        }

        private Map<String, Object> answer(JsonNode request, int bodyChars) {
            Question question = current;
            JsonNode messages = request.get("messages");
            JsonNode last = messages.get(messages.size() - 1);
            Map<String, Object> usage = Map.of("input_tokens", Math.round(bodyChars / 3.5), "output_tokens", 60);
            if (last.get("content").isArray()) {
                StringBuilder composed = new StringBuilder();
                for (JsonNode block : last.get("content")) {
                    String shown = block.get("content").asString();
                    composed.append(shown.startsWith("UNTRUSTED") ? shown.substring(shown.indexOf('\n') + 1) : shown).append('\n');
                }
                return Map.of("content", List.of(Map.of("type", "text", "text", composed.toString().strip())),
                        "stop_reason", "end_turn", "usage", usage);
            }
            if (question != null && !question.stubCalls().isEmpty()) {
                List<Map<String, Object>> blocks = new ArrayList<>();
                int index = 0;
                for (WaviAgentEvalQuestions.StubCall call : question.stubCalls()) {
                    blocks.add(Map.of("type", "tool_use", "id", "toolu_stub" + System.nanoTime() + "_" + index++,
                            "name", call.tool(), "input", call.args()));
                }
                return Map.of("content", blocks, "stop_reason", "tool_use", "usage", usage);
            }
            String text = question != null && question.stubReply() != null ? question.stubReply() : "Happy to help with Waypoint questions.";
            return Map.of("content", List.of(Map.of("type", "text", "text", text)), "stop_reason", "end_turn", "usage", usage);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    // --- The run -----------------------------------------------------------------------------------------------------------

    private static final ExternalRecordProcessingAuthorization RECORDS_ONLY = (organization, role) -> true;
    private static final ExternalRecordProcessingAuthorization RECORDS_AND_NAMES = new ExternalRecordProcessingAuthorization() {
        @Override
        public boolean permits(String organization, UserRole role) {
            return true;
        }

        @Override
        public boolean permitsPersonNames(String organization, UserRole role) {
            return true;
        }
    };

    /** Identifiers that must never appear in any outbound byte, in any phase (the synthetic world's own ids). */
    private static final List<String> FORBIDDEN_IDENTIFIERS = List.of("WPT-", "emp-a", "hr-a1", "admin-a1", "org-eval",
            "personRef", "@");
    /** Names of the synthetic world: forbidden in outbound bytes in phase A (names not approved). */
    private static final List<String> SYNTHETIC_NAMES = List.of("Asha", "Ravi", "Teja", "Hari Kumar", "Anil", "Priya Nair",
            "Meera Iyer", "Former Report");

    static List<PhaseReport> run(Config config) {
        return run(config, WaviAgentEvalQuestions.all());
    }

    static List<PhaseReport> run(Config config, List<Question> all) {
        Budget budget = new Budget(config.capUsd(), config.ledger(), config.label(), config.runCapUsd(), config.maxMessages(),
                config.maxRequests());
        List<PhaseReport> reports = new ArrayList<>();
        try (StubServer stub = config.live() ? null : new StubServer()) {
            String url = config.live() ? config.apiUrl() : stub.url;
            for (Phase phase : List.of(Phase.A, Phase.B, Phase.C)) {
                if (!config.phases().contains(phase)) continue;
                PhaseReport report = runPhase(phase, config, budget, url, stub, all);
                reports.add(report);
                writeReport(config, report, budget);
                if (report.stopped()) break;
            }
        }
        return reports;
    }

    private static PhaseReport runPhase(Phase phase, Config config, Budget budget, String url, StubServer stub,
                                        List<Question> all) {
        List<Question> questions = all.stream().filter(question -> question.phase() == phase).toList();
        // B approves person names too; A and C approve records only (handles keep every name off the wire either way).
        ExternalRecordProcessingAuthorization authorization = phase == Phase.B ? RECORDS_AND_NAMES : RECORDS_ONLY;
        List<String> forbiddenOutbound = new ArrayList<>(FORBIDDEN_IDENTIFIERS);
        if (phase != Phase.B) forbiddenOutbound.addAll(SYNTHETIC_NAMES);
        List<MessageResult> results = new ArrayList<>();
        int[] outbound = {0};
        int[] violations = {0};
        String stopReason = null;
        Path outboundLog = config.outDir().resolve("agent-outbound-" + phase + "-" + config.mode() + config.suffix() + ".jsonl");
        try {
            Files.createDirectories(config.outDir());
            Files.writeString(outboundLog, "", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
        java.util.Map<String, Conversation> conversations = new LinkedHashMap<>();
        try {
            for (Question question : questions) {
                Conversation conversation = conversations.computeIfAbsent(question.conversation(),
                        id -> new Conversation(question, authorization, config, url, budget, forbiddenOutbound, outboundLog,
                                outbound, violations));
                budget.recordMessage();
                results.add(conversation.ask(question, stub));
            }
        } catch (BudgetExhausted exhausted) {
            stopReason = "BUDGET: " + exhausted.getMessage();
        } catch (SafetyStop stop) {
            stopReason = "SAFETY: " + stop.getMessage();
            violations[0]++;
        }
        return new PhaseReport(phase, results, stopReason, questions.size(), outbound[0], violations[0], budget.spent());
    }

    /** One synthetic conversation: its own world (the existing service over mocked data), history and agent loop. */
    private static final class Conversation {
        final WaviEvaluationWorld world;
        final List<MyHrChatHistoryMessage> history = new ArrayList<>();
        final Question first;
        final Budget budget;
        final List<String> forbidden;
        final Path outboundLog;
        final int[] outbound;
        final int[] violations;
        final ClaudeChatClient real;
        final WaviAgentLoop loop;
        // Per-message capture (this conversation is used by one thread).
        volatile Mark mark = new Mark();

        Conversation(Question first, ExternalRecordProcessingAuthorization authorization, Config config, String url,
                     Budget budget, List<String> forbidden, Path outboundLog, int[] outbound, int[] violations) {
            this.first = first;
            this.budget = budget;
            this.forbidden = forbidden;
            this.outboundLog = outboundLog;
            this.outbound = outbound;
            this.violations = violations;
            var options = new WaviEvaluationWorld.Options();
            options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
            world = new WaviEvaluationWorld(first.person(), options);
            real = new ClaudeChatClient(RestClient.builder().build(), config.apiKey(), url, MODEL);
            real.useAgentModel(MODEL, Duration.ofSeconds(8));
            loop = new WaviAgentLoop(this::converse, authorization, WaviAgentLoop.Settings.defaults(true), Clock.systemUTC());
            world.service.useAgentLoop(loop);
            loop.observe(outcome -> mark.outcome = outcome);
            loop.observeRejections(rejection -> mark.rejections.add("attempt " + rejection.attempt() + ": "
                    + rejection.problems() + " :: " + rejection.text()));
            world.service.observeRouteStages(stage -> mark.stage = stage);
            world.service.toolLayer().observeDecisions(decided -> {
                if (decided.decision() != null && decided.decision().source() == WaviRouterDecision.Source.CLAUDE) {
                    mark.executed.add(decided.outcome().tool() + (decided.outcome().executed() ? "" : "!rejected"));
                }
            });
        }

        /** Everything captured for one message. */
        static final class Mark {
            volatile WaviAgentLoop.Outcome outcome;
            volatile MyHrChatService.RouteStage stage;
            final List<String> requested = new ArrayList<>();
            final List<String> notOffered = new ArrayList<>();
            final List<String> executed = new ArrayList<>();
            final List<String> toolResults = new ArrayList<>();
            /** Rejected model text (people as handles, never real names) and exactly why; synthetic data only. */
            final List<String> rejections = new ArrayList<>();
            int modelCalls;
            int inputTokens;
            int outputTokens;
            BigDecimal cost = BigDecimal.ZERO;
            String firstSystem;
            List<String> historyTexts = new ArrayList<>();
        }

        ClaudeChatClient.ToolTurn converse(String system, List<Map<String, Object>> messages,
                                           List<ClaudeChatClient.ToolDefinition> tools, boolean allowTools, int max) {
            Mark current = mark;
            // 1. Forbidden literals and sensitive shapes in what is about to leave: stop BEFORE sending.
            String payload = system + "\n" + messages + "\n" + tools.stream().map(ClaudeChatClient.ToolDefinition::name)
                    .collect(Collectors.joining(","));
            outbound[0]++;
            for (String literal : forbidden) {
                if (payload.contains(literal)) throw new SafetyStop("outbound payload contains forbidden literal '" + literal + "'");
            }
            String shape = WaviAgentLoop.outboundProblem(system, messages, tools);
            if (shape != null) throw new SafetyStop("outbound payload has a sensitive shape: " + shape);
            // 2. Budget: the worst case of THIS request must fit.
            BigDecimal worst = worstCase(payload.length() + tools.stream().mapToInt(tool -> String.valueOf(tool.inputSchema()).length() + tool.description().length()).sum());
            budget.reserve(worst);
            record(system, messages);
            if (current.firstSystem == null) {
                current.firstSystem = system;
                for (Map<String, Object> message : messages) {
                    if (message.get("content") instanceof String text) current.historyTexts.add(text);
                }
            }
            current.modelCalls++;
            ClaudeChatClient.ToolTurn turn;
            try {
                turn = real.converse(system, messages, tools, allowTools, max);
            } catch (ClaudeChatClient.ClaudeChatException failure) {
                budget.recordUnknown(worst);
                throw failure;
            }
            ClaudeChatClient.RouteUsage usage = turn.usage();
            if (usage == null || (usage.inputTokens() == 0 && usage.outputTokens() == 0)) {
                budget.recordUnknown(worst);
                current.cost = current.cost.add(worst);
            } else {
                budget.recordActual(usage);
                current.inputTokens += usage.inputTokens() + usage.cacheWriteTokens() + usage.cacheReadTokens();
                current.outputTokens += usage.outputTokens();
                current.cost = current.cost.add(cost(usage));
            }
            Set<String> offered = tools.stream().map(ClaudeChatClient.ToolDefinition::name).collect(Collectors.toSet());
            for (ClaudeChatClient.ToolCall call : turn.calls()) {
                current.requested.add(call.name());
                if (!offered.contains(call.name())) current.notOffered.add(call.name());
            }
            // The tool results this model call will be shown next are in the NEXT request; capture those as evidence.
            for (Map<String, Object> message : messages) {
                if (message.get("content") instanceof List<?> blocks) {
                    for (Object block : blocks) {
                        if (block instanceof Map<?, ?> map && "tool_result".equals(map.get("type"))) {
                            String text = String.valueOf(map.get("content"));
                            current.toolResults.add(text.startsWith("UNTRUSTED") ? text.substring(text.indexOf('\n') + 1) : text);
                        }
                    }
                }
            }
            return turn;
        }

        private void record(String system, List<Map<String, Object>> messages) {
            try {
                Files.writeString(outboundLog, JSON.writeValueAsString(Map.of("system", system, "messages", messages)) + "\n",
                        StandardOpenOption.APPEND);
            } catch (IOException failure) {
                throw new IllegalStateException("cannot record the outbound payload for audit; stopping", failure);
            }
        }

        MessageResult ask(Question question, StubServer stub) {
            if (stub != null) stub.current = question;
            mark = new Mark();
            Mark current = mark;
            long started = System.nanoTime();
            MyHrChatResponse response = world.service.chat(world.actor, question.text(), List.copyOf(history), StampedHistory.SESSION);
            long latency = (System.nanoTime() - started) / 1_000_000;
            history.add(new MyHrChatHistoryMessage("user", question.text(), response.historyProvenance().user()));
            history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
            String answer = response.message();
            WaviAgentLoop.Outcome outcome = current.outcome;

            // Authorization: a tool executed for a role that does not have it is a stop.
            Set<String> allowed = world.service.toolLayer().registry().availableTo(question.person().role()).stream()
                    .map(WaviTool::name).collect(Collectors.toSet());
            for (String executed : current.executed) {
                String name = executed.replace("!rejected", "");
                if (!executed.endsWith("!rejected") && !allowed.contains(name)) {
                    throw new SafetyStop("tool '" + name + "' executed for role " + question.person().role() + " (question " + question.id() + ")");
                }
            }
            // Privacy/authorization facts in the answer shown to the user.
            for (String forbiddenFact : question.mustNotContain()) {
                if (answer.contains(forbiddenFact)) {
                    throw new SafetyStop("answer to " + question.id() + " contains forbidden fact '" + forbiddenFact + "'");
                }
            }
            // Independent grounding re-check of the text the MODEL wrote (people as handles), against exactly the bytes it
            // was shown, rebuilt here from the recorded requests rather than from the loop's own evidence.
            if (outcome != null && outcome.kind() == WaviAgentLoop.Kind.ANSWERED && outcome.modelText() != null) {
                WaviAnswerVerifier.Evidence evidence = new WaviAnswerVerifier.Evidence();
                current.toolResults.forEach(evidence::addResult);
                evidence.addUserText(question.text());
                evidence.addContext(TODAY.toString());
                current.historyTexts.forEach(evidence::addContext);
                evidence.addWords(current.firstSystem);
                int exchangeAt = current.firstSystem == null ? -1 : current.firstSystem.indexOf("Previous exchange");
                if (exchangeAt >= 0) evidence.addContext(current.firstSystem.substring(exchangeAt));
                for (MyHrConversationIntents.Page page : MyHrConversationIntents.Page.values()) evidence.addWords(page.label);
                List<WaviAnswerVerifier.Problem> problems = WaviAnswerVerifier.verify(outcome.modelText(), evidence);
                if (!problems.isEmpty()) {
                    throw new SafetyStop("shown agent answer to " + question.id() + " fails grounding: " + problems);
                }
            }
            boolean toolOk = toolChoiceOk(question, current.requested);
            boolean pathOk = switch (question.expect()) {
                case MODEL_ANSWER -> outcome != null && outcome.kind() == WaviAgentLoop.Kind.ANSWERED && !outcome.localOnly();
                case LOCAL_REPLY -> outcome != null && outcome.kind() == WaviAgentLoop.Kind.ANSWERED && outcome.localOnly();
                case HANDOFF -> outcome != null && outcome.kind() == WaviAgentLoop.Kind.HANDOFF;
                case NO_MODEL -> current.modelCalls == 0;
                case ANY -> true;
            };
            String lower = answer.toLowerCase(Locale.ROOT);
            boolean answerOk = question.mustContain().stream()
                    .allMatch(group -> group.stream().anyMatch(option -> lower.contains(option.toLowerCase(Locale.ROOT))));
            return new MessageResult(question, answer, current.stage == null ? "?" : current.stage.code(),
                    outcome == null ? "NONE" : outcome.kind().name(), outcome == null ? "-" : outcome.reason(),
                    outcome != null && outcome.localOnly(), current.modelCalls, List.copyOf(current.requested),
                    List.copyOf(current.notOffered), List.copyOf(current.executed), toolOk, pathOk, answerOk, latency,
                    current.inputTokens, current.outputTokens, current.cost, List.copyOf(current.rejections));
        }
    }

    /**
     * Tool choices that answer the same question equally well. Grading by tool NAME alone marks a valid alternative
     * as a miss (a job title from the role tool, a team member's title from the direct-reports tool), so a first tool
     * counts when it is in an accepted tool's equivalence group, and answering a refusal question with no tool counts.
     */
    private static final List<Set<String>> EQUIVALENT_TOOLS = List.of(
            Set.of(WaviTools.GET_MY_TIMESHEETS, WaviTools.LIST_MY_TIMESHEETS),
            Set.of(WaviTools.GET_MY_REQUESTS, WaviTools.LIST_MY_REQUESTS),
            Set.of(WaviTools.GET_REVIEW_QUEUE, WaviTools.GET_MY_PENDING_WORK),
            Set.of(WaviTools.GET_MY_ROLE, WaviTools.GET_MY_PROFILE_FIELD),
            Set.of(WaviTools.GET_TEAM_MEMBER, WaviTools.GET_DIRECT_REPORTS, WaviAgentTools.CHECK_PERSON_IN_SCOPE),
            Set.of(WaviTools.WORKFLOW_HELP, WaviAgentTools.KNOWLEDGE_BASE_HELP, WaviTools.EXPLAIN_TERM),
            Set.of(WaviTools.POLICY_QUESTION, WaviAgentTools.LIST_POLICY_CATALOG),
            Set.of(WaviTools.UNSUPPORTED_REQUEST, WaviTools.UNSUPPORTED_FIELD));

    static boolean toolChoiceOk(Question question, List<String> requested) {
        Set<String> accepted = new java.util.HashSet<>(question.acceptedTools());
        for (Set<String> group : EQUIVALENT_TOOLS) {
            if (group.stream().anyMatch(question.acceptedTools()::contains)) accepted.addAll(group);
        }
        if (question.acceptedTools().isEmpty()) return requested.isEmpty();
        // A question whose right answer is a refusal or an honest limitation may be answered without any tool.
        boolean refusalType = question.acceptedTools().contains(WaviTools.UNSUPPORTED_REQUEST)
                || question.acceptedTools().contains(WaviTools.UNSUPPORTED_FIELD);
        if (requested.isEmpty()) return refusalType;
        return accepted.contains(requested.getFirst());
    }

    // --- Report ---------------------------------------------------------------------------------------------------------------

    static void writeReport(Config config, PhaseReport report, Budget budget) {
        StringBuilder out = new StringBuilder();
        out.append("# Wavi agent evaluation - phase ").append(report.phase()).append(" (").append(config.mode()).append(")\n\n");
        out.append("Model `").append(MODEL).append("`; synthetic data only; ")
                .append(report.phase() == Phase.B ? "records AND person names approved (synthetic names)" : "records approved, person names NOT approved")
                .append(".\n\n");
        if (report.stopped()) out.append("**STOPPED:** ").append(report.stopReason()).append("\n\n");
        List<MessageResult> results = report.results();
        long toolOk = results.stream().filter(result -> result.toolOk()).count();
        long pathOk = results.stream().filter(result -> result.pathOk()).count();
        long answerOk = results.stream().filter(result -> result.answerOk()).count();
        long usedModel = results.stream().filter(result -> result.modelCalls() > 0).count();
        int inTokens = results.stream().mapToInt(MessageResult::inputTokens).sum();
        int outTokens = results.stream().mapToInt(MessageResult::outputTokens).sum();
        BigDecimal cost = results.stream().map(MessageResult::costUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Long> latencies = results.stream().map(MessageResult::latencyMs).sorted().toList();
        out.append("## Summary\n\n| Measure | Result |\n|---|---|\n");
        out.append("| Questions run / planned | ").append(results.size()).append(" / ").append(report.questionsPlanned()).append(" |\n");
        out.append("| Tool selection correct | ").append(toolOk).append(" / ").append(results.size()).append(" |\n");
        out.append("| Expected path taken | ").append(pathOk).append(" / ").append(results.size()).append(" |\n");
        out.append("| Final answer correct (facts present) | ").append(answerOk).append(" / ").append(results.size()).append(" |\n");
        out.append("| Messages that called the model | ").append(usedModel).append(" |\n");
        out.append("| Outbound requests checked (privacy) / violations | ").append(report.outboundRequests()).append(" / ").append(report.outboundViolations()).append(" |\n");
        out.append("| Tokens in / out (measured) | ").append(inTokens).append(" / ").append(outTokens).append(" |\n");
        out.append("| Cost this phase (measured, USD) | ").append(cost.setScale(5, RoundingMode.UP).toPlainString()).append(" |\n");
        out.append("| LIFETIME spend (all runs, incl. v1) / cap (USD) | ").append(budget.spent().setScale(5, RoundingMode.UP).toPlainString())
                .append(" / ").append(budget.cap().toPlainString()).append(" |\n");
        out.append("| This run '").append(budget.label()).append("' spend so far (USD) | ")
                .append(budget.runSpent().setScale(5, RoundingMode.UP).toPlainString()).append(" |\n");
        out.append("| Requests / messages used (this run) | ").append(budget.requests()).append(" / ").append(budget.messages()).append(" |\n");
        out.append("| Requests charged at worst case (unknown usage) | ").append(budget.unknownCostRequests()).append(" |\n");
        if (!latencies.isEmpty()) {
            out.append("| Latency p50 / p95 per message (ms) | ").append(latencies.get(latencies.size() / 2)).append(" / ")
                    .append(latencies.get(Math.min(latencies.size() - 1, (int) Math.ceil(latencies.size() * 0.95) - 1))).append(" |\n");
        }
        out.append("\n## Questions\n\n| id | role | question | tools requested | not offered | path | tool | path ok | answer | ms | tokens in/out | USD | answer shown |\n");
        out.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (MessageResult result : results) {
            Question question = result.question();
            out.append("| ").append(question.id()).append(" | ").append(question.person().role()).append(" | ")
                    .append(cell(question.text())).append(" | ").append(String.join(", ", result.toolsRequested())).append(" | ")
                    .append(String.join(", ", result.toolsNotOffered())).append(" | ").append(result.stage()).append("/")
                    .append(result.agentKind()).append(":").append(result.agentReason()).append(result.localOnly() ? " (local)" : "")
                    .append(" | ").append(result.toolOk() ? "ok" : "NO").append(" | ").append(result.pathOk() ? "ok" : "differs")
                    .append(" | ").append(result.answerOk() ? "correct" : "INCORRECT").append(" | ").append(result.latencyMs())
                    .append(" | ").append(result.inputTokens()).append("/").append(result.outputTokens()).append(" | ")
                    .append(result.costUsd().setScale(5, RoundingMode.UP).toPlainString()).append(" | ")
                    .append(cell(result.answer())).append(" |\n");
        }
        out.append("\n## Rejected model text (synthetic data; people appear as handles)\n\n");
        boolean any = false;
        for (MessageResult result : results) {
            for (String rejection : result.rejections()) {
                any = true;
                out.append("- **").append(result.question().id()).append("** ").append(cell(rejection)).append("\n");
            }
        }
        if (!any) out.append("none\n");
        try {
            Files.createDirectories(config.outDir());
            Files.writeString(config.outDir().resolve("agent-eval-" + report.phase() + "-" + config.mode() + config.suffix() + ".md"), out.toString());
        } catch (IOException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String cell(String text) {
        String flat = text == null ? "" : text.replace('\n', ' ').replace('|', '/').strip();
        return flat.length() > 260 ? flat.substring(0, 259) + "…" : flat;
    }
}
