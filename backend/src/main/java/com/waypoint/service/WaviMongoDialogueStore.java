package com.waypoint.service;

import com.waypoint.security.VerifiedMyHrActor;
import org.bson.Document;
import org.bson.types.Binary;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.Optional;

/**
 * Optional shared dialogue state in the existing MongoDB. Only encrypted state and an opaque owner digest are stored.
 * Expiration is enforced on every read and write; the TTL index is solely for eventual cleanup. An atomic revision
 * check prevents an earlier request from replacing a state written by a concurrent request on another instance.
 * A configured shared provenance key is required across all instances; without it this bean refuses to start.
 */
@Component
@ConditionalOnProperty(name = "waypoint.myhr.chat.shared-dialogue.enabled", havingValue = "true")
final class WaviMongoDialogueStore extends WaviDialogueStore {
    static final String COLLECTION = "wavi_dialogue_state";
    private static final int MAX_CIPHERTEXT_BYTES = 16_384;
    private static final int NONCE_BYTES = 12;
    private static final ObjectMapper JSON = new ObjectMapper();

    private record Pending(String question, String subject, boolean submission, boolean date, boolean own) { }
    private record Stored(String turnId, String tool, Map<String, Object> arguments, String recordedAt,
                          Pending pending, String referenceDate, List<CitationRef> citations) { }

    private final MongoOperations mongo;
    private final Duration lifetime;
    private final byte[] encryptionKey;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    WaviMongoDialogueStore(MongoOperations mongo, MyHrChatHistoryProvenance provenance) {
        this(mongo, provenance, Duration.ofHours(3));
    }

    WaviMongoDialogueStore(MongoOperations mongo, MyHrChatHistoryProvenance provenance, Duration lifetime) {
        super(lifetime);
        if (mongo == null || provenance == null || !provenance.hasSharedKey()) {
            throw new IllegalStateException("Shared dialogue requires MongoDB and the shared history-provenance key");
        }
        if (lifetime == null || lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("Dialogue lifetime must be positive");
        }
        this.mongo = mongo;
        this.lifetime = lifetime;
        this.encryptionKey = provenance.derivedKey("dialogue-encryption");
    }

    @Override
    Snapshot snapshot(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session,
                      String latestVerifiedTurnId, Instant now) {
        if (actor == null || session == null || now == null) return new Snapshot(-1, Optional.empty());
        String owner = key(actor, session);
        Document document = mongo.findById(owner, Document.class, COLLECTION);
        if (document == null) return new Snapshot(0, Optional.empty());
        Date expiry = document.getDate("expiresAt");
        if (expiry == null) throw unavailable();
        if (!expiry.toInstant().isAfter(now)) return new Snapshot(0, Optional.empty());
        Object number = document.get("revision");
        if (!(number instanceof Number) || ((Number) number).longValue() <= 0) throw unavailable();
        long revision = ((Number) number).longValue();
        State state = decode(owner, revision, expiry, document.get("payload"));
        if (latestVerifiedTurnId != null && !state.turnId().equals(latestVerifiedTurnId)) {
            return new Snapshot(-1, Optional.empty());
        }
        return new Snapshot(revision, latestVerifiedTurnId != null && !NO_STATE.equals(state.tool())
                ? Optional.of(state) : Optional.empty());
    }

    @Override
    Optional<State> find(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session,
                         String latestVerifiedTurnId, Instant now) {
        return snapshot(actor, session, latestVerifiedTurnId, now).previous();
    }

    @Override
    boolean recordIfUnchanged(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session,
                              State state, long expectedRevision, Instant now) {
        if (actor == null || session == null || state == null || state.turnId() == null || state.tool() == null
                || expectedRevision < 0 || now == null) return false;
        String owner = key(actor, session);
        long revision;
        do { revision = random.nextLong() & Long.MAX_VALUE; } while (revision == 0 || revision == expectedRevision);
        Date expiry = Date.from(now.plus(lifetime));
        byte[] payload = encode(owner, revision, expiry, state);
        Criteria match = Criteria.where("_id").is(owner);
        if (expectedRevision == 0) match = match.and("expiresAt").lte(Date.from(now));
        else match = match.and("revision").is(expectedRevision).and("expiresAt").gt(Date.from(now));
        Update update = new Update().set("revision", revision).set("expiresAt", expiry).set("payload", payload);
        Document changed = mongo.findAndModify(Query.query(match), update,
                FindAndModifyOptions.options().returnNew(true), Document.class, COLLECTION);
        if (changed != null) return true;
        if (expectedRevision != 0) return false;
        try {
            mongo.insert(new Document("_id", owner).append("revision", revision).append("expiresAt", expiry)
                    .append("payload", payload), COLLECTION);
            return true;
        } catch (DuplicateKeyException concurrentTurn) {
            return false;
        }
    }

    @Override
    void record(VerifiedMyHrActor actor, MyHrChatHistoryProvenance.Session session, State state) {
        throw new UnsupportedOperationException("Shared dialogue requires a revision check");
    }

    private byte[] encode(String owner, long revision, Date expiry, State state) {
        WaviClarification pending = state.pending();
        Stored stored = new Stored(state.turnId(), state.tool(), state.arguments(),
                state.recordedAt() == null ? null : state.recordedAt().toString(),
                pending == null ? null : new Pending(pending.question(), pending.subject(), pending.submission(),
                        pending.date(), pending.own()), state.referenceDate() == null ? null : state.referenceDate().toString(),
                state.citations());
        try {
            byte[] nonce = new byte[NONCE_BYTES];
            random.nextBytes(nonce);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, owner, revision, expiry);
            byte[] encrypted = cipher.doFinal(JSON.writeValueAsBytes(stored));
            if (nonce.length + encrypted.length > MAX_CIPHERTEXT_BYTES) throw unavailable();
            byte[] result = Arrays.copyOf(nonce, nonce.length + encrypted.length);
            System.arraycopy(encrypted, 0, result, nonce.length, encrypted.length);
            return result;
        } catch (GeneralSecurityException | RuntimeException invalid) {
            throw unavailable();
        }
    }

    private State decode(String owner, long revision, Date expiry, Object payload) {
        byte[] bytes = payload instanceof Binary binary ? binary.getData() : payload instanceof byte[] raw ? raw : null;
        if (bytes == null || bytes.length <= NONCE_BYTES || bytes.length > MAX_CIPHERTEXT_BYTES) throw unavailable();
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, Arrays.copyOf(bytes, NONCE_BYTES), owner, revision, expiry);
            Stored stored = JSON.readValue(cipher.doFinal(bytes, NONCE_BYTES, bytes.length - NONCE_BYTES), Stored.class);
            if (stored.turnId() == null || stored.tool() == null || stored.arguments() == null) throw unavailable();
            Pending pending = stored.pending();
            return new State(stored.turnId(), stored.tool(), stored.arguments(),
                    stored.recordedAt() == null ? null : Instant.parse(stored.recordedAt()),
                    pending == null ? null : new WaviClarification(pending.question(), pending.subject(), pending.submission(),
                            pending.date(), pending.own()),
                    stored.referenceDate() == null ? null : LocalDate.parse(stored.referenceDate()), null, stored.citations());
        } catch (GeneralSecurityException | RuntimeException invalid) {
            throw unavailable();
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String owner, long revision, Date expiry) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD((owner + ':' + revision + ':' + expiry.getTime()).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static DataAccessResourceFailureException unavailable() {
        return new DataAccessResourceFailureException("Conversation state unavailable");
    }
}
