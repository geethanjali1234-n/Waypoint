package com.waypoint.service;

import com.waypoint.exception.UnauthorizedException;
import com.waypoint.security.VerifiedMyHrActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Runs one router decision: gate, then tool, then output bounds. Exceptions from the underlying services
 * (authorization, data access) propagate unchanged, exactly as on the existing routes.
 *
 * <p>Output bounds: the reply is at most {@link #MAX_REPLY_CHARS} characters, and page buttons are limited to
 * known pages the verified role has, at most {@link #MAX_PAGE_KEYS}.
 */
class WaviToolLayer {

    static final String TOOL_MARKER = "MYHR_WAVI_TOOL";
    static final int MAX_REPLY_CHARS = 4000;
    static final int MAX_PAGE_KEYS = 4;
    private static final Logger log = LoggerFactory.getLogger(WaviToolLayer.class);

    /**
     * The outcome of one decision.
     *
     * @param tool the tool's name, or null when the decision named none the registry knows
     * @param executed whether the tool ran (a lookup may have happened); false for gate answers
     * @param externallyShareable whether the reply may later reach an external provider as history
     */
    record Outcome(String tool, WaviToolResult result, boolean executed, boolean externallyShareable,
                   java.util.Map<String, Object> arguments) {
        Outcome(String tool, WaviToolResult result, boolean executed, boolean externallyShareable) {
            this(tool, result, executed, externallyShareable, java.util.Map.of());
        }
    }

    private final WaviToolRegistry registry;
    private final WaviRouterPolicyGate gate;
    /** Tests only: sees each outcome. */
    private volatile Consumer<Outcome> observer;
    /** Tests only: sees each decision (with its source) together with its outcome. */
    private volatile Consumer<Decided> decisionObserver;

    /** Tests only: a decision as received from its source (router or fast path) and what the layer did with it. */
    record Decided(WaviRouterDecision decision, Outcome outcome) { }

    WaviToolLayer(WaviToolRegistry registry) {
        this.registry = registry;
        this.gate = new WaviRouterPolicyGate(registry);
    }

    WaviToolRegistry registry() {
        return registry;
    }

    /** Tests only. */
    void observe(Consumer<Outcome> observer) {
        this.observer = observer;
    }

    /** Tests only. */
    void observeDecisions(Consumer<Decided> observer) {
        this.decisionObserver = observer;
    }

    Outcome execute(WaviRouterDecision decision, VerifiedMyHrActor actor, LocalDate today) {
        return execute(decision, actor, today, null);
    }

    Outcome execute(WaviRouterDecision decision, VerifiedMyHrActor actor, LocalDate today, String message) {
        if (actor == null) {
            throw new UnauthorizedException("Authentication is required");
        }
        long started = System.nanoTime();
        WaviTool.Context context = new WaviTool.Context(today, message);
        WaviRouterPolicyGate.Verdict verdict = gate.check(decision, actor.role(), context);
        Outcome outcome;
        if (verdict instanceof WaviRouterPolicyGate.Execute execute) {
            WaviToolResult result = execute.tool().execute(actor, execute.arguments(), context);
            if (result == null || (!isHandoff(result) && result.text() == null)) {
                result = WaviToolResult.notAvailable("NO_ANSWER", WaviRouterPolicyGate.CANNOT_HELP_REPLY, List.of());
            }
            outcome = new Outcome(execute.tool().name(), bounded(result, actor), true,
                    execute.tool().externallyShareable(), execute.arguments().routingValues());
        } else {
            WaviRouterPolicyGate.Answer answer = (WaviRouterPolicyGate.Answer) verdict;
            WaviTool tool = answer.tool();
            boolean shareable = tool != null && tool.externallyShareable()
                    && answer.result().kind() == WaviToolResult.Kind.NOT_AVAILABLE;
            outcome = new Outcome(tool == null ? null : tool.name(), bounded(answer.result(), actor), false, shareable);
        }
        // Codes only: never the question, arguments' values or the reply.
        log.info("{} tool={} source={} executed={} result={} latencyMs={}", TOOL_MARKER,
                outcome.tool() == null ? "none" : outcome.tool(),
                decision == null ? "none" : decision.source(), outcome.executed(), outcome.result().reasonCode(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        Consumer<Outcome> watching = observer;
        if (watching != null) watching.accept(outcome);
        Consumer<Decided> decisions = decisionObserver;
        if (decisions != null) decisions.accept(new Decided(decision, outcome));
        return outcome;
    }

    /**
     * What the gate would do with a decision, without running any tool (shadow mode): "allowed", "clarify",
     * "outside-role" or "rejected:CODE". Outside-role replies are fixed text, so even that path reads no record.
     */
    String dryRun(WaviRouterDecision decision, com.waypoint.enums.UserRole role, LocalDate today) {
        WaviRouterPolicyGate.Verdict verdict = gate.check(decision, role, new WaviTool.Context(today));
        if (verdict instanceof WaviRouterPolicyGate.Execute) return "allowed";
        WaviToolResult result = ((WaviRouterPolicyGate.Answer) verdict).result();
        return switch (result.kind()) {
            case REJECTED -> "rejected:" + result.reasonCode();
            case CLARIFY -> "clarify";
            default -> "outside-role";
        };
    }

    static boolean isHandoff(WaviToolResult result) {
        return result.kind() == WaviToolResult.Kind.POLICY_HANDOFF || result.kind() == WaviToolResult.Kind.GENERAL_HANDOFF;
    }

    private static WaviToolResult bounded(WaviToolResult result, VerifiedMyHrActor actor) {
        String text = result.text();
        if (text != null && text.length() > MAX_REPLY_CHARS) {
            text = text.substring(0, MAX_REPLY_CHARS - 1) + "…";
        }
        List<String> pages = null;
        if (result.pageKeys() != null) {
            pages = new ArrayList<>();
            for (String key : result.pageKeys()) {
                MyHrConversationIntents.Page page = page(key);
                // Never a button for a page the verified role does not have.
                if (page != null && page.availableTo(actor.role()) && !pages.contains(key) && pages.size() < MAX_PAGE_KEYS) {
                    pages.add(key);
                }
            }
        }
        return new WaviToolResult(result.kind(), text, pages, result.reasonCode(), result.policyQuery(), result.facts());
    }

    private static MyHrConversationIntents.Page page(String key) {
        if (key == null) return null;
        for (MyHrConversationIntents.Page page : MyHrConversationIntents.Page.values()) {
            if (page.name().equals(key)) return page;
        }
        return null;
    }
}
