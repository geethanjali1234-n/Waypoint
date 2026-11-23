package com.waypoint.service;

import com.sun.net.httpserver.HttpServer;
import com.waypoint.dto.myhr.ParsedTimesheetResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
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
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OFFLINE and free. Timesheet document extraction (vision) is a paid Claude path too: it draws on the SAME cumulative
 * ledger as Wavy chat, the agent, routing and cited policy answers, accounts for requests still in flight, prices by
 * the model that answers, and fails closed. A local stub server stands in for the API; no real service is called.
 */
class ClaudeSpendLimiterExtractionTest {

    private static final String KEY = "sk-ant-test-key-0000";
    private static final String HAIKU = "claude-haiku-5-5";
    private static final byte[] PDF = new byte[20_000];

    private HttpServer server;
    private final AtomicInteger hits = new AtomicInteger();
    private volatile int status = 200;
    private volatile boolean withUsage = true;
    private volatile boolean slow;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private String stub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/messages", exchange -> {
            hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            if (slow) {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            String usage = withUsage ? ",\"usage\":{\"input_tokens\":2500,\"output_tokens\":120}" : "";
            byte[] reply = (status == 200
                    ? "{\"content\":[{\"type\":\"text\",\"text\":\"[{\\\"date\\\":\\\"2026-09-01\\\",\\\"hours\\\":8,\\\"description\\\":\\\"Work\\\"}]\"}],"
                            + "\"stop_reason\":\"end_turn\"" + usage + "}"
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

    private static ClaudeDocumentExtractionService extraction(String url, String model, ClaudeSpendLimiter limiter) {
        ClaudeDocumentExtractionService service = new ClaudeDocumentExtractionService(RestClient.builder(), KEY, url, model);
        service.shareSpendLimiter(limiter);
        return service;
    }

    private static ClaudeChatClient chat(String url, ClaudeSpendLimiter limiter) {
        ClaudeChatClient client = new ClaudeChatClient(RestClient.builder().build(), KEY, url, HAIKU, 1024);
        client.useAgentModel(HAIKU, Duration.ofSeconds(5));
        client.shareSpendLimiter(limiter);
        return client;
    }

    private static Optional<ParsedTimesheetResponse> extract(ClaudeDocumentExtractionService service) {
        return service.extract(PDF, "scan.pdf", "doc-1", "MDY");
    }

    private static Path ledger(Path dir) throws IOException {
        Path file = dir.resolve("ledger.txt");
        Files.writeString(file, "0.00000000");
        return file;
    }

    private static double total(Path file) throws IOException {
        return Double.parseDouble(Files.readString(file).strip());
    }

    @Test
    void extractionAndChatDrawOnTheSameLedger(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        ClaudeSpendLimiter shared = new ClaudeSpendLimiter(0.05, file, null);
        assertThat(extract(extraction(url, HAIKU, shared))).isPresent();
        double afterExtraction = total(file);
        // 2500 in at $0.10/M + 120 out at $0.50/M, settled from the response's usage at the answering model's price
        assertThat(afterExtraction).isEqualTo((2500 * 0.10 + 120 * 0.50) / 1_000_000d, org.assertj.core.data.Offset.offset(1e-9));
        chat(url, shared).chat("hello", List.of());
        assertThat(total(file)).isGreaterThan(afterExtraction);
        assertThat(shared.spent()).isEqualTo(total(file), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void oneCapAcrossChatAndExtractionAndARefusedExtractionIsNeverSent(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        ClaudeSpendLimiter shared = new ClaudeSpendLimiter(0.0012, file, null);
        ClaudeDocumentExtractionService extraction = extraction(url, HAIKU, shared);
        ClaudeChatClient chat = chat(url, shared);
        int sent = 0;
        boolean refused = false;
        for (int i = 0; i < 100 && !refused; i++) {
            try {
                if (i % 2 == 0) {
                    chat.chat("hello", List.of());
                    sent++;
                } else if (extract(extraction).isPresent()) {
                    sent++;
                } else {
                    refused = true; // extraction returns empty when the cap stops it
                }
            } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
                refused = true;
            }
        }
        assertThat(refused).isTrue();
        assertThat(hits.get()).isEqualTo(sent);
        assertThat(total(file)).isLessThanOrEqualTo(0.0012);
        int before = hits.get();
        assertThat(extract(extraction)).isEmpty();
        assertThat(hits.get()).isEqualTo(before);
    }

    @Test
    void aBackendRestartKeepsExtractionClosedAgainstTheSameLedger(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        ClaudeDocumentExtractionService first = extraction(url, HAIKU, new ClaudeSpendLimiter(0.0010, file, null));
        while (extract(first).isPresent()) { /* use the allowance */ }
        int used = hits.get();
        Files.writeString(file, "0.00100000");
        ClaudeDocumentExtractionService restarted = extraction(url, HAIKU, new ClaudeSpendLimiter(0.0010, file, null));
        assertThat(extract(restarted)).isEmpty();
        assertThat(hits.get()).isEqualTo(used);
    }

    @Test
    void aDearerModelIsPricedConservativelySoItIsRefusedWhereHaikuFits(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        // The default extraction model and its 4096-token allowance cannot fit a $0.05 cap even before any input.
        assertThat(extract(extraction(url, "claude-sonnet-5", new ClaudeSpendLimiter(0.05, file, null)))).isEmpty();
        assertThat(hits.get()).isZero();
        assertThat(total(file)).isZero();
        assertThat(extract(extraction(url, HAIKU, new ClaudeSpendLimiter(0.05, file, null)))).isPresent();
        assertThat(hits.get()).isEqualTo(1);
    }

    @Test
    void aMissingOrCorruptLedgerFailsClosedBeforeAnythingIsSent(@TempDir Path dir) throws Exception {
        String url = stub();
        Path corrupt = dir.resolve("corrupt.txt");
        Files.writeString(corrupt, "not-a-number");
        for (Path file : List.of(dir.resolve("absent.txt"), corrupt)) {
            assertThat(extract(extraction(url, HAIKU, new ClaudeSpendLimiter(0.05, file, null)))).isEmpty();
        }
        // a cap with no ledger configured at all
        assertThat(extract(extraction(url, HAIKU, ClaudeSpendLimiter.fromSettings(0.05, "")))).isEmpty();
        assertThat(hits.get()).isZero();
    }

    @Test
    void aResponseWithoutUsageKeepsTheReservationAndARejectedRequestReleasesIt(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        withUsage = false;
        extract(extraction(url, HAIKU, new ClaudeSpendLimiter(0.05, file, null)));
        double kept = total(file);
        assertThat(kept).isGreaterThan(4096 * 0.50 / 1_000_000d * 0.99); // at least the full output allowance
        withUsage = true;
        status = 400;
        extract(extraction(url, HAIKU, new ClaudeSpendLimiter(0.05, file, null)));
        assertThat(total(file)).isEqualTo(kept, org.assertj.core.data.Offset.offset(1e-9)); // reservation given back
    }

    @Test
    void requestsStillInFlightCountAgainstTheCapImmediately(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        slow = true;
        ClaudeSpendLimiter shared = new ClaudeSpendLimiter(0.0040, file, null);
        ClaudeDocumentExtractionService extraction = extraction(url, HAIKU, shared);
        ClaudeChatClient chat = chat(url, shared);
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            boolean useChat = t % 2 == 0;
            futures.add(pool.submit(() -> {
                go.await();
                try {
                    if (useChat) chat.chat("hello", List.of());
                    else if (extract(extraction).isEmpty()) return null;
                    succeeded.incrementAndGet();
                } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
                    // refused while others were still in flight
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> future : futures) future.get();
        pool.shutdown();
        assertThat(succeeded.get()).isGreaterThan(0).isLessThan(threads);
        assertThat(hits.get()).isEqualTo(succeeded.get());
        assertThat(total(file)).isLessThanOrEqualTo(0.0040);
    }

    // --- Production wiring: Spring hands the SAME limiter to every paid Claude path ---------------------------------------------

    @Test
    void springWiresOneSharedLimiterIntoTheChatClientAndTheExtractionService(@TempDir Path dir) throws Exception {
        Path file = ledger(dir);
        String url = stub();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of(
                    "waypoint.myhr.agent.spend-cap-usd=0.0008",
                    "waypoint.myhr.agent.spend-ledger-file=" + file,
                    "waypoint.myhr.ai.claude.api-key=" + KEY,
                    "waypoint.myhr.ai.claude.api-url=" + url,
                    "waypoint.myhr.ai.claude.model=" + HAIKU,
                    "waypoint.myhr.ai.claude.max-tokens=1024",
                    "waypoint.myhr.timesheet-document-ai.api-key=" + KEY,
                    "waypoint.myhr.timesheet-document-ai.api-url=" + url,
                    "waypoint.myhr.timesheet-document-ai.model=" + HAIKU).applyTo(context);
            context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()); // "60s" -> Duration, as in Boot
            context.registerBean(RestClient.Builder.class, () -> RestClient.builder());
            context.register(ClaudeSpendConfiguration.class, ClaudeChatClient.class, ClaudeDocumentExtractionService.class);
            context.refresh();

            ClaudeChatClient chat = context.getBean(ClaudeChatClient.class);
            ClaudeDocumentExtractionService extraction = context.getBean(ClaudeDocumentExtractionService.class);
            int sent = 0;
            boolean refused = false;
            for (int i = 0; i < 100 && !refused; i++) {
                try {
                    if (i % 2 == 0) {
                        chat.chat("hello", List.of());
                        sent++;
                    } else if (extract(extraction).isPresent()) {
                        sent++;
                    } else {
                        refused = true;
                    }
                } catch (ClaudeChatClient.ClaudeSpendLimitException capped) {
                    refused = true;
                }
            }
            assertThat(refused).isTrue();
            assertThat(hits.get()).isEqualTo(sent);
            assertThat(total(file)).isLessThanOrEqualTo(0.0008);
            // both paths now refuse: they share one cap
            assertThatThrownBy(() -> chat.chat("hello", List.of())).isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
            assertThat(extract(extraction)).isEmpty();
        }
    }

    @Test
    void springWithACapButNoLedgerRefusesEveryPaidPath() throws Exception {
        String url = stub();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            TestPropertyValues.of("waypoint.myhr.agent.spend-cap-usd=0.05",
                    "waypoint.myhr.ai.claude.api-key=" + KEY, "waypoint.myhr.ai.claude.api-url=" + url,
                    "waypoint.myhr.ai.claude.model=" + HAIKU,
                    "waypoint.myhr.timesheet-document-ai.api-key=" + KEY,
                    "waypoint.myhr.timesheet-document-ai.api-url=" + url,
                    "waypoint.myhr.timesheet-document-ai.model=" + HAIKU).applyTo(context);
            context.getBeanFactory().setConversionService(org.springframework.boot.convert.ApplicationConversionService.getSharedInstance()); // "60s" -> Duration, as in Boot
            context.registerBean(RestClient.Builder.class, () -> RestClient.builder());
            context.register(ClaudeSpendConfiguration.class, ClaudeChatClient.class, ClaudeDocumentExtractionService.class);
            context.refresh();
            assertThatThrownBy(() -> context.getBean(ClaudeChatClient.class).chat("hello", List.of()))
                    .isInstanceOf(ClaudeChatClient.ClaudeSpendLimitException.class);
            assertThat(extract(context.getBean(ClaudeDocumentExtractionService.class))).isEmpty();
            assertThat(hits.get()).isZero();
        }
    }

    @Test
    void theEnabledSwitchKeepsDocumentReadingOffEvenWithAKeyAndSendsNothing() throws Exception {
        String url = stub();
        ClaudeDocumentExtractionService service = extraction(url, HAIKU, ClaudeSpendLimiter.fromSettings(0, ""));
        service.enabled(false);
        assertThat(service.isConfigured()).isFalse();
        assertThat(extract(service)).isEmpty();
        assertThat(hits.get()).isZero();
    }

    @Test
    void springBindsTheEnabledSwitchFromConfigurationAndItDefaultsToOn() throws Exception {
        String url = stub();
        for (String flag : new String[]{"false", null}) {
            try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
                TestPropertyValues.of("waypoint.myhr.timesheet-document-ai.api-key=" + KEY,
                        "waypoint.myhr.timesheet-document-ai.api-url=" + url,
                        "waypoint.myhr.timesheet-document-ai.model=" + HAIKU).applyTo(context);
                if (flag != null) TestPropertyValues.of("waypoint.myhr.timesheet-document-ai.enabled=" + flag).applyTo(context);
                context.registerBean(RestClient.Builder.class, () -> RestClient.builder());
                context.register(ClaudeDocumentExtractionService.class);
                context.refresh();
                assertThat(context.getBean(ClaudeDocumentExtractionService.class).isConfigured()).isEqualTo(flag == null);
            }
        }
        assertThat(hits.get()).isZero();
    }

    @Test
    void withNoCapConfiguredExtractionBehavesAsBefore() throws Exception {
        String url = stub();
        assertThat(extract(extraction(url, "claude-sonnet-5", ClaudeSpendLimiter.fromSettings(0, "")))).isPresent();
        assertThat(hits.get()).isEqualTo(1);
    }
}
