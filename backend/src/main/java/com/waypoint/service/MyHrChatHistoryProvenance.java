package com.waypoint.service;

import com.waypoint.dto.myhr.MyHrChatHistoryMessage;
import com.waypoint.security.VerifiedMyHrActor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-issued provenance for Wavi conversation history. The browser returns earlier messages with every request,
 * so their content and any label attached to them are client input. The server therefore stamps each message of a
 * turn it answered with a MAC over: the verified user, organization and role; a fingerprint of the login session
 * (the bearer token); the message role and exact text; the turn; the time; and whether the text may be sent to an
 * external AI provider. History reaches an external provider only when its stamp verifies for the current request.
 *
 * <p>The key is random per process and never leaves memory, so a restart, another instance, a new login, a role or
 * organization change, an edit to the text, a relabelled role or a stamp older than the session lifetime all make a
 * stamp unverifiable - and unverifiable history is simply not sent to an external provider. No database, browser
 * storage or credential is involved.
 *
 * <p>The stamp attests only that this server produced or received the text in that session and how it classified
 * it. It cannot stop a user from typing restricted text into a new message.
 */
@Component
public class MyHrChatHistoryProvenance {

    /** Whether a message may be sent to an external AI provider (Claude, or an Ollama that is not local). */
    public enum ExternalUse {
        PERMITTED("P"),
        RESTRICTED("R");

        private final String code;

        ExternalUse(String code) {
            this.code = code;
        }

        static ExternalUse ofCode(String code) {
            for (ExternalUse use : values()) {
                if (use.code.equals(code)) return use;
            }
            return null;
        }
    }

    /** What a valid stamp attests. */
    public record Stamp(ExternalUse use, String turnId) {
    }

    /** The login session a conversation belongs to: a SHA-256 fingerprint of its bearer token, never the token. */
    public record Session(String fingerprint) {

        public Session {
            if (fingerprint == null || fingerprint.isBlank()) {
                throw new IllegalArgumentException("A chat session requires a fingerprint");
            }
        }

        /** Null when the header carries no bearer token. */
        public static Session fromAuthorizationHeader(String header) {
            if (header == null || !header.startsWith("Bearer ")) {
                return null;
            }
            String token = header.substring("Bearer ".length()).trim();
            return token.isEmpty() ? null : new Session(sha256(token));
        }

        @Override
        public String toString() {
            return "Session[fingerprint=redacted]";
        }
    }

    static final String VERSION = "v1";
    /** Longest stamp this class issues is about 80 characters; anything much longer is not one. */
    public static final int MAX_STAMP_LENGTH = 200;
    private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofMinutes(1);
    private static final Pattern STAMP = Pattern.compile(
            "v1\\.([PR])\\.([A-Za-z0-9_-]{22})\\.(\\d{1,12})\\.([A-Za-z0-9_-]{43})");
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();

    private final byte[] key;
    private final boolean sharedKeyConfigured;
    private final Duration lifetime;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    /** The stamp lifetime is the login session's: a stamp never outlives the token it was issued under. */
    /**
     * {@code waypoint.myhr.history-provenance.key}: a base64 secret of at least 32 bytes shared by every backend
     * instance, so a stamp issued by one instance verifies on another (no session affinity needed for history).
     * Unset: a random per-process key, as before - correct for one instance; with several, history stamped by
     * another instance is rejected and Wavi loses conversational context (never authorization).
     */
    @Autowired
    public MyHrChatHistoryProvenance(@Value("${jwt.expiration-ms:10800000}") long sessionLifetimeMs,
                                     @Value("${waypoint.myhr.history-provenance.key:}") String sharedKey) {
        this(Duration.ofMillis(sessionLifetimeMs), Clock.systemUTC(), sharedKey(sharedKey));
    }

    MyHrChatHistoryProvenance(Duration lifetime, Clock clock) {
        this(lifetime, clock, null);
    }

    MyHrChatHistoryProvenance(Duration lifetime, Clock clock, byte[] sharedKey) {
        if (lifetime == null || lifetime.isNegative() || lifetime.isZero()) {
            throw new IllegalArgumentException("Chat history provenance requires a positive lifetime");
        }
        sharedKeyConfigured = sharedKey != null;
        if (sharedKey != null) {
            this.key = sharedKey.clone();
        } else {
            this.key = new byte[32];
            random.nextBytes(key);
        }
        this.lifetime = lifetime;
        this.clock = clock;
    }

    boolean hasSharedKey() {
        return sharedKeyConfigured;
    }

    /** Derive separate keys for server-side purposes without exposing the history-signing key. */
    byte[] derivedKey(String purpose) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return hmac.doFinal(("waypoint-wavi-v1:" + purpose).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    /** A stable opaque person reference when instances share their history key; it never grants access. */
    String personReference(String userId) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(derivedKey("person-reference"), "HmacSHA256"));
            return HexFormat.of().formatHex(hmac.doFinal(String.valueOf(userId).getBytes(StandardCharsets.UTF_8)), 0, 12);
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    /** Decodes the configured key; a configured but unusable key fails startup rather than silently weakening. */
    static byte[] sharedKey(String configured) {
        if (configured == null || configured.isBlank()) {
            org.slf4j.LoggerFactory.getLogger(MyHrChatHistoryProvenance.class).warn("MyHR chat history provenance "
                    + "uses a per-process key; set waypoint.myhr.history-provenance.key when running several instances");
            return null;
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured.strip());
        } catch (IllegalArgumentException invalid) {
            throw new IllegalStateException("waypoint.myhr.history-provenance.key must be base64");
        }
        if (decoded.length < 32) {
            throw new IllegalStateException("waypoint.myhr.history-provenance.key must decode to at least 32 bytes");
        }
        return decoded;
    }

    /** A fresh, unguessable id shared by the two messages of one turn. */
    String newTurnId() {
        byte[] id = new byte[16];
        random.nextBytes(id);
        return BASE64.encodeToString(id);
    }

    String stamp(VerifiedMyHrActor actor, Session session, String turnId, String role, String content, ExternalUse use) {
        long issuedAt = clock.instant().getEpochSecond();
        return VERSION + "." + use.code + "." + turnId + "." + issuedAt + "."
                + mac(actor, session, turnId, issuedAt, role, content, use);
    }

    /**
     * What the message's stamp attests for this actor and session, or empty when it does not verify - no stamp,
     * a malformed or forged one, another user, organization, role or session, different text or role, or expired.
     */
    Optional<Stamp> verify(VerifiedMyHrActor actor, Session session, MyHrChatHistoryMessage message) {
        if (actor == null || session == null || message == null || message.provenance() == null
                || message.provenance().length() > MAX_STAMP_LENGTH) {
            return Optional.empty();
        }
        Matcher matcher = STAMP.matcher(message.provenance());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        ExternalUse use = ExternalUse.ofCode(matcher.group(1));
        String turnId = matcher.group(2);
        long issuedAt = Long.parseLong(matcher.group(3));
        Instant issued = Instant.ofEpochSecond(issuedAt);
        Instant now = clock.instant();
        if (use == null || issued.isAfter(now.plus(ALLOWED_CLOCK_SKEW)) || issued.plus(lifetime).isBefore(now)) {
            return Optional.empty();
        }
        String expected = mac(actor, session, turnId, issuedAt, message.role(), message.content(), use);
        boolean valid = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                matcher.group(4).getBytes(StandardCharsets.US_ASCII));
        return valid ? Optional.of(new Stamp(use, turnId)) : Optional.empty();
    }

    private String mac(VerifiedMyHrActor actor, Session session, String turnId, long issuedAt, String role,
                       String content, ExternalUse use) {
        // Every field is length-prefixed, so no two different inputs can produce the same MAC input.
        StringBuilder input = new StringBuilder();
        for (String field : new String[]{VERSION, use.code, turnId, Long.toString(issuedAt), actor.userId(),
                actor.organizationId(), actor.role().name(), session.fingerprint(), String.valueOf(role),
                sha256(content == null ? "" : content)}) {
            input.append(field.length()).append(':').append(field).append('\n');
        }
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(key, "HmacSHA256"));
            return BASE64.encodeToString(hmac.doFinal(input.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException("HmacSHA256 is unavailable", unavailable);
        }
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable", unavailable);
        }
    }
}
