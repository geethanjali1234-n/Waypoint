package com.waypoint.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OFFLINE and free: the cumulative spending cap for the agent's paid calls. Unit tests of the limiter, then the real
 * {@link ClaudeChatClient} against a local stub server to prove a refused request is never sent, and the agent loop to
 * prove a cap refusal is not a provider outage.
 */
class ClaudeSpendLimiterTest {

    private static final ClaudeChatClient.RouteUsage USAGE = new ClaudeChatClient.RouteUsage(1000, 100, 0, 0);
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static Path ledger(Path dir, String content) throws IOException {
        Path file = dir.resolve("ledger.txt");
        Files.writeString(file, content);
        return file;
    }

    // --- Limiter ---------------------------------------------------------------------------------------------------------------

    @Test
    void noCapMeansNoLimitAndNothingIsCounted() {
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0, null, null);
        assertThat(limiter.enabled()).isFalse();
        assertThat(limiter.reserve(1_000)).isZero();
        assertThat(limiter.spent()).isZero();
    }

    @Test
    void aReservationBeyondTheCapIsRefusedBeforeAnythingIsSpent() {
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, null, null);
        limiter.reserve(0.03);
        assertThatThrownBy(() -> limiter.reserve(0.0201)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        assertThat(limiter.spent()).isEqualTo(0.03);
        limiter.reserve(0.02); // exactly the cap is allowed
        assertThat(limiter.spent()).isEqualTo(0.05, org.assertj.core.data.Offset.offset(1e-12));
        assertThatThrownBy(() -> limiter.reserve(0.000001)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
    }

    @Test
    void settlingReplacesTheReservationWithTheRealCostFromUsage() {
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, null, null);
        double reserved = limiter.reserve(0.01);
        limiter.settle(reserved, USAGE);
        // 1000 in at $0.10/M + 100 out at $0.50/M = $0.00015
        assertThat(limiter.spent()).isEqualTo(0.00015, org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void aRequestThatFailedAfterSendingKeepsItsWorstCaseReservation() {
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, null, null);
        limiter.reserve(0.01); // never settled: the response was lost
        assertThat(limiter.spent()).isEqualTo(0.01);
    }

    @Test
    void theTotalSurvivesARestart(@TempDir Path dir) throws IOException {
        Path file = ledger(dir, "0.00000000");
        ClaudeSpendLimiter first = new ClaudeSpendLimiter(0.05, file, null);
        first.settle(first.reserve(0.01), new ClaudeChatClient.RouteUsage(100_000, 20_000, 0, 0)); // $0.02
        assertThat(first.spent()).isEqualTo(0.02, org.assertj.core.data.Offset.offset(1e-9));

        ClaudeSpendLimiter restarted = new ClaudeSpendLimiter(0.05, file, null);
        assertThat(restarted.spent()).isEqualTo(0.02, org.assertj.core.data.Offset.offset(1e-9));
        restarted.reserve(0.03);
        assertThatThrownBy(() -> restarted.reserve(0.01)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        assertThat(new ClaudeSpendLimiter(0.05, file, null).spent()).isEqualTo(0.05, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aMissingLedgerFailsClosed(@TempDir Path dir) {
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, dir.resolve("absent.txt"), null);
        assertThat(limiter.isClosed()).isTrue();
        assertThatThrownBy(() -> limiter.reserve(0.0000001)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
    }

    @Test
    void aCorruptLedgerFailsClosed(@TempDir Path dir) throws IOException {
        for (String bad : List.of("not a number", "", "-1", "NaN", "Infinity", "0.01\u0000garbage", "0,01")) {
            ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, ledger(dir, bad), null);
            assertThat(limiter.isClosed()).as(bad).isTrue();
            assertThatThrownBy(() -> limiter.reserve(0.0000001)).as(bad)
                    .isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        }
    }

    @Test
    void aLedgerThatCannotBeSavedClosesTheLimiterInsteadOfLettingSpendingGoUncounted(@TempDir Path dir) throws IOException {
        Path sub = Files.createDirectory(dir.resolve("sub"));
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, ledger(sub, "0.00000000"), null);
        limiter.reserve(0.001);
        Files.delete(sub.resolve("ledger.txt"));
        Files.delete(sub);
        assertThatThrownBy(() -> limiter.reserve(0.001)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        assertThat(limiter.isClosed()).isTrue();
        assertThat(limiter.spent()).isEqualTo(0.001); // the failed reservation was rolled back
        assertThatThrownBy(() -> limiter.reserve(0.000001)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
    }

    @Test
    void invalidAmountsAreRefused() {
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, null, null);
        for (double bad : new double[]{Double.NaN, -0.01, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> limiter.reserve(bad)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        }
    }

    @Test
    void aCapWithoutALedgerFileCannotBeEnforcedAcrossRestartsAndRefusesEverything() {
        ClaudeChatClient client = new ClaudeChatClient(RestClient.builder().build(), "sk-ant-test-key-0000", "http://127.0.0.1:9/v1/messages",
                "claude-sonnet-5-5");
        client.configureSpend(0.05, "");
        assertThatThrownBy(() -> client.converse("s", List.of(java.util.Map.of("role", "user", "content", "hi")), List.of(), true, 16))
                .isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        client.configureSpend(0, ""); // no cap asked for: unchanged behaviour (then the call fails on the dead URL, not the cap)
        assertThatThrownBy(() -> client.converse("s", List.of(java.util.Map.of("role", "user", "content", "hi")), List.of(), true, 16))
                .isNotInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
    }

    @Test
    void concurrentRequestsCannotTogetherExceedTheCap(@TempDir Path dir) throws Exception {
        Path file = ledger(dir, "0.00000000");
        ClaudeSpendLimiter limiter = new ClaudeSpendLimiter(0.05, file, null);
        int threads = 32;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                go.await();
                for (int n = 0; n < 20; n++) {
                    try {
                        limiter.reserve(0.004);
                        allowed.incrementAndGet();
                    } catch (ClaudeChatClient.ClaudeSpendLimitException refused) {
                        // expected once the cap is reached
                    }
                }
                return null;
            }));
        }
        go.countDown();
        for (var future : futures) future.get();
        pool.shutdown();
        assertThat(allowed.get()).isEqualTo(12); // 12 x 0.004 = 0.048; a 13th would pass 0.05
        assertThat(limiter.spent()).isLessThanOrEqualTo(0.05);
        assertThat(new ClaudeSpendLimiter(0.05, file, null).spent()).isEqualTo(0.048, org.assertj.core.data.Offset.offset(1e-9));
    }

    // --- Through the real client -----------------------------------------------------------------------------------------------

    private String stub(AtomicInteger hits) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] reply = ("{\"content\":[{\"type\":\"text\",\"text\":\"ok\"}],\"stop_reason\":\"end_turn\","
                    + "\"usage\":{\"input_tokens\":2000,\"output_tokens\":50}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    private static ClaudeChatClient client(String url, ClaudeSpendLimiter limiter) {
        ClaudeChatClient client = new ClaudeChatClient(RestClient.builder().build(), "sk-ant-test-key-0000", url, "claude-sonnet-5-5");
        client.useAgentModel("claude-haiku-5-5", Duration.ofSeconds(5));
        client.useSpendLimiter(limiter);
        return client;
    }

    private static void call(ClaudeChatClient client) {
        client.converse("system", List.of(java.util.Map.of("role", "user", "content", "hello")), List.of(), true, 1024);
    }

    @Test
    void aRefusedRequestIsNeverSentAndTheCapStopsRealCalls(@TempDir Path dir) throws Exception {
      try (var reservation = syntheticReservation()) {
        AtomicInteger hits = new AtomicInteger();
        Path file = ledger(dir, "0.00000000");
        // One call reserves ~ (200 + a few chars/2 in-tokens) * 0.125/M + 1024 * 0.5/M = about $0.0005, settles at ~$0.000225.
        ClaudeChatClient client = client(stub(hits), new ClaudeSpendLimiter(0.001, file, null));
        int sent = 0;
        for (int i = 0; i < 50; i++) {
            try {
                call(client);
                sent++;
            } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
                break;
            }
        }
        assertThat(sent).isGreaterThanOrEqualTo(2).isLessThan(50);
        assertThat(hits.get()).isEqualTo(sent); // the refused call never reached the server
        assertThat(new ClaudeSpendLimiter(0.001, file, null).spent()).isLessThanOrEqualTo(0.001);
        int before = hits.get();
        assertThatThrownBy(() -> call(client)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        assertThat(hits.get()).isEqualTo(before);
      }
    }

    @Test
    void theCapStillHoldsAfterABackendRestartAgainstTheSameLedger(@TempDir Path dir) throws Exception {
      try (var reservation = syntheticReservation()) {
        AtomicInteger hits = new AtomicInteger();
        String url = stub(hits);
        Path file = ledger(dir, "0.00000000");
        ClaudeChatClient first = client(url, new ClaudeSpendLimiter(0.001, file, null));
        try {
            while (true) call(first);
        } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
            // first process hit the cap
        }
        int used = hits.get();
        ClaudeChatClient restarted = client(url, new ClaudeSpendLimiter(0.001, file, null));
        assertThatThrownBy(() -> call(restarted)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        assertThat(hits.get()).isEqualTo(used);
      }
    }

    private static org.mockito.MockedStatic<ClaudeSpendLimiter> syntheticReservation() {
        var mocked = org.mockito.Mockito.mockStatic(ClaudeSpendLimiter.class);
        mocked.when(() -> ClaudeSpendLimiter.fromSettings(org.mockito.Mockito.anyDouble(), org.mockito.Mockito.any()))
                .thenCallRealMethod();
        mocked.when(() -> ClaudeSpendLimiter.closed(org.mockito.Mockito.anyDouble())).thenCallRealMethod();
        return mocked;
    }

    // --- The agent loop --------------------------------------------------------------------------------------------------------

    @Test
    void aCapRefusalFallsBackToTheNormalAnswerAndNeverOpensTheCircuitBreaker() {
        AtomicInteger attempts = new AtomicInteger();
        ClaudeChatClient.ToolConversation capped = (system, messages, tools, allow, max) -> {
            attempts.incrementAndGet();
            throw new ClaudeChatClient.ClaudeSpendLimitException();
        };
        var options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        WaviAgentLoop.Settings settings = WaviAgentLoop.Settings.defaults(true);
        world.service.useAgentLoop(new WaviAgentLoop(capped, (organization, role) -> true, settings, Clock.systemUTC()));
        for (int question = 0; question < settings.breakerThreshold() + 4; question++) {
            String answer = world.service.chat(world.actor, "what is my job title", List.of(), StampedHistory.SESSION).message();
            assertThat(answer).contains("Senior Data Analyst"); // the deterministic pipeline answered
        }
        // Every question still asked the cap (the breaker never opened and hid it), and the model was never reached.
        assertThat(attempts.get()).isEqualTo(settings.breakerThreshold() + 4);
    }
}
