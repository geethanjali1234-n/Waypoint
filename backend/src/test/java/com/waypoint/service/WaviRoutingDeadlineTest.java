package com.waypoint.service;

import com.sun.net.httpserver.HttpServer;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import com.waypoint.security.VerifiedMyHrActor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * P3B hardening: routing deadlines, transient-only bounded retries, safe failure classification and the routing HTTP
 * client's total timeout and single request per attempt. HTTP tests use a loopback server only; nothing leaves the
 * machine and no model is called.
 */
class WaviRoutingDeadlineTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-11-05");
    private static final VerifiedMyHrActor ASHA = TestActors.actor("emp-a1", WaviEvaluationWorld.ORG, UserRole.EMPLOYEE);
    private static final String GOOD = "{\"decision\":{\"tool\":\"get_my_profile_field\",\"arguments\":{\"field\":"
            + "\"REPORTING_MANAGER\"}},\"confidence\":\"HIGH\",\"language\":\"telugu\"}";
    private static final Map<String, Object> SCHEMA = Map.of("type", "object", "properties", Map.of(),
            "additionalProperties", false);

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    // --- The routing HTTP client against a loopback server -----------------------------------------------------------

    private ClaudeChatClient client(Duration timeout, HttpServerHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> handler.handle(exchange));
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
        ClaudeChatClient claude = new ClaudeChatClient(RestClient.builder().build(), "test-key", url, "claude-sonnet-5");
        claude.useRouterModel("claude-haiku-5-5", "auto");
        claude.useRouterHttp(timeout);
        return claude;
    }

    interface HttpServerHandler {
        void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body,
                                Map<String, String> headers) throws IOException {
        exchange.getRequestBody().readAllBytes();
        headers.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Test
    void aSlowRoutingRequestEndsAtTheTotalTimeoutAsOneRequest() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ClaudeChatClient claude = client(Duration.ofMillis(300), exchange -> {
            requests.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}", Map.of());
        });
        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> claude.routeJsonWithUsage("S", "U", SCHEMA, 400, "low"))
                    .isInstanceOf(ClaudeChatClient.ClaudeTimeoutException.class)
                    .satisfies(failure -> assertThat(((ClaudeChatClient.ClaudeChatException) failure).failureClass())
                            .isEqualTo("timeout"));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2000));
        } finally {
            release.countDown();
        }
        assertThat(requests.get()).as("no hidden resend").isEqualTo(1);
    }

    @Test
    void aColdStructuredRouteCompletingInsideThreePointFiveSecondsSucceedsWithinTheFourSecondDeadline()
            throws IOException {
        AtomicInteger requests = new AtomicInteger();
        ClaudeChatClient claude = client(Duration.ofMillis(3500), exchange -> {
            requests.incrementAndGet();
            try {
                Thread.sleep(2700);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("loopback handler interrupted", interrupted);
            }
            respond(exchange, 200, "{\"stop_reason\":\"end_turn\",\"content\":[{\"type\":\"text\",\"text\":\""
                    + GOOD.replace("\"", "\\\"") + "\"}]}", Map.of());
        });
        WaviSemanticRouting routing = routing(1, Duration.ofSeconds(4), new WaviSemanticRouting.ClaudeProvider(claude));

        WaviSemanticRouting.Result result = route(routing);

        assertThat(result.outcome()).isEqualTo("decision");
        assertThat(result.decision().tool()).isEqualTo(WaviTools.GET_MY_PROFILE_FIELD);
        assertThat(result.latencyMs()).isLessThan(4000);
        assertThat(requests.get()).as("one POST for the successful attempt").isEqualTo(1);
    }

    @Test
    void aRequestExceedingThreePointFiveSecondsTimesOutAsOnePost() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ClaudeChatClient claude = client(Duration.ofMillis(3500), exchange -> {
            requests.incrementAndGet();
            try {
                release.await(6, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{}", Map.of());
        });
        try {
            assertThatThrownBy(() -> claude.routeJsonWithUsage("S", "U", SCHEMA, 400, "low"))
                    .isInstanceOf(ClaudeChatClient.ClaudeTimeoutException.class);
        } finally {
            release.countDown();
        }
        assertThat(requests.get()).as("no hidden resend after a whole-request timeout").isEqualTo(1);
    }

    @Test
    void aConnectionDroppedWithoutAnAnswerIsNeverResent() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        ClaudeChatClient claude = client(Duration.ofSeconds(2), exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.close();
        });
        assertThatThrownBy(() -> claude.routeJsonWithUsage("S", "U", SCHEMA, 400, "low"))
                .isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void failuresAreClassifiedFromStatusAndErrorTypeWithoutTheMessage() throws IOException {
        List<int[]> statuses = List.of(new int[]{529}, new int[]{429}, new int[]{503}, new int[]{500}, new int[]{400},
                new int[]{401});
        AtomicReference<Integer> next = new AtomicReference<>();
        ClaudeChatClient claude = client(Duration.ofSeconds(2), exchange -> respond(exchange, next.get(),
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"PRIVATE DETAIL\"}}",
                Map.of("request-id", "req_011CSynthetic", "retry-after", "2")));
        Map<Integer, String> expectedClass = Map.of(529, "overloaded", 429, "rate_limited", 503, "unavailable_503",
                500, "server_5xx", 400, "rejected_400", 401, "auth_or_config");
        for (int[] status : statuses) {
            next.set(status[0]);
            Throwable failure = null;
            try {
                claude.routeJsonWithUsage("S", "U", SCHEMA, 400, "low");
            } catch (ClaudeChatClient.ClaudeChatException thrown) {
                failure = thrown;
            }
            ClaudeChatClient.ClaudeChatException chat = (ClaudeChatClient.ClaudeChatException) failure;
            assertThat(chat).as("status " + status[0]).isNotNull();
            assertThat(chat.failureClass()).isEqualTo(expectedClass.get(status[0]));
            assertThat(chat.status()).isEqualTo(status[0]);
            assertThat(chat.errorType()).isEqualTo("overloaded_error");
            assertThat(chat.requestId()).isEqualTo("req_011CSynthetic");
            assertThat(chat.getMessage()).isNull();
            assertThat(String.valueOf(chat)).doesNotContain("PRIVATE DETAIL");
            assertThat(chat.transientFailure()).isEqualTo(List.of(429, 529, 503).contains(status[0]));
            if (status[0] == 429) assertThat(chat.retryAfterMillis()).isEqualTo(2000);
        }
    }

    // --- The routing loop with scripted providers ------------------------------------------------------------------

    /** A provider following a script of steps, with an optional per-attempt timeout. */
    static class Scripted implements WaviSemanticRouting.Provider {
        final List<Supplier<String>> steps = new ArrayList<>();
        Duration attemptTimeout = Duration.ZERO;
        final AtomicInteger calls = new AtomicInteger();

        Scripted then(Supplier<String> step) {
            steps.add(step);
            return this;
        }

        public String name() {
            return "claude";
        }

        public WaviRouterDecision.Source source() {
            return WaviRouterDecision.Source.CLAUDE;
        }

        public boolean usable() {
            return true;
        }

        public String route(WaviRouterPrompt.Request request, WaviSemanticRouting.Settings settings) {
            int call = calls.getAndIncrement();
            return steps.get(Math.min(call, steps.size() - 1)).get();
        }

        @Override
        public Duration attemptTimeout() {
            return attemptTimeout;
        }
    }

    private static Supplier<String> failing(int status, long retryAfterMillis) {
        return () -> {
            throw new ClaudeChatClient.ClaudeUnavailableException().withDetails(status, "overloaded_error", null,
                    retryAfterMillis);
        };
    }

    private static Supplier<String> sleeping(long millis, String output) {
        return () -> {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ClaudeChatClient.ClaudeUnavailableException().withDetails(529, null, null, 0);
            }
            return output;
        };
    }

    private static WaviSemanticRouting routing(int maxAttempts, Duration budget, WaviSemanticRouting.Provider provider) {
        return new WaviSemanticRouting(true, List.of(provider), new WaviSemanticRouting.Settings(500, 400, maxAttempts,
                budget, "low", 1000, Duration.ofMinutes(10), 1000, Duration.ZERO, 0, 50, Duration.ofSeconds(60)),
                Clock.systemUTC());
    }

    private static List<WaviTool> catalog() {
        WaviEvaluationWorld.Options options = new WaviEvaluationWorld.Options();
        options.toolLayer = true;
        return new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options).service.toolLayer().registry().all();
    }

    private static WaviSemanticRouting.Result route(WaviSemanticRouting routing) {
        return routing.route(ASHA, "నా మేనేజర్ ఎవరు?", null, TODAY, catalog());
    }

    @Test
    void servingMakesOneAttemptByDefault() {
        assertThat(WaviSemanticRouting.Settings.defaults().maxAttempts()).isEqualTo(1);
        Scripted provider = new Scripted().then(failing(529, 0));
        WaviSemanticRouting.Result result = route(new WaviSemanticRouting(true, List.of(provider),
                WaviSemanticRouting.Settings.defaults(), Clock.systemUTC()));
        assertThat(provider.calls.get()).isEqualTo(1);
        assertThat(result.retries()).isZero();
        assertThat(result.failures()).containsExactly("overloaded");
    }

    @Test
    void transientFailuresAreRetriedUntilTheAttemptCapAndEveryRetryIsRecorded() {
        Scripted provider = new Scripted().then(failing(529, 0));
        WaviSemanticRouting.Result result = route(routing(3, Duration.ofSeconds(4), provider));
        assertThat(provider.calls.get()).isEqualTo(3);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(result.retries()).isEqualTo(2);
        assertThat(result.failures()).containsExactly("overloaded", "overloaded", "overloaded");
        assertThat(result.decision()).isNull();

        Scripted recovers = new Scripted().then(failing(429, 0)).then(() -> GOOD);
        WaviSemanticRouting.Result second = route(routing(2, Duration.ofSeconds(4), recovers));
        assertThat(second.outcome()).isEqualTo("decision");
        assertThat(second.retries()).isEqualTo(1);
        assertThat(second.failures()).containsExactly("rate_limited");
    }

    @Test
    void aProviderCanDeclareItsOwnTransientFailureWithoutLeakingItsClientTypeIntoTheRouter() {
        RuntimeException transientFailure = new IllegalStateException("synthetic provider overload");
        Scripted provider = new Scripted() {
            @Override
            public WaviSemanticRouting.Failure failure(RuntimeException exception) {
                return new WaviSemanticRouting.Failure("provider_overloaded", "unavailable", true, 0, false);
            }
        }.then(() -> { throw transientFailure; }).then(() -> GOOD);

        WaviSemanticRouting.Result result = route(routing(2, Duration.ofSeconds(4), provider));

        assertThat(result.outcome()).isEqualTo("decision");
        assertThat(provider.calls.get()).isEqualTo(2);
        assertThat(result.retries()).isOne();
        assertThat(result.failures()).containsExactly("provider_overloaded");
    }

    @Test
    void nonTransientFailuresAreNeverRetried() {
        for (Supplier<String> failure : List.<Supplier<String>>of(failing(0, 0), failing(500, 0),
                () -> { throw new ClaudeChatClient.ClaudeTimeoutException(); },
                () -> { throw new ClaudeChatClient.ClaudeRequestRejectedException().withDetails(400, null, null, 0); })) {
            Scripted provider = new Scripted().then(failure);
            WaviSemanticRouting.Result result = route(routing(3, Duration.ofSeconds(4), provider));
            assertThat(provider.calls.get()).as(result.failures().toString()).isEqualTo(1);
            assertThat(result.retries()).isZero();
        }
    }

    @Test
    void aRetryIsSkippedWhenRetryAfterOrAFullAttemptNoLongerFitsTheDeadline() {
        Scripted longWait = new Scripted().then(failing(429, 10_000));
        WaviSemanticRouting.Result waited = route(routing(3, Duration.ofSeconds(4), longWait));
        assertThat(longWait.calls.get()).isEqualTo(1);
        assertThat(waited.outcome()).isEqualTo("budget");

        // Warm up prompt building so the 100 ms margin below measures the deadline rule, not first-use setup.
        route(routing(1, Duration.ofSeconds(4), new Scripted().then(() -> GOOD)));
        Scripted bounded = new Scripted().then(failing(529, 0));
        bounded.attemptTimeout = Duration.ofMillis(3900);
        WaviSemanticRouting.Result noRoom = route(routing(3, Duration.ofSeconds(4), bounded));
        assertThat(bounded.calls.get()).as("a 3.9 s attempt cannot start with less than 3.9 s left").isEqualTo(1);
        assertThat(noRoom.outcome()).isEqualTo("budget");

        Scripted tooSlow = new Scripted().then(() -> GOOD);
        tooSlow.attemptTimeout = Duration.ofSeconds(5);
        WaviSemanticRouting.Result misconfigured = route(routing(1, Duration.ofSeconds(4), tooSlow));
        assertThat(tooSlow.calls.get()).as("an attempt that could outlast the deadline is never started").isZero();
        assertThat(misconfigured.failures()).containsExactly("attempt_timeout_exceeds_deadline");

        Scripted invalidTimeout = new Scripted().then(() -> GOOD);
        invalidTimeout.attemptTimeout = Duration.ofMillis(-1);
        WaviSemanticRouting.Result invalid = route(routing(1, Duration.ofSeconds(4), invalidTimeout));
        assertThat(invalidTimeout.calls.get()).as("a provider cannot bypass deadline planning with a negative timeout")
                .isZero();
        assertThat(invalid.failures()).containsExactly("invalid_attempt_timeout");

        Scripted permitted = new Scripted().then(() -> GOOD);
        permitted.attemptTimeout = Duration.ofMillis(3500);
        WaviSemanticRouting.Result insideDeadline = route(routing(1, Duration.ofSeconds(4), permitted));
        assertThat(permitted.calls.get()).isOne();
        assertThat(insideDeadline.outcome()).isEqualTo("decision");
    }

    @Test
    void aDecisionArrivingAfterTheDeadlineIsDiscarded() {
        Scripted late = new Scripted().then(sleeping(300, GOOD));
        WaviSemanticRouting.Result result = route(routing(1, Duration.ofMillis(150), late));
        assertThat(result.outcome()).isEqualTo("deadline");
        assertThat(result.decision()).isNull();
        assertThat(result.failures()).containsExactly("deadline_exceeded");
    }

    @Test
    void anInterruptedRoutingCallStopsWithoutRetrying() throws Exception {
        Scripted slow = new Scripted().then(sleeping(5_000, GOOD));
        AtomicReference<WaviSemanticRouting.Result> outcome = new AtomicReference<>();
        WaviSemanticRouting routing = routing(3, Duration.ofSeconds(10), slow);
        List<WaviTool> catalog = catalog();
        Thread caller = new Thread(() -> outcome.set(routing.route(ASHA, "నా మేనేజర్ ఎవరు?", null, TODAY, catalog)));
        caller.start();
        Thread.sleep(200);
        caller.interrupt();
        caller.join(3_000);
        assertThat(outcome.get()).isNotNull();
        assertThat(outcome.get().outcome()).isEqualTo("cancelled");
        assertThat(slow.calls.get()).isEqualTo(1);
    }

    @Test
    void anExpiredRoutingDecisionNeverRunsATool() {
        String question = "నా మేనేజర్ ఎవరు?";
        WaviEvaluationWorld plain = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        MyHrChatResponse baseline = plain.service.chat(plain.actor, question, List.of(), StampedHistory.SESSION);

        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        world.service.useSemanticRouting(routing(1, Duration.ofMillis(150), new Scripted().then(sleeping(300, GOOD))));
        MyHrChatResponse response = world.service.chat(world.actor, question, List.of(), StampedHistory.SESSION);

        // The late decision is discarded (no tool runs); the message the scope guard would refuse gets the honest
        // routing-unavailable reply instead of an off-topic claim.
        assertThat(response.message()).isIn(baseline.message(), MyHrChatService.ROUTER_UNAVAILABLE_REPLY);
        assertThat(Mockito.mockingDetails(world.employees).getInvocations()).isEmpty();
    }

    @Test
    void theHarnessCapCountsEveryRetryAtTheHttpBoundary() {
        Scripted overloaded = new Scripted().then(failing(529, 0));
        WaviLiveRouterEvaluation.MeteredProvider metered = new WaviLiveRouterEvaluation.MeteredProvider(overloaded, 2);
        WaviSemanticRouting.Result result = route(routing(3, Duration.ofSeconds(4), metered));
        assertThat(overloaded.calls.get()).isEqualTo(2);
        assertThat(metered.httpAttempts()).isEqualTo(2);
        assertThat(metered.capBlocked()).isEqualTo(1);
        assertThat(result.attempts()).isEqualTo(3);
    }
}
