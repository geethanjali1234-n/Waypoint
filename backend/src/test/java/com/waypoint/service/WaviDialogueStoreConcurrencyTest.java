package com.waypoint.service;

import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class WaviDialogueStoreConcurrencyTest {
    private static final Instant NOW = Instant.parse("2026-11-05T15:00:00Z");

    @Test
    void overlappingTurnsCannotOverwriteTheFirstCompletedState() throws Exception {
        var store = new WaviDialogueStore(Duration.ofHours(3));
        var actor = TestActors.actor("synthetic-1", "org-a", UserRole.EMPLOYEE);
        var session = new MyHrChatHistoryProvenance.Session("a".repeat(64));
        long revision = store.snapshot(actor, session, null, NOW).revision();
        int turns = 24;
        var executor = Executors.newFixedThreadPool(turns);
        var ready = new CountDownLatch(turns);
        var start = new CountDownLatch(1);
        var results = new ArrayList<java.util.concurrent.Future<String>>();
        try {
            for (int i = 0; i < turns; i++) {
                final String id = "turn-" + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    var state = new WaviDialogueStore.State(id, WaviTools.GET_MY_TIMESHEETS,
                            Map.of("period", "DAY", "date", "2026-10-15"), NOW);
                    return store.recordIfUnchanged(actor, session, state, revision, NOW) ? id : "";
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<String> winners = new ArrayList<>();
            for (var result : results) {
                String id = result.get(10, TimeUnit.SECONDS);
                if (!id.isEmpty()) winners.add(id);
            }
            assertThat(winners).hasSize(1);
            assertThat(store.find(actor, session, winners.getFirst(), NOW)).isPresent();
            assertThat(store.find(actor, session, "unverified-turn", NOW)).isEmpty();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void revisionsStayScopedAndExpiredOrStaleSnapshotsCannotOverwrite() {
        var store = new WaviDialogueStore(Duration.ofHours(3));
        var owner = TestActors.actor("synthetic-1", "org-a", UserRole.EMPLOYEE);
        var otherUser = TestActors.actor("synthetic-2", "org-a", UserRole.EMPLOYEE);
        var otherOrg = TestActors.actor("synthetic-1", "org-b", UserRole.EMPLOYEE);
        var otherRole = TestActors.actor("synthetic-1", "org-a", UserRole.HR);
        var session = new MyHrChatHistoryProvenance.Session("a".repeat(64));
        var otherSession = new MyHrChatHistoryProvenance.Session("b".repeat(64));
        var first = new WaviDialogueStore.State("first", WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "NAME"), NOW);
        var second = new WaviDialogueStore.State("second", WaviTools.GET_MY_PROFILE_FIELD, Map.of("field", "NAME"), NOW);

        assertThat(store.recordIfUnchanged(owner, session, first, 0, NOW)).isTrue();
        var snapshot = store.snapshot(owner, session, "first", NOW);
        assertThat(snapshot.previous()).contains(first);
        var stale = store.snapshot(owner, session, "forged", NOW);
        assertThat(stale.previous()).isEmpty();
        assertThat(stale.revision()).isNegative();
        assertThat(store.recordIfUnchanged(owner, session, second, stale.revision(), NOW)).isFalse();
        assertThat(store.recordIfUnchanged(owner, session, second, snapshot.revision(), NOW)).isTrue();
        assertThat(store.recordIfUnchanged(owner, session, first, snapshot.revision(), NOW)).isFalse();
        assertThat(store.find(owner, session, "second", NOW)).contains(second);
        assertThat(store.find(otherUser, session, "second", NOW)).isEmpty();
        assertThat(store.find(otherOrg, session, "second", NOW)).isEmpty();
        assertThat(store.find(otherRole, session, "second", NOW)).isEmpty();
        assertThat(store.find(owner, otherSession, "second", NOW)).isEmpty();
        var secondSnapshot = store.snapshot(owner, session, "second", NOW);
        assertThat(store.recordIfUnchanged(owner, session, first, secondSnapshot.revision(), NOW.plus(Duration.ofHours(4))))
                .isFalse();
        assertThat(store.find(owner, session, "second", NOW.plus(Duration.ofHours(4)))).isEmpty();
    }
}
