package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/**
 * Concurrent conversations on ONE shared chat service (as one backend instance serves many users): every follow-up
 * must resolve from its own conversation's state, never another's, and a session's stamped history replayed under a
 * different login session must not inherit that state. Synthetic world; no provider is called.
 */
class WaviConcurrencyTest {

    private static final int CONVERSATIONS = 64;
    private static final int THREADS = 16;

    @Test
    void concurrentConversationsKeepTheirOwnDialogueState() throws Exception {
        var options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        LocalDate first = LocalDate.parse("2026-09-01");
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> outcomes = new ArrayList<>();
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        try {
            for (int index = 0; index < CONVERSATIONS; index++) {
                LocalDate day = first.plusDays(index);
                var session = MyHrChatHistoryProvenance.Session.fromAuthorizationHeader("Bearer concurrent-" + index);
                Callable<String> conversation = () -> {
                    start.await();
                    List<MyHrChatHistoryMessage> history = new ArrayList<>();
                    timedAsk(world, session, "Did I submit my timesheet for " + day + "?", history, latencies);
                    String scope = timedAsk(world, session, "Which date did that cover?", history, latencies);
                    return day + "|" + scope;
                };
                outcomes.add(pool.submit(conversation));
            }
            long started = System.nanoTime();
            start.countDown();
            for (Future<String> outcome : outcomes) {
                String[] parts = outcome.get(60, TimeUnit.SECONDS).split("\\|", 2);
                assertThat(parts[1]).as("conversation for " + parts[0]).isEqualTo("The selected date is " + parts[0] + ".");
            }
            long elapsed = System.nanoTime() - started;
            assertThat(latencies).hasSize(CONVERSATIONS * 2);
            List<Long> sorted = latencies.stream().sorted().toList();
            double seconds = elapsed / 1_000_000_000.0;
            String report = String.format(Locale.ROOT,
                    "Synthetic in-memory Wavi test; mocks only, no network.\nConversations: %d; turns: %d; worker threads: %d.\n"
                            + "Wall seconds: %.3f; completed turns/second: %.1f; per-turn p50/p95 ms: %.3f/%.3f.\n"
                            + "This is a local concurrency check, not a capacity or production latency benchmark.\n",
                    CONVERSATIONS, sorted.size(), THREADS, seconds, sorted.size() / seconds,
                    sorted.get(sorted.size() / 2) / 1_000_000.0,
                    sorted.get((int) Math.ceil(sorted.size() * 0.95) - 1) / 1_000_000.0);
            Files.createDirectories(Path.of("target/wavi-eval"));
            Files.writeString(Path.of("target/wavi-eval/local-concurrency-summary.txt"), report);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anotherSessionReplayingStampedHistoryDoesNotInheritItsState() {
        var options = new WaviEvaluationWorld.Options();
        options.semanticRouter = WaviEvaluationWorld.RouterBehaviour.UNAVAILABLE;
        WaviEvaluationWorld world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, options);
        var owner = MyHrChatHistoryProvenance.Session.fromAuthorizationHeader("Bearer owner");
        var other = MyHrChatHistoryProvenance.Session.fromAuthorizationHeader("Bearer other");
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        ask(world, owner, "Did I submit my timesheet for 2026-10-14?", history);
        String replayed = world.service.chat(world.actor, "Which date did that cover?", List.copyOf(history), other)
                .message();
        assertThat(replayed).doesNotContain("2026-10-14");
    }

    @Test
    void aSlowTurnCannotReplaceACompletedConcurrentTurnInTheSameSession() throws Exception {
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        var session = MyHrChatHistoryProvenance.Session.fromAuthorizationHeader("Bearer same-session");
        var detail = world.employees.getOwnDetail(world.actor.userId());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("profile lookup timed out");
            return detail;
        }).when(world.employees).getOwnDetail(world.actor.userId());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var slower = executor.submit(() -> world.service.chat(world.actor, "what's my employee id?", List.of(), session));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            MyHrChatResponse earlier = world.service.chat(world.actor, "what is my timesheet status for this week?",
                    List.of(), session);
            release.countDown();
            MyHrChatResponse later = slower.get(10, TimeUnit.SECONDS);
            String earlierId = earlier.historyProvenance().assistant().split("\\.")[2];
            String laterId = later.historyProvenance().assistant().split("\\.")[2];
            assertThat(world.service.dialogueStore().find(world.actor, session, earlierId, WaviEvaluationWorld.NOW))
                    .isPresent().get().extracting(WaviDialogueStore.State::tool).isEqualTo(WaviTools.GET_MY_TIMESHEETS);
            assertThat(world.service.dialogueStore().find(world.actor, session, laterId, WaviEvaluationWorld.NOW)).isEmpty();
            assertThat(later.message()).contains("WPT-1001");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void verifiedOlderHistoryCannotReplaceTheHeadAndAContextFreeTurnCanBeFollowedNormally() {
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        var session = MyHrChatHistoryProvenance.Session.fromAuthorizationHeader("Bearer same-session");
        List<MyHrChatHistoryMessage> history = new ArrayList<>();
        ask(world, session, "hello", history);
        List<MyHrChatHistoryMessage> oldHistory = List.copyOf(history);
        assertThat(ask(world, session, "what is my employee id?", history)).contains("WPT-1001");
        String headId = history.getLast().provenance().split("\\.")[2];
        assertThat(world.service.dialogueStore().find(world.actor, session, headId, WaviEvaluationWorld.NOW))
                .isPresent().get().extracting(WaviDialogueStore.State::tool).isEqualTo(WaviTools.GET_MY_PROFILE_FIELD);

        world.service.chat(world.actor, "what is my timesheet status this week?", oldHistory, session);

        assertThat(world.service.dialogueStore().find(world.actor, session, headId, WaviEvaluationWorld.NOW))
                .isPresent().get().extracting(WaviDialogueStore.State::tool).isEqualTo(WaviTools.GET_MY_PROFILE_FIELD);
    }

    private static String ask(WaviEvaluationWorld world, MyHrChatHistoryProvenance.Session session, String question,
                              List<MyHrChatHistoryMessage> history) {
        MyHrChatResponse response = world.service.chat(world.actor, question, List.copyOf(history), session);
        history.add(new MyHrChatHistoryMessage("user", question, response.historyProvenance().user()));
        history.add(new MyHrChatHistoryMessage("assistant", response.message(), response.historyProvenance().assistant()));
        return response.message();
    }

    private static String timedAsk(WaviEvaluationWorld world, MyHrChatHistoryProvenance.Session session,
                                   String question, List<MyHrChatHistoryMessage> history, List<Long> latencies) {
        long started = System.nanoTime();
        try {
            return ask(world, session, question, history);
        } finally {
            latencies.add(System.nanoTime() - started);
        }
    }
}
