package com.waypoint.service;

import com.waypoint.security.VerifiedMyHrActor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What Wavi last answered in a conversation, so "and November?" can be understood: the tool and its validated
 * arguments, plus a bounded server-only pending question while clarifying. No reply or record value.
 * Pending question text is never included in the router prompt or returned to the browser.
 *
 * <p>Held on the server only, never sent to or accepted from the browser, so a client cannot supply or alter it.
 * It is keyed by the verified actor (user, organization, role) and login session. It is used only when the
 * conversation's latest history turn carries this server's verified stamp for the turn the state was recorded
 * in. A new, cleared or edited conversation, another login, or another user therefore never inherits it.
 * This bounded store is process-local: deployments with multiple instances require session affinity or a shared
 * expiring implementation. Losing the instance loses conversational references, never authorization; clarification
 * is required rather than accepting record identity from unverified browser history.
 */
class WaviDialogueStore {

    /** A source displayed in the prior answer. The document is reauthorized and retrieved again before use. */
    record CitationRef(int number, String documentId, String ingestionId) { }

    /** The last answered request. {@code arguments} passed the tool's schema: enums, ISO dates, small integers. */
    record State(String turnId, String tool, Map<String, Object> arguments, Instant recordedAt,
                 WaviClarification pending, java.time.LocalDate referenceDate, WaviQuestionUnderstanding understanding,
                 java.util.List<CitationRef> citations) {
        State(String turnId, String tool, Map<String, Object> arguments, Instant recordedAt,
              WaviClarification pending, java.time.LocalDate referenceDate, WaviQuestionUnderstanding understanding) {
            this(turnId, tool, arguments, recordedAt, pending, referenceDate, understanding, null);
        }
        State(String turnId, String tool, Map<String, Object> arguments, Instant recordedAt,
              WaviClarification pending, java.time.LocalDate referenceDate) {
            this(turnId, tool, arguments, recordedAt, pending, referenceDate, null);
        }
        State(String turnId, String tool, Map<String, Object> arguments, Instant recordedAt, WaviClarification pending) {
            this(turnId, tool, arguments, recordedAt, pending, null, null);
        }
        State(String turnId, String tool, Map<String, Object> arguments, Instant recordedAt) {
            this(turnId, tool, arguments, recordedAt, null, null, null);
        }
        State {
            arguments = arguments == null ? Map.of() : Map.copyOf(arguments);
            understanding = understanding == null ? WaviQuestionUnderstanding.from(tool, arguments, referenceDate) : understanding;
            citations = citations == null ? java.util.List.of() : java.util.List.copyOf(citations);
        }
    }

    static final int MAX_CONVERSATIONS = 10_000;
    /** A verified turn with no continuation context still advances the conversation head. */
    static final String NO_STATE = "__no_dialogue_state__";

    /** Revision is used only for an atomic write check; only matching, verified turns expose their state. */
    record Snapshot(long revision, Optional<State> previous) { }
    private record Entry(long revision, State state) { }

    private final Duration lifetime;
    private long nextRevision;
    private final Map<String, Entry> states = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_CONVERSATIONS;
        }
    };

    WaviDialogueStore(Duration lifetime) {
        this.lifetime = lifetime;
    }

    synchronized void record(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session, State state) {
        if (actor == null || session == null || state == null || state.turnId() == null || state.tool() == null) return;
        states.put(key(actor, session), new Entry(++nextRevision, state));
    }

    /** Save only if the state observed before the request has not been replaced by another request. */
    synchronized boolean recordIfUnchanged(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session,
                                           State state, long expectedRevision, Instant now) {
        if (actor == null || session == null || state == null || state.turnId() == null || state.tool() == null
                || expectedRevision < 0) return false;
        String key = key(actor, session);
        Entry current = current(key, now);
        long actualRevision = current == null ? 0 : current.revision();
        if (actualRevision != expectedRevision) return false;
        states.put(key, new Entry(++nextRevision, state));
        return true;
    }

    synchronized Snapshot snapshot(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session,
                                   String latestVerifiedTurnId, Instant now) {
        if (actor == null || session == null) return new Snapshot(-1, Optional.empty());
        Entry current = current(key(actor, session), now);
        if (current == null) return new Snapshot(0, Optional.empty());
        State state = current.state();
        if (latestVerifiedTurnId != null && !state.turnId().equals(latestVerifiedTurnId)) {
            return new Snapshot(-1, Optional.empty());
        }
        return new Snapshot(current.revision(), latestVerifiedTurnId != null && !NO_STATE.equals(state.tool())
                ? Optional.of(state) : Optional.empty());
    }

    private Entry current(String key, Instant now) {
        Entry current = states.get(key);
        if (current != null && now != null && current.state().recordedAt() != null
                && current.state().recordedAt().plus(lifetime).isBefore(now)) {
            states.remove(key);
            return null;
        }
        return current;
    }

    /**
     * The state for this actor and session, only when {@code latestVerifiedTurnId} (the stamp-verified turn the
     * conversation's history ends with) is the turn it was recorded in, and it has not expired.
     */
    synchronized Optional<State> find(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session,
                                      String latestVerifiedTurnId, Instant now) {
        return snapshot(actor, session, latestVerifiedTurnId, now).previous();
    }

    synchronized int size() {
        return states.size();
    }

    /** A digest, so the map holds no user id or session fingerprint in the clear. */
    static String key(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session) {
        String raw = actor.userId() + "\u0000" + actor.organizationId() + "\u0000" + actor.role() + "\u0000"
                + session.fingerprint();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
