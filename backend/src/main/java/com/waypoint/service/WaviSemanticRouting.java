package com.waypoint.service;

import com.waypoint.security.VerifiedMyHrActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Semantic routing (Phase P2): asks a model which Wavi tool a message needs when no deterministic route answered
 * it. Off unless {@code waypoint.myhr.router.semantic.enabled=true}.
 *
 * <p>The router only chooses a tool and arguments; {@link WaviToolLayer} checks and runs them for the verified
 * actor. It receives only {@link WaviRouterPrompt}'s minimal, sanitized input. A missing, slow, failing,
 * refusing or invalid router gives "no decision", and the caller keeps its existing path.
 *
 * <p>Providers are tried in the configured order ({@code waypoint.myhr.router.providers}, default "claude"):
 * <ul>
 *   <li>{@code claude}: the existing {@link ClaudeChatClient} with Structured Outputs;</li>
 *   <li>{@code ollama-local}: a verifiably local Ollama, for development, never in Claude-only mode.</li>
 * </ul>
 * Any other name (for example a hosted fallback) is not implemented and needs separate approval: semantic
 * routing then stays off rather than sending questions to an unapproved provider.
 *
 * <p>Cost and latency controls:
 * <ul>
 *   <li>bounded attempts within a total time budget per message;</li>
 *   <li>a decision cache (only decisions, keyed by a digest);</li>
 *   <li>a per-user limit and a daily limit per process;</li>
 *   <li>a circuit breaker per provider.</li>
 * </ul>
 * One {@value #ROUTER_MARKER} log line per routed message records codes, counts and timings only, never the
 * message.
 */
@Component
public class WaviSemanticRouting {

    static final String ROUTER_MARKER = "MYHR_WAVI_ROUTER";
    static final String CLAUDE = "claude";
    static final String OLLAMA_LOCAL = "ollama-local";
    static final String DEFAULT_PROVIDER_CONFIGURATION = CLAUDE;
    static final List<String> DEFAULT_PROVIDER_NAMES = List.of(CLAUDE);
    private static final Logger log = LoggerFactory.getLogger(WaviSemanticRouting.class);

    /** A model that can make a routing decision: returns its JSON text, or throws. */
    interface Provider {
        String name();

        WaviRouterDecision.Source source();

        /** Whether this provider may be used at all right now (e.g. Ollama only when verifiably local). */
        boolean usable();

        String route(WaviRouterPrompt.Request request, Settings settings);

        /** The decision text and, where the provider reports it, the tokens the call used. */
        default Reply routeWithUsage(WaviRouterPrompt.Request request, Settings settings) {
            return new Reply(route(request, settings), null);
        }

        /**
         * The most one attempt can take (the provider's whole-request timeout). An attempt is started only when this
         * much of the routing deadline remains. ZERO means "not bounded by the provider": then only the deadline
         * check after the attempt applies.
         */
        default Duration attemptTimeout() {
            return Duration.ZERO;
        }

        /**
         * Observes a retry after the router has accepted a transient failure and its backoff. Implementations that
         * meter external calls can retain this separately from their actual HTTP-call count.
         */
        default void retryStarted() {
            // No-op for serving providers that do not need evaluation telemetry.
        }

        /**
         * Converts a provider exception into the routing loop's provider-neutral failure contract. Providers added
         * later can override this method to expose their own retryable overload or rate-limit responses without
         * teaching the routing loop about another client library.
         */
        default Failure failure(RuntimeException exception) {
            if (exception instanceof ClaudeChatClient.ClaudeSafetyRefusalException) {
                return new Failure("refusal", "refusal", false, 0, true);
            }
            if (exception instanceof ClaudeChatClient.ClaudeChatException claude) {
                return new Failure(claude.failureClass(), outcomeOf(claude), claude.transientFailure(),
                        claude.retryAfterMillis(), false);
            }
            if (exception instanceof OllamaClient.OllamaIncompleteResponseException
                    || exception instanceof OllamaClient.PolicyContextNotLocalException) {
                return new Failure("incomplete", "incomplete", false, 0, false);
            }
            if (exception instanceof OllamaClient.OllamaUnavailableException) {
                return new Failure("unavailable", "unavailable", false, 0, false);
            }
            return new Failure("error", "error", false, 0, false);
        }
    }

    /** A provider-neutral classification for one failed routing attempt. */
    record Failure(String code, String outcome, boolean retryable, long retryAfterMillis, boolean terminal) {
        Failure {
            if (code == null || code.isBlank() || outcome == null || outcome.isBlank()) {
                throw new IllegalArgumentException("routing failure needs a code and outcome");
            }
            if (retryAfterMillis < 0) throw new IllegalArgumentException("retryAfterMillis must not be negative");
        }
    }

    /** A provider's raw decision text and token usage (null when the provider reports none). */
    record Reply(String json, ClaudeChatClient.RouteUsage usage) {
    }

    record Settings(int maxQuestionChars, int maxOutputTokens, int maxAttempts, Duration totalBudget, String effort,
                    int userLimit, Duration userWindow, int dailyLimit, Duration cacheTtl, int cacheSize,
                    int circuitFailures, Duration circuitOpen) {

        /**
         * Serving defaults: ONE attempt. The deterministic path is the fallback, so a second paid attempt is worth it
         * only when evidence shows transient failures are common; max-attempts can raise it to at most 3.
         */
        static Settings defaults() {
            return new Settings(500, 400, 1, Duration.ofSeconds(4), "low", 30, Duration.ofMinutes(10), 2000,
                    Duration.ofMinutes(10), 1000, 5, Duration.ofSeconds(60));
        }

        Settings {
            if (maxQuestionChars < 20 || maxQuestionChars > 2000) throw new IllegalArgumentException("max-question-chars");
            if (maxOutputTokens < 64 || maxOutputTokens > 2000) throw new IllegalArgumentException("max-output-tokens");
            if (maxAttempts < 1 || maxAttempts > 3) throw new IllegalArgumentException("max-attempts must be 1-3");
            if (userLimit < 1 || dailyLimit < 1 || cacheSize < 0 || circuitFailures < 1) {
                throw new IllegalArgumentException("router limits must be positive");
            }
            if (!List.of("low", "medium", "high").contains(effort)) throw new IllegalArgumentException("effort");
        }
    }

    /**
     * What routing produced for one message: a decision, or null with the reason in {@code outcome}.
     * Outcomes: decision, cached, empty, user-limit, daily-limit, no-provider, refusal, invalid-output,
     * unavailable, incomplete, rejected, configuration, budget, deadline, cancelled, error.
     *
     * @param retries  attempts after the first (each one a counted provider call)
     * @param failures the failure class of every failed attempt, in order (codes only, e.g. "overloaded")
     */
    record Result(WaviRouterDecision decision, String outcome, String provider, int attempts, long latencyMs,
                  ClaudeChatClient.RouteUsage usage, int retries, List<String> failures) {

        Result {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }

        Result(WaviRouterDecision decision, String outcome, String provider, int attempts, long latencyMs,
               ClaudeChatClient.RouteUsage usage) {
            this(decision, outcome, provider, attempts, latencyMs, usage, Math.max(0, attempts - 1), List.of());
        }
    }

    private final boolean enabled;
    /** Shadow mode: decisions are only proposed and logged, never served. Ignored when serving is on. */
    private final boolean shadow;
    private final double shadowSampleRate;
    private final List<Provider> providers;
    private final Settings settings;
    private final Clock clock;

    private final Map<String, CachedDecision> cache = new LinkedHashMap<>(64, 0.75f, true);
    private final Map<String, Deque<Instant>> userCalls = new HashMap<>();
    private final Map<String, Integer> consecutiveFailures = new HashMap<>();
    private final Map<String, Instant> openUntil = new HashMap<>();
    private LocalDate countingDay;
    private int callsToday;

    private record CachedDecision(WaviRouterDecision decision, Instant expires) {
    }

    @Autowired
    public WaviSemanticRouting(ClaudeChatClient claude, OllamaClient ollama,
                               @Value("${waypoint.myhr.router.semantic.enabled:false}") boolean enabled,
                               @Value("${waypoint.myhr.router.providers:" + DEFAULT_PROVIDER_CONFIGURATION + "}")
                               List<String> providerNames,
                               @Value("${waypoint.myhr.ai.claude-only.enabled:false}") boolean claudeOnly,
                               @Value("${waypoint.myhr.router.max-question-chars:500}") int maxQuestionChars,
                               @Value("${waypoint.myhr.router.max-output-tokens:400}") int maxOutputTokens,
                               @Value("${waypoint.myhr.router.max-attempts:1}") int maxAttempts,
                               @Value("${waypoint.myhr.router.total-budget:4s}") Duration totalBudget,
                               @Value("${waypoint.myhr.router.claude.effort:low}") String effort,
                               @Value("${waypoint.myhr.router.user-limit:30}") int userLimit,
                               @Value("${waypoint.myhr.router.user-limit-window:10m}") Duration userWindow,
                               @Value("${waypoint.myhr.router.daily-limit:2000}") int dailyLimit,
                               @Value("${waypoint.myhr.router.cache-ttl:10m}") Duration cacheTtl,
                               @Value("${waypoint.myhr.router.shadow.enabled:false}") boolean shadow,
                               @Value("${waypoint.myhr.router.shadow.sample-rate:0.1}") double shadowSampleRate) {
        this(enabled, shadow, shadowSampleRate, providers(providerNames, claude, ollama, claudeOnly),
                new Settings(maxQuestionChars, maxOutputTokens, maxAttempts, totalBudget,
                        effort == null ? "low" : effort.strip().toLowerCase(Locale.ROOT), userLimit, userWindow,
                        dailyLimit, cacheTtl, 1000, 5, Duration.ofSeconds(60)),
                Clock.systemUTC());
    }

    WaviSemanticRouting(boolean enabled, List<Provider> providers, Settings settings, Clock clock) {
        this(enabled, false, 0.0, providers, settings, clock);
    }

    WaviSemanticRouting(boolean enabled, boolean shadow, double shadowSampleRate, List<Provider> providers,
                        Settings settings, Clock clock) {
        if (shadowSampleRate < 0.0 || shadowSampleRate > 1.0) {
            throw new IllegalArgumentException("waypoint.myhr.router.shadow.sample-rate must be between 0 and 1");
        }
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.settings = settings;
        this.clock = clock;
        this.enabled = enabled && !this.providers.isEmpty();
        this.shadow = shadow && !enabled && !this.providers.isEmpty();
        this.shadowSampleRate = shadowSampleRate;
        if (enabled && shadow) {
            log.warn("{} shadow mode ignored: semantic routing is serving decisions", ROUTER_MARKER);
        }
        if ((enabled || shadow) && this.providers.isEmpty()) {
            log.error("{} disabled: no approved router provider is configured", ROUTER_MARKER);
        } else if (this.shadow) {
            log.info("{} shadow mode enabled providers={} sampleRate={}: decisions are logged, never served",
                    ROUTER_MARKER, this.providers.stream().map(Provider::name).toList(), shadowSampleRate);
        } else if (this.enabled) {
            log.info("{} semantic routing enabled providers={} maxAttempts={} budgetMs={}", ROUTER_MARKER,
                    this.providers.stream().map(Provider::name).toList(), settings.maxAttempts(),
                    settings.totalBudget().toMillis());
        }
    }

    /**
     * The configured providers, or an empty list (routing off) when any name is not an approved provider. A
     * hosted fallback is a configuration choice that needs separate approval and an implementation first.
     */
    static List<Provider> providers(List<String> names, ClaudeChatClient claude, OllamaClient ollama, boolean claudeOnly) {
        List<Provider> providers = new ArrayList<>();
        for (String raw : names == null ? List.<String>of() : names) {
            String name = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) continue;
            switch (name) {
                case CLAUDE -> providers.add(new ClaudeProvider(claude));
                case OLLAMA_LOCAL -> {
                    if (!claudeOnly) providers.add(new OllamaProvider(ollama));
                }
                default -> {
                    log.error("{} unknown or unapproved router provider '{}': a hosted fallback needs separate approval "
                            + "and is not implemented; semantic routing stays off", ROUTER_MARKER, name.length() > 30
                            ? name.substring(0, 30) : name);
                    return List.of();
                }
            }
        }
        return providers;
    }

    boolean enabled() {
        return enabled;
    }

    boolean shadowEnabled() {
        return shadow;
    }

    double shadowSampleRate() {
        return shadowSampleRate;
    }

    Settings settings() {
        return settings;
    }

    /**
     * A routing decision for one message, or a Result without one. Never throws for a provider problem.
     *
     * @param previous the previous answered request of this conversation, or null
     * @param catalog  the tools the router may choose from
     */
    Result route(VerifiedMyHrActor actor, String message, WaviDialogueStore.State previous, LocalDate today,
                 List<WaviTool> catalog) {
        long started = System.nanoTime();
        Result result = decide(actor, message, previous, today, catalog, started);
        WaviRouterDecision decision = result.decision();
        ClaudeChatClient.RouteUsage usage = result.usage() == null ? ClaudeChatClient.RouteUsage.NONE : result.usage();
        // Codes, counts and timings only: never the message, arguments' values or the user.
        log.info("{} mode={} outcome={} provider={} attempts={} retries={} failures={} tool={} confidence={} "
                        + "language={} followUp={} latencyMs={} tokensIn={} tokensOut={} cacheRead={} cacheWrite={}",
                ROUTER_MARKER, enabled ? "serve" : "shadow", result.outcome(), result.provider(), result.attempts(),
                result.retries(), result.failures(),
                decision == null ? "none" : decision.tool(), decision == null ? "none" : decision.confidence(),
                decision == null || decision.language() == null ? "none" : decision.language(), previous != null,
                result.latencyMs(), usage.inputTokens(), usage.outputTokens(), usage.cacheReadTokens(),
                usage.cacheWriteTokens());
        return result;
    }

    private Result decide(VerifiedMyHrActor actor, String message, WaviDialogueStore.State previous, LocalDate today,
                          List<WaviTool> catalog, long started) {
        if ((!enabled && !shadow) || actor == null) return result(null, "disabled", "none", 0, started);
        String question = WaviRouterPrivacy.sanitize(message, settings.maxQuestionChars());
        if (question.isBlank()) return result(null, "empty", "none", 0, started);
        WaviRouterPrompt.Request request = WaviRouterPrompt.build(catalog, actor.role(), today, previous, question);
        Optional<WaviRouterDecision> cached = cached(request.cacheKey());
        if (cached.isPresent()) return result(cached.get(), "cached", "cache", 0, started);
        if (!admitUser(actor.userId())) return result(null, "user-limit", "none", 0, started);
        if (!admitToday()) return result(null, "daily-limit", "none", 0, started);

        String outcome = "no-provider";
        String lastProvider = "none";
        Trace trace = new Trace();
        for (Provider provider : providers) {
            if (!provider.usable() || circuitOpen(provider.name())) continue;
            Duration attemptTimeout = provider.attemptTimeout() == null ? Duration.ZERO : provider.attemptTimeout();
            if (attemptTimeout.isNegative() || attemptTimeout.compareTo(settings.totalBudget()) > 0) {
                // Configuration error, never silent: this provider's single request could outlast the deadline.
                trace.failures.add(attemptTimeout.isNegative() ? "invalid_attempt_timeout"
                        : "attempt_timeout_exceeds_deadline");
                outcome = "budget";
                continue;
            }
            lastProvider = provider.name();
            for (int attempt = 1; attempt <= settings.maxAttempts(); attempt++) {
                if (!fits(started, attemptTimeout, 0)) {
                    return result(null, "budget", lastProvider, started, trace);
                }
                if (Thread.currentThread().isInterrupted()) {
                    return result(null, "cancelled", lastProvider, started, trace);
                }
                trace.attempts++;
                try {
                    Reply reply = provider.routeWithUsage(request, settings);
                    trace.usage = reply.usage();
                    if (elapsed(started).compareTo(settings.totalBudget()) > 0) {
                        // A decision that arrives after the deadline is never acted on, whatever it says.
                        trace.failures.add("deadline_exceeded");
                        return result(null, "deadline", provider.name(), started, trace);
                    }
                    Optional<WaviRouterDecision> decision = WaviRouterDecisionParser.parse(reply.json(), provider.source());
                    if (decision.isEmpty()) {
                        outcome = "invalid-output";
                        trace.failures.add("invalid_output");
                        failed(provider.name());
                        break;
                    }
                    succeeded(provider.name());
                    if (decision.get().confidence() != WaviRouterDecision.Confidence.LOW) {
                        remember(request.cacheKey(), decision.get());
                    }
                    return result(decision.get(), "decision", provider.name(), started, trace);
                } catch (RuntimeException unexpected) {
                    Failure failure = failureFor(provider, unexpected);
                    outcome = failure.outcome();
                    trace.failures.add(failure.code());
                    // A provider refusal is the answer. Do not ask a fallback provider after it.
                    if (failure.terminal()) return result(null, outcome, provider.name(), started, trace);
                    failed(provider.name());
                    if (Thread.currentThread().isInterrupted()) {
                        return result(null, "cancelled", provider.name(), started, trace);
                    }
                    // Only a provider's explicit transient classification is retried, and only when a full
                    // attempt still fits after its bounded Retry-After or the standard backoff.
                    if (!failure.retryable() || attempt == settings.maxAttempts()) break;
                    long waitMillis = failure.retryAfterMillis() > 0 ? failure.retryAfterMillis() : TRANSIENT_BACKOFF_MILLIS;
                    if (!fits(started, attemptTimeout, waitMillis)) {
                        return result(null, "budget", provider.name(), started, trace);
                    }
                    if (!pause(waitMillis)) return result(null, "cancelled", provider.name(), started, trace);
                    trace.retries++;
                    provider.retryStarted();
                }
            }
        }
        return result(null, outcome, lastProvider, started, trace);
    }

    /** Backoff before retrying an overloaded or unavailable provider that sent no Retry-After. */
    static final long TRANSIENT_BACKOFF_MILLIS = 250;

    /** A broken optional provider classifier must not let a provider error escape the fail-safe routing loop. */
    private static Failure failureFor(Provider provider, RuntimeException exception) {
        try {
            Failure failure = provider.failure(exception);
            return failure == null ? new Failure("error", "error", false, 0, false) : failure;
        } catch (RuntimeException ignored) {
            return new Failure("error", "error", false, 0, false);
        }
    }

    /** What one routing call did, across its attempts. */
    private static final class Trace {
        int attempts;
        int retries;
        final List<String> failures = new ArrayList<>();
        ClaudeChatClient.RouteUsage usage;
    }

    /** Whether, after waiting {@code waitMillis}, a whole attempt of {@code attemptTimeout} still ends in time. */
    private boolean fits(long started, Duration attemptTimeout, long waitMillis) {
        Duration remaining = settings.totalBudget().minus(elapsed(started)).minusMillis(waitMillis);
        if (remaining.isNegative() || remaining.isZero()) return false;
        return attemptTimeout.isZero() || remaining.compareTo(attemptTimeout) >= 0;
    }

    /** Waits before a retry; false when the routing thread was interrupted (the request is being cancelled). */
    private static boolean pause(long millis) {
        if (millis <= 0) return true;
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String outcomeOf(ClaudeChatClient.ClaudeChatException failure) {
        if (failure instanceof ClaudeChatClient.ClaudeIncompleteResponseException) return "incomplete";
        if (failure instanceof ClaudeChatClient.ClaudeTimeoutException) return "timeout";
        if (failure instanceof ClaudeChatClient.ClaudeRequestRejectedException) return "rejected";
        if (failure instanceof ClaudeChatClient.ClaudeConfigurationException) return "configuration";
        if (failure instanceof ClaudeChatClient.ClaudeSpendLimitException) return "spend-limit";
        return "unavailable";
    }

    private Result result(WaviRouterDecision decision, String outcome, String provider, long started, Trace trace) {
        return new Result(decision, outcome, provider, trace.attempts, elapsed(started).toMillis(), trace.usage,
                trace.retries, trace.failures);
    }

    private Result result(WaviRouterDecision decision, String outcome, String provider, int attempts, long started) {
        return result(decision, outcome, provider, attempts, started, null);
    }

    private Result result(WaviRouterDecision decision, String outcome, String provider, int attempts, long started,
                          ClaudeChatClient.RouteUsage usage) {
        return new Result(decision, outcome, provider, attempts, elapsed(started).toMillis(), usage);
    }

    private static Duration elapsed(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    // --- Cost controls ----------------------------------------------------------------------------------------------

    private synchronized Optional<WaviRouterDecision> cached(String key) {
        CachedDecision entry = cache.get(key);
        if (entry == null) return Optional.empty();
        if (entry.expires().isBefore(clock.instant())) {
            cache.remove(key);
            return Optional.empty();
        }
        return Optional.of(entry.decision());
    }

    private synchronized void remember(String key, WaviRouterDecision decision) {
        if (settings.cacheSize() == 0) return;
        cache.put(key, new CachedDecision(decision, clock.instant().plus(settings.cacheTtl())));
        while (cache.size() > settings.cacheSize()) {
            cache.remove(cache.keySet().iterator().next());
        }
    }

    private synchronized boolean admitUser(String userId) {
        Instant now = clock.instant();
        Deque<Instant> calls = userCalls.computeIfAbsent(userId, key -> new ArrayDeque<>());
        while (!calls.isEmpty() && calls.peekFirst().isBefore(now.minus(settings.userWindow()))) calls.pollFirst();
        if (calls.size() >= settings.userLimit()) return false;
        calls.addLast(now);
        if (userCalls.size() > 50_000) userCalls.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        return true;
    }

    private synchronized boolean admitToday() {
        LocalDate day = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        if (!day.equals(countingDay)) {
            countingDay = day;
            callsToday = 0;
        }
        if (callsToday >= settings.dailyLimit()) return false;
        callsToday++;
        return true;
    }

    private synchronized boolean circuitOpen(String provider) {
        Instant until = openUntil.get(provider);
        if (until == null) return false;
        if (until.isAfter(clock.instant())) return true;
        openUntil.remove(provider);
        consecutiveFailures.remove(provider);
        return false;
    }

    private synchronized void failed(String provider) {
        int failures = consecutiveFailures.merge(provider, 1, Integer::sum);
        if (failures >= settings.circuitFailures()) {
            openUntil.put(provider, clock.instant().plus(settings.circuitOpen()));
            log.warn("{} circuit-open provider={} forSeconds={}", ROUTER_MARKER, provider, settings.circuitOpen().toSeconds());
        }
    }

    private synchronized void succeeded(String provider) {
        consecutiveFailures.remove(provider);
    }

    // --- Providers --------------------------------------------------------------------------------------------------

    static final class ClaudeProvider implements Provider {
        private final ClaudeChatClient claude;

        ClaudeProvider(ClaudeChatClient claude) {
            this.claude = claude;
        }

        public String name() {
            return CLAUDE;
        }

        public WaviRouterDecision.Source source() {
            return WaviRouterDecision.Source.CLAUDE;
        }

        public boolean usable() {
            return claude != null;
        }

        public String route(WaviRouterPrompt.Request request, Settings settings) {
            return routeWithUsage(request, settings).json();
        }

        @Override
        public Reply routeWithUsage(WaviRouterPrompt.Request request, Settings settings) {
            ClaudeChatClient.RouteResult result = claude.routeJsonWithUsage(request.system(), request.user(),
                    request.schema(), settings.maxOutputTokens(), settings.effort());
            return new Reply(result.json(), result.usage());
        }

        @Override
        public Duration attemptTimeout() {
            return claude == null ? Duration.ZERO : claude.routerTimeout();
        }
    }

    static final class OllamaProvider implements Provider {
        private final OllamaClient ollama;

        OllamaProvider(OllamaClient ollama) {
            this.ollama = ollama;
        }

        public String name() {
            return OLLAMA_LOCAL;
        }

        public WaviRouterDecision.Source source() {
            return WaviRouterDecision.Source.OLLAMA_DEV;
        }

        /** Development only: never a remote Ollama (on Render there is none). */
        public boolean usable() {
            return ollama != null && ollama.isLocalEndpoint();
        }

        public String route(WaviRouterPrompt.Request request, Settings settings) {
            return ollama.routeJson(request.system(), request.user(), request.schema(), settings.maxOutputTokens());
        }

        @Override
        public Duration attemptTimeout() {
            return ollama == null ? Duration.ZERO : ollama.routerTimeout();
        }
    }
}
