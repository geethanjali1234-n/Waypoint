package com.waypoint.service;

import com.sun.net.httpserver.HttpServer;
import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * OFFLINE and free. Exercises the existing shared ledger and estimates across the chatbot's request paths using
 * synthetic rates and usage against a local stub. These fixtures do not prove a hard cap for real provider charges.
 */
class ClaudeSpendLimiterAllPathsTest {

    private static final String KEY = "sk-ant-test-key-0000";
    private static final String HAIKU = "claude-haiku-5-5";

    private HttpServer server;
    private MockedStatic<ClaudeSpendLimiter> reservation;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile boolean withUsage = true;
    private volatile int lastInputTokens;

    private static MockedStatic<ClaudeSpendLimiter> mockedReservationForLocalStub() {
        var mocked = Mockito.mockStatic(ClaudeSpendLimiter.class);
        mocked.when(() -> ClaudeSpendLimiter.fromSettings(Mockito.anyDouble(), Mockito.any())).thenCallRealMethod();
        mocked.when(() -> ClaudeSpendLimiter.closed(Mockito.anyDouble())).thenCallRealMethod();
        return mocked;
    }

    @BeforeEach
    void enableSyntheticAccountingTests() {
        // All clients in this test class use only the loopback stub created below.
        reservation = mockedReservationForLocalStub();
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
        reservation.close();
    }

    private String stub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            int requestChars = exchange.getRequestBody().readAllBytes().length;
            lastInputTokens = requestChars / 3 + 1; // a real tokenizer yields fewer than one token per two characters
            String usage = withUsage ? ",\"usage\":{\"input_tokens\":" + lastInputTokens + ",\"output_tokens\":60}" : "";
            byte[] reply = (status == 200
                    ? "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"ok\\\":true}\"}],\"stop_reason\":\"end_turn\"" + usage + "}"
                    : "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"x\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages";
    }

    private ClaudeChatClient client(String url, String model, int maxTokens, ClaudeSpendLimiter limiter) {
        if (!url.equals("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages")) {
            throw new IllegalArgumentException("Only the test's loopback stub may receive requests");
        }
        ClaudeChatClient client = new ClaudeChatClient(RestClient.builder().build(), KEY, url, model, maxTokens);
        client.useAgentModel(HAIKU, Duration.ofSeconds(5));
        client.useSpendLimiter(limiter);
        return client;
    }

    /** The five paid paths, each as a callable. */
    private static List<Consumer<ClaudeChatClient>> paths() {
        return List.of(
                c -> c.converse("system", List.of(Map.of("role", "user", "content", "hello")), List.of(), true, 256),
                c -> c.chat("hello", List.of(new MyHrChatHistoryMessage("user", "hi"))),
                c -> c.chat(new MyHrGroundedPrompt("Grounded system prompt.", List.of(), "question", false)),
                c -> c.chatWithDocuments(new MyHrGroundedDocumentPrompt("Cited rules.", List.of(),
                        List.of(new MyHrGroundedDocumentPrompt.Document("Synthetic Leave Policy", null, "Synthetic leave text.")),
                        "How many days?")),
                c -> c.routeJson("Router rules", "question", Map.of("type", "object"), 128, "low"));
    }

    private static final List<String> NAMES = List.of("converse", "chat", "chat(grounded)", "chatWithDocuments", "routeJson");

    private static Path ledger(Path dir) throws IOException {
        Path file = dir.resolve("ledger.txt");
        Files.writeString(file, "0.00000000");
        return file;
    }

    private static double total(Path file) throws IOException {
        return Double.parseDouble(Files.readString(file).strip());
    }

    // --- One ledger for every path ---------------------------------------------------------------------------------------------

    @Test
    void everyPaidPathDebitsTheSameLedgerWithItsRealCost(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        ClaudeChatClient client = client(stub(), HAIKU, 1024, new ClaudeSpendLimiter(0.05, file, null));
        double previous = 0;
        for (int i = 0; i < paths().size(); i++) {
            paths().get(i).accept(client);
            double now = total(file);
            // settled from the response's usage: the reported input tokens at $0.10/M plus 60 output tokens at $0.50/M
            assertThat(now - previous).as(NAMES.get(i))
                    .isEqualTo((lastInputTokens * 0.10 + 60 * 0.50) / 1_000_000d, org.assertj.core.data.Offset.offset(1e-9));
            previous = now;
        }
        assertThat(hits.get()).isEqualTo(5);
    }

    @Test
    void oneCapCoversAMixOfAllPathsAndARefusedCallNeverReachesTheServer(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        ClaudeChatClient client = client(stub(), HAIKU, 1024, new ClaudeSpendLimiter(0.0015, file, null));
        int sent = 0;
        boolean refused = false;
        for (int round = 0; round < 100 && !refused; round++) {
            for (Consumer<ClaudeChatClient> path : paths()) {
                try {
                    path.accept(client);
                    sent++;
                } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
                    refused = true;
                    break;
                }
            }
        }
        assertThat(refused).isTrue();
        assertThat(sent).isGreaterThanOrEqualTo(2);
        assertThat(hits.get()).isEqualTo(sent);
        assertThat(total(file)).isLessThanOrEqualTo(0.0015);
        // Anything that would pass the cap is refused, whichever path asks; what still fits never goes beyond it.
        for (int i = 0; i < 200; i++) {
            try {
                paths().get(i % paths().size()).accept(client);
            } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
                // refused before sending
            }
        }
        assertThat(total(file)).isLessThanOrEqualTo(0.0015);
        assertThat(hits.get()).isGreaterThanOrEqualTo(sent);
    }

    @Test
    void aBackendRestartKeepsEveryPathClosedAgainstTheSameLedger(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        ClaudeChatClient first = client(url, HAIKU, 1024, new ClaudeSpendLimiter(0.0008, file, null));
        try {
            while (true) paths().get(1).accept(first);
        } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
            // the first process used the allowance
        }
        int used = hits.get();
        assertThat(total(file)).isGreaterThan(0).isLessThanOrEqualTo(0.0008);
        Files.writeString(file, "0.00080000"); // what is left cannot fit any path's worst case
        ClaudeChatClient restarted = client(url, HAIKU, 1024, new ClaudeSpendLimiter(0.0008, file, null));
        for (int i = 0; i < paths().size(); i++) {
            Consumer<ClaudeChatClient> path = paths().get(i);
            assertThatThrownBy(() -> path.accept(restarted)).as(NAMES.get(i))
                    .isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        }
        assertThat(hits.get()).isEqualTo(used);
    }

    @Test
    void theSpringConfigurationBuildsTheSameSharedLimiterForAllPaths(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        ClaudeChatClient client = new ClaudeChatClient(RestClient.builder().build(), KEY, stub(), HAIKU, 1024);
        client.configureSpend(0.001, file.toString());
        client.useAgentModel(HAIKU, Duration.ofSeconds(5));
        int sent = 0;
        try {
            for (int i = 0; i < 100; i++) {
                paths().get(i % paths().size()).accept(client);
                sent++;
            }
        } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
            // reached
        }
        assertThat(sent).isLessThan(100);
        assertThat(total(file)).isLessThanOrEqualTo(0.001);
    }

    // --- Concurrency ----------------------------------------------------------------------------------------------------------

    @Test
    void concurrentCallsOnDifferentPathsCannotTogetherExceedTheCap(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        ClaudeChatClient client = client(stub(), HAIKU, 1024, new ClaudeSpendLimiter(0.004, file, null));
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int offset = t;
            futures.add(pool.submit(() -> {
              try (var ignored = mockedReservationForLocalStub()) {
                go.await();
                for (int n = 0; n < 10; n++) {
                    try {
                        paths().get((offset + n) % paths().size()).accept(client);
                        succeeded.incrementAndGet();
                    } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
                        // expected once the allowance is gone
                    }
                }
                return null;
              }
            }));
        }
        go.countDown();
        for (Future<?> future : futures) future.get();
        pool.shutdown();
        assertThat(succeeded.get()).isLessThan(threads * 10).isGreaterThan(0);
        assertThat(hits.get()).isEqualTo(succeeded.get()); // no call was sent without a reservation
        assertThat(total(file)).isLessThanOrEqualTo(0.004);
    }

    // --- Fail closed ----------------------------------------------------------------------------------------------------------

    @Test
    void anUnfamiliarOrDearModelIsPricedConservativelyAndANormalChatCallWouldNotFitASmallCap(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        // The default chat model and its default 4096-token allowance: its worst case alone is above a $0.05 cap.
        ClaudeChatClient client = client(stub(), "claude-sonnet-5", 4096, new ClaudeSpendLimiter(0.05, file, null));
        assertThatThrownBy(() -> client.chat("hello", List.of())).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        assertThat(hits.get()).isZero();
        assertThat(total(file)).isZero();
        // Within the cap a 512-token allowance does fit, and is settled at the conservative rates.
        ClaudeChatClient small = client(stub(), "claude-sonnet-5", 128, new ClaudeSpendLimiter(0.05, file, null));
        small.chat("hello", List.of());
        assertThat(total(file)).isEqualTo((lastInputTokens * 15.0 + 60 * 75.0) / 1_000_000d, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void aResponseWithoutUsageKeepsTheWorstCaseReservation(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        withUsage = false;
        ClaudeChatClient client = client(stub(), HAIKU, 1024, new ClaudeSpendLimiter(0.05, file, null));
        client.chat("hello", List.of());
        assertThat(total(file)).isGreaterThan(1024 * 0.50 / 1_000_000d * 0.99); // at least the full output allowance
    }

    @Test
    void aRejectedRequestGivesItsReservationBackButAServerErrorKeepsIt(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        ClaudeChatClient client = client(stub(), HAIKU, 1024, new ClaudeSpendLimiter(0.05, file, null));
        status = 400;
        assertThatThrownBy(() -> client.chat("hello", List.of())).isInstanceOf(ClaudeChatClient.ClaudeRequestRejectedException.class);
        assertThat(total(file)).isZero();
        status = 500;
        assertThatThrownBy(() -> client.chat("hello", List.of())).isInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);
        assertThat(total(file)).isGreaterThan(0); // a failure after sending is counted at the worst case
    }

    @Test
    void aMissingOrCorruptLedgerRefusesEveryPathBeforeAnythingIsSent(@TempDir Path dir) throws Exception {
        String url = stub();
        for (String content : new String[]{null, "garbage", "-3"}) {
            Path file = dir.resolve("l-" + (content == null ? "missing" : content.length()) + ".txt");
            if (content != null) Files.writeString(file, content);
            ClaudeChatClient client = client(url, HAIKU, 1024, new ClaudeSpendLimiter(0.05, file, null));
            for (Consumer<ClaudeChatClient> path : paths()) {
                assertThatThrownBy(() -> path.accept(client)).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
            }
        }
        assertThat(hits.get()).isZero();
    }

    @Test
    void withNoCapConfiguredNothingChangesAndNoLedgerIsNeeded() throws Exception {
        ClaudeChatClient client = new ClaudeChatClient(RestClient.builder().build(), KEY, stub(), HAIKU, 1024);
        client.configureSpend(0, "");
        client.useAgentModel(HAIKU, Duration.ofSeconds(5));
        for (Consumer<ClaudeChatClient> path : paths()) path.accept(client);
        assertThat(hits.get()).isEqualTo(5);
    }

    // --- Claude as the primary provider does not bypass the cap, and a refusal is not an outage --------------------------------

    @Test
    void aCapRefusalIsNotAnOutageSoItNeverFallsBackToOllamaOrTripsTheFallbackPath() {
        assertThat(new ClaudeChatClient.ClaudeSpendLimitException()).isNotInstanceOf(ClaudeChatClient.ClaudeUnavailableException.class);

        ClaudeChatClient claude = mock(ClaudeChatClient.class);
        OllamaClient ollama = mock(OllamaClient.class);
        when(ollama.isLocalEndpoint()).thenReturn(true);
        org.mockito.Mockito.lenient().when(ollama.verifiedLocalModel()).thenReturn(true);
        when(claude.chat(anyString(), anyList())).thenThrow(new ClaudeChatClient.ClaudeSpendLimitException());
        MyHrChatService service = new MyHrChatService(claude, ollama, mock(TimesheetService.class),
                mock(ProfileChangeRequestService.class), mock(EmployeeManagementService.class),
                mock(DocumentRequestService.class), "claude", mock(KnowledgeRetrievalService.class), false, false);
        var actor = com.waypoint.security.TestActors.actor("employee-9", "org-9", com.waypoint.enums.UserRole.EMPLOYEE);
        assertThatThrownBy(() -> service.chat(actor, "Write a polite email to my team", List.of()))
                .isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
        verifyNoInteractions(ollama);
    }

    @Test
    void theUserSeesAnHonestUsageLimitMessage() {
        var response = new GlobalExceptionHandler().handleClaudeSpendLimit(new ClaudeChatClient.ClaudeSpendLimitException());
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().getMessage()).contains("usage limit");
    }
}
