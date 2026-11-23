package com.waypoint.service;

import com.waypoint.security.VerifiedMyHrActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/**
 * Shadow mode (Phase P3A): the semantic router proposes a decision for a sample of messages. That includes messages
 * a deterministic route already answered, so the router's agreement with every route can be measured. Nothing it
 * proposes is ever served or executed. The gate is only dry-run (validation, no lookup), the user's answer is
 * already computed, and the call runs on a bounded background queue that drops work rather than delay anyone.
 *
 * <p>Each comparison is one {@value #SHADOW_MARKER} log line of codes only: what served the message (a tool and
 * whether its arguments matched, the policy/general pipeline, a scope refusal, or another fixed route), what the
 * router proposed, its confidence, the gate's dry-run verdict, latency and tokens. Never the message, a reply,
 * argument values or the user.
 */
final class WaviShadowRouting {

    static final String SHADOW_MARKER = "MYHR_WAVI_SHADOW";
    private static final Logger log = LoggerFactory.getLogger(WaviShadowRouting.class);
    private static final Set<String> HANDOFF_TOOLS = Set.of(WaviTools.POLICY_QUESTION, WaviTools.GENERAL_HELP);

    /**
     * What answered the message.
     *
     * @param category {@code tool} (a tool-layer route; {@code tool} and {@code arguments} set), {@code handoff}
     *                 (policy retrieval or the general provider), {@code out_of_scope} (scope refusal) or
     *                 {@code fixed} (any other existing route, e.g. queue wording or small talk)
     */
    record Served(String category, String tool, Map<String, Object> arguments) {
    }

    /** One shadow comparison, as logged. */
    record Comparison(String served, String proposed, String confidence, String agreement, boolean argumentsAgree,
                      String gate, String outcome, boolean review) {
    }

    private final WaviSemanticRouting routing;
    private final Executor executor;
    private final DoubleSupplier random;
    private final AtomicLong dropped = new AtomicLong();
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();

    WaviShadowRouting(WaviSemanticRouting routing, Executor executor, DoubleSupplier random) {
        this.routing = routing;
        this.executor = executor == null ? defaultExecutor(dropped) : executor;
        this.random = random;
    }

    /** One background thread, at most 100 waiting comparisons; anything beyond is dropped and counted. */
    private static Executor defaultExecutor(AtomicLong dropped) {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(1, 1, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                runnable -> {
                    Thread thread = new Thread(runnable, "wavi-shadow-router");
                    thread.setDaemon(true);
                    return thread;
                }, (runnable, executor) -> dropped.incrementAndGet());
        pool.allowCoreThreadTimeOut(true);
        return pool;
    }

    /** Samples the message and, if chosen, compares in the background. Never throws, never blocks the answer. */
    void observe(VerifiedMyHrActor actor, String message, WaviDialogueStore.State previous, LocalDate today,
                 WaviToolLayer layer, Served served) {
        if (actor == null || message == null || layer == null || served == null) return;
        if (random.getAsDouble() >= routing.shadowSampleRate()) return;
        try {
            executor.execute(() -> {
                try {
                    compare(actor, message, previous, today, layer, served);
                } catch (RuntimeException failure) {
                    log.warn("{} comparison failed ({})", SHADOW_MARKER, failure.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException rejected) {
            dropped.incrementAndGet();
        }
    }

    Comparison compare(VerifiedMyHrActor actor, String message, WaviDialogueStore.State previous, LocalDate today,
                       WaviToolLayer layer, Served served) {
        WaviSemanticRouting.Result result = routing.route(actor, message, previous, today, layer.registry().all().stream().filter(tool -> !(tool instanceof WaviTool.AgentOnly)).toList());
        WaviRouterDecision decision = result.decision();
        String proposed = decision == null ? "none" : decision.tool();
        String gate = decision == null ? "none" : layer.dryRun(decision, actor.role(), today);
        String agreement;
        boolean argumentsAgree = false;
        switch (served.category()) {
            case "tool" -> {
                agreement = proposed.equals(served.tool()) ? "agree" : "disagree";
                argumentsAgree = decision != null && agreement.equals("agree")
                        && decision.arguments().equals(served.arguments() == null ? Map.of() : served.arguments());
            }
            case "handoff" -> agreement = HANDOFF_TOOLS.contains(proposed) ? "agree" : "disagree";
            case "out_of_scope" -> agreement = proposed.equals(WaviTools.OUT_OF_SCOPE) ? "agree" : "disagree";
            default -> agreement = "unknown";
        }
        if (decision == null) agreement = "no-decision";
        // A sure, valid proposal that differs from a deterministic answer is the signal to inspect that route.
        boolean deterministic = served.category().equals("tool") || served.category().equals("fixed");
        boolean review = deterministic && decision != null
                && decision.confidence() == WaviRouterDecision.Confidence.HIGH && gate.equals("allowed")
                && !agreement.equals("agree") && !proposed.equals(WaviTools.CLARIFY);
        String servedLabel = served.category().equals("tool") ? "tool:" + served.tool() : served.category();
        Comparison comparison = new Comparison(servedLabel, proposed,
                decision == null ? "none" : decision.confidence().name(), agreement, argumentsAgree, gate,
                result.outcome(), review);
        counts.computeIfAbsent(served.category() + "/" + agreement, key -> new AtomicLong()).incrementAndGet();
        if (review) counts.computeIfAbsent("review", key -> new AtomicLong()).incrementAndGet();
        ClaudeChatClient.RouteUsage usage = result.usage() == null ? ClaudeChatClient.RouteUsage.NONE : result.usage();
        log.info("{} served={} proposed={} confidence={} agreement={} argumentsAgree={} gate={} outcome={} review={} "
                        + "latencyMs={} tokensIn={} tokensOut={} cacheRead={}", SHADOW_MARKER, comparison.served(),
                comparison.proposed(), comparison.confidence(), comparison.agreement(), comparison.argumentsAgree(),
                comparison.gate(), comparison.outcome(), comparison.review(), result.latencyMs(), usage.inputTokens(),
                usage.outputTokens(), usage.cacheReadTokens());
        return comparison;
    }

    /** Counts by "served category/agreement", plus "review" and "dropped"; for tests and a future admin view. */
    Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new TreeMap<>();
        counts.forEach((key, value) -> snapshot.put(key, value.get()));
        snapshot.put("dropped", dropped.get());
        return snapshot;
    }
}
