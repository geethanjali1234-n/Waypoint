package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.dto.myhr.MyHrChatResponse;
import com.waypoint.enums.UserRole;
import com.waypoint.security.TestActors;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline, atomic Mongo mock: verifies state encoding, owner isolation, CAS and service fail-closed behavior. */
class WaviMongoDialogueStoreTest {
    private static final Instant NOW = WaviEvaluationWorld.NOW;

    private static MyHrChatHistoryProvenance provenance(int seed) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) seed);
        return new MyHrChatHistoryProvenance(Duration.ofHours(3), Clock.fixed(NOW, ZoneOffset.UTC), key);
    }

    private static final class AtomicMongo {
        final MongoOperations mongo = mock(MongoOperations.class);
        final Map<String, Document> records = new ConcurrentHashMap<>();

        AtomicMongo() {
            when(mongo.findById(anyString(), eq(Document.class), eq(WaviMongoDialogueStore.COLLECTION)))
                    .thenAnswer(call -> {
                        synchronized (records) {
                            Document doc = records.get(call.getArgument(0));
                            return doc == null ? null : new Document(doc);
                        }
                    });
            when(mongo.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class),
                    eq(Document.class), eq(WaviMongoDialogueStore.COLLECTION))).thenAnswer(call -> {
                Document query = ((Query) call.getArgument(0)).getQueryObject();
                Document changes = ((Update) call.getArgument(1)).getUpdateObject().get("$set", Document.class);
                synchronized (records) {
                    Document doc = records.get(query.getString("_id"));
                    if (doc == null) return null;
                    if (query.containsKey("revision") && !query.get("revision").equals(doc.get("revision"))) return null;
                    Document expiry = query.get("expiresAt", Document.class);
                    if (expiry != null) {
                        Date date = doc.getDate("expiresAt");
                        if (expiry.containsKey("$gt") && !date.after(expiry.getDate("$gt"))) return null;
                        if (expiry.containsKey("$lte") && date.after(expiry.getDate("$lte"))) return null;
                    }
                    doc.putAll(changes);
                    return new Document(doc);
                }
            });
            when(mongo.insert(any(Document.class), eq(WaviMongoDialogueStore.COLLECTION))).thenAnswer(call -> {
                Document doc = call.getArgument(0);
                synchronized (records) {
                    if (records.containsKey(doc.getString("_id"))) throw new DuplicateKeyException("duplicate");
                    records.put(doc.getString("_id"), new Document(doc));
                    return doc;
                }
            });
        }
    }

    @Test
    void encryptedStateIsSharedAcrossInstancesButBoundToUserRoleTenantSessionAndTurn() {
        var backend = new AtomicMongo();
        var first = new WaviMongoDialogueStore(backend.mongo, provenance(3), Duration.ofHours(3));
        var second = new WaviMongoDialogueStore(backend.mongo, provenance(3), Duration.ofHours(3));
        var actor = TestActors.actor("user-a", "org-a", UserRole.HR);
        var session = new MyHrChatHistoryProvenance.Session("a".repeat(64));
        var state = new WaviDialogueStore.State("turn-one", WaviTools.GET_MY_REQUESTS,
                Map.of("kind", "PROFILE_CHANGE"), NOW,
                new WaviClarification("private-question-marker", "timesheets", true, false, true), null, null,
                List.of(new WaviDialogueStore.CitationRef(1, "private-source-marker", "private-version-marker")));
        assertThat(first.recordIfUnchanged(actor, session, state, 0, NOW)).isTrue();
        assertThat(backend.records.values().toString()).doesNotContain("private-question-marker", "private-source-marker",
                "private-version-marker", "PROFILE_CHANGE", "user-a");
        assertThat(second.find(actor, session, "turn-one", NOW)).contains(state);
        assertThat(second.find(actor, session, "wrong-turn", NOW)).isEmpty();
        assertThat(second.find(TestActors.actor("user-b", "org-a", UserRole.HR), session, "turn-one", NOW)).isEmpty();
        assertThat(second.find(TestActors.actor("user-a", "org-b", UserRole.HR), session, "turn-one", NOW)).isEmpty();
        assertThat(second.find(TestActors.actor("user-a", "org-a", UserRole.EMPLOYEE), session, "turn-one", NOW)).isEmpty();
        assertThat(second.find(actor, new MyHrChatHistoryProvenance.Session("b".repeat(64)), "turn-one", NOW)).isEmpty();
        assertThat(second.find(actor, session, "turn-one", NOW.plus(Duration.ofHours(4)))).isEmpty();
        assertThat(provenance(3).personReference("user-a")).isEqualTo(provenance(3).personReference("user-a"))
                .isNotEqualTo(provenance(4).personReference("user-a"));
    }

    @Test
    void concurrentInstancesUseAtomicCompareAndSetAndRejectStaleOrExpiredWriters() throws Exception {
        var backend = new AtomicMongo();
        var one = new WaviMongoDialogueStore(backend.mongo, provenance(3), Duration.ofHours(3));
        var two = new WaviMongoDialogueStore(backend.mongo, provenance(3), Duration.ofHours(3));
        var actor = TestActors.actor("user-a", "org-a", UserRole.EMPLOYEE);
        var session = new MyHrChatHistoryProvenance.Session("a".repeat(64));
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { start.await(); return one.recordIfUnchanged(actor, session,
                    new WaviDialogueStore.State("a", WaviTools.GET_MY_ROLE, Map.of(), NOW), 0, NOW); });
            var second = pool.submit(() -> { start.await(); return two.recordIfUnchanged(actor, session,
                    new WaviDialogueStore.State("b", WaviTools.GET_MY_ROLE, Map.of(), NOW), 0, NOW); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            var current = one.snapshot(actor, session, null, NOW);
            var newState = new WaviDialogueStore.State("next", WaviTools.GET_MY_ROLE, Map.of(), NOW);
            assertThat(two.recordIfUnchanged(actor, session, newState, current.revision(), NOW)).isTrue();
            assertThat(one.recordIfUnchanged(actor, session, newState, current.revision(), NOW)).isFalse();
            var next = one.snapshot(actor, session, "next", NOW);
            Instant later = NOW.plus(Duration.ofHours(4));
            assertThat(two.recordIfUnchanged(actor, session, newState, next.revision(), later)).isFalse();
            assertThat(two.recordIfUnchanged(actor, session, newState, 0, later)).isTrue();
        } finally {
            start.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void tamperingOrWrongKeyFailsClosedAndSharedModeRequiresAConfiguredKey() {
        var backend = new AtomicMongo();
        var actor = TestActors.actor("user-a", "org-a", UserRole.EMPLOYEE);
        var session = new MyHrChatHistoryProvenance.Session("a".repeat(64));
        assertThatThrownBy(() -> new WaviMongoDialogueStore(backend.mongo,
                new MyHrChatHistoryProvenance(Duration.ofHours(3), Clock.fixed(NOW, ZoneOffset.UTC)), Duration.ofHours(3)))
                .isInstanceOf(IllegalStateException.class);
        var one = new WaviMongoDialogueStore(backend.mongo, provenance(3), Duration.ofHours(3));
        assertThat(one.recordIfUnchanged(actor, session,
                new WaviDialogueStore.State("turn", WaviTools.GET_MY_ROLE, Map.of(), NOW), 0, NOW)).isTrue();
        var wrong = new WaviMongoDialogueStore(backend.mongo, provenance(4), Duration.ofHours(3));
        assertThatThrownBy(() -> wrong.find(actor, session, "turn", NOW)).isInstanceOf(DataAccessResourceFailureException.class);
        Document doc = backend.records.values().iterator().next();
        byte[] bytes = ((byte[]) doc.get("payload")).clone();
        bytes[bytes.length - 1] ^= 1;
        doc.put("payload", bytes);
        assertThatThrownBy(() -> one.find(actor, session, "turn", NOW)).isInstanceOf(DataAccessResourceFailureException.class);
    }

    @Test
    void enablingSharedModeCreatesNoIndexByItself() {
        var mongo = mock(MongoOperations.class);
        var indexes = mock(org.springframework.data.mongodb.core.index.IndexOperations.class);
        when(mongo.indexOps(WaviMongoDialogueStore.COLLECTION)).thenReturn(indexes);
        new WaviMongoDialogueStore(mongo, provenance(3));
        // No index is created by the application: the TTL index is part of the separately approved setup step.
        org.mockito.Mockito.verifyNoInteractions(indexes);
    }

    @Test
    void databaseFailureReturnsAClosedAnswerWithoutCallingBusinessServices() {
        var backend = new AtomicMongo();
        when(backend.mongo.findById(anyString(), eq(Document.class), eq(WaviMongoDialogueStore.COLLECTION)))
                .thenThrow(new DataAccessResourceFailureException("offline"));
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        world.service.configureSharedDialogueStore(new WaviMongoDialogueStore(backend.mongo, provenance(3), Duration.ofHours(3)));
        MyHrChatResponse response = world.service.chat(world.actor, "What is my employee ID?", List.of(), StampedHistory.SESSION);
        assertThat(response.message()).isEqualTo(MyHrChatService.DATA_UNAVAILABLE_REPLY);
        verify(world.employees, never()).getOwnDetail(anyString());
        verify(world.claude, never()).chat(anyString(), anyList());
    }

    @Test
    void stampedConversationAndPinnedPersonContinueAcrossTwoServiceInstances() {
        var backend = new AtomicMongo();
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.HR, new WaviEvaluationWorld.Options());
        var first = service(world, backend, provenance(7));
        var second = service(world, backend, provenance(7));
        String question = "what is Ravi Teja's job title?";
        MyHrChatResponse answer = first.chat(world.actor, question, List.of(), StampedHistory.SESSION);
        assertThat(answer.message()).contains("Ravi Teja", "QA Engineer");
        var history = List.of(new MyHrChatHistoryMessage("user", question, answer.historyProvenance().user()),
                new MyHrChatHistoryMessage("assistant", answer.message(), answer.historyProvenance().assistant()));

        clearInvocations(backend.mongo);
        MyHrChatResponse followUp = second.chat(world.actor, "Is there pending work for this employee?", history,
                StampedHistory.SESSION);
        assertThat(followUp.message()).contains("Ravi Teja").doesNotContain("Work waiting for your review from Asha Rao");
        verify(backend.mongo, times(1)).findById(anyString(), eq(Document.class), eq(WaviMongoDialogueStore.COLLECTION));

        var demoted = TestActors.actor(world.actor.userId(), world.actor.organizationId(), UserRole.EMPLOYEE);
        MyHrChatResponse replay = second.chat(demoted, "Is there pending work for this employee?", history,
                StampedHistory.SESSION);
        assertThat(replay.message()).doesNotContain("Ravi Teja", "QA Engineer");
    }

    @Test
    void pendingClarificationCanBeCompletedOnAnotherServiceInstance() {
        var backend = new AtomicMongo();
        var world = new WaviEvaluationWorld(WaviEvaluationWorld.EMPLOYEE, new WaviEvaluationWorld.Options());
        var first = service(world, backend, provenance(7));
        var second = service(world, backend, provenance(7));
        String question = "what's the status?";
        MyHrChatResponse clarification = first.chat(world.actor, question, List.of(), StampedHistory.SESSION);
        assertThat(clarification.message()).containsIgnoringCase("which");
        var history = List.of(new MyHrChatHistoryMessage("user", question, clarification.historyProvenance().user()),
                new MyHrChatHistoryMessage("assistant", clarification.message(), clarification.historyProvenance().assistant()));
        MyHrChatResponse answer = second.chat(world.actor, "my offer letter", history, StampedHistory.SESSION);
        assertThat(answer.message()).containsIgnoringCase("pending");
    }

    private static MyHrChatService service(WaviEvaluationWorld world, AtomicMongo backend,
                                           MyHrChatHistoryProvenance provenance) {
        var service = new MyHrChatService(world.claude, world.ollama, world.timesheets, world.profileChanges,
                world.employees, world.documents, "claude", world.retrieval, true, false, false,
                ExternalPolicyProcessingAuthorization.DENY_ALL, provenance);
        service.useClock(Clock.fixed(NOW, ZoneOffset.UTC));
        service.configureSharedDialogueStore(new WaviMongoDialogueStore(backend.mongo, provenance, Duration.ofHours(3)));
        return service;
    }
}
