package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.TokenCipher;
import com.waypoint.security.TokenCipherException;
import com.waypoint.service.EmailAttachment;
import com.waypoint.service.EmailSenderIdentity;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sends one email through the Gmail API as the Google account connected by <em>one specific organization</em>.
 *
 * <p>Safety rules, all enforced here at send time (the resolver's earlier check is only a hint):</p>
 * <ul>
 *   <li>The connection is loaded by the message's organization id and nothing else; there is no way to reach another
 *       organization's connection.</li>
 *   <li>The connection must be CONNECTED, the feature enabled, the stored token decryptable for that organization,
 *       the connected account must be the sender the identity names, and the account's domain must still equal the
 *       Organization Email's domain.</li>
 *   <li>Access tokens live only in memory, are refreshed from the encrypted refresh token on demand and are never
 *       stored, logged or returned. Neither message content nor addresses are logged.</li>
 * </ul>
 *
 * <p>Outcomes: returns normally when Google accepted the message; throws {@link Unavailable} when the message
 * definitely was <em>not</em> sent (the router may then use SMTP); throws {@link DeliveryUncertain} when Google gave no
 * answer, so the message may have been sent and must not be re-sent.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GmailApiEmailService {

    /**
     * The only size figure Google's documentation gives for sending is the upload guide's "5 MB or less" for the simple
     * upload type. Neither the messages.send reference, the sending guide nor the usage-limits page states a maximum
     * message size or a limit on the JSON {@code raw} field (the 35 MB figure quoted elsewhere is not in the current
     * official pages), so no larger limit is assumed. The cap below keeps the whole encoded request body under that one
     * documented figure: the message is sent as base64url, which is 4/3 the size of the MIME bytes, so
     * 3,500,000 MIME bytes become about 4.67 MB, below 5 MiB (5,242,880) with room for the JSON wrapper. Larger messages
     * go by SMTP instead of risking a rejected API call.
     */
    static final long ENCODED_BODY_BUDGET_BYTES = 5L * 1024 * 1024;
    static final int MAX_RAW_MESSAGE_BYTES = 3_500_000;
    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final Duration LAST_SEND_UPDATE_INTERVAL = Duration.ofMinutes(10);
    private static final int DEFAULT_TOKEN_LIFETIME_SECONDS = 300;

    private final GoogleOAuthProperties properties;
    private final TokenCipher tokenCipher;
    private final GoogleOAuthClient googleClient;
    private final OrganizationGoogleConnectionRepository connections;
    private final OrganizationRepository organizations;
    private final ApplicationEventPublisher events;

    /** In-memory only, per organization, tied to the exact stored token so a reconnect or switch never reuses it. */
    private final Map<String, CachedAccessToken> accessTokens = new ConcurrentHashMap<>();
    private final Map<String, Object> refreshLocks = new ConcurrentHashMap<>();

    /** The message was definitely not sent; {@link #code()} says why. Safe to retry through SMTP. */
    public static class Unavailable extends RuntimeException {
        private final String code;

        public Unavailable(String code) {
            super("Gmail API not used: " + code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** Google did not answer; the message may have been delivered. Must not be re-sent. */
    public static class DeliveryUncertain extends RuntimeException {
        public DeliveryUncertain() {
            super("Gmail API gave no answer; delivery is uncertain");
        }
    }

    private record CachedAccessToken(String refreshTokenCiphertext, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "CachedAccessToken[redacted]";
        }
    }

    public void send(EmailSenderIdentity identity, String to, String subject, String htmlBody, String plainTextBody,
                     List<EmailAttachment> attachments) {
        if (identity == null || !identity.usesGoogleSender()) throw new Unavailable("NO_GOOGLE_IDENTITY");
        String organizationId = identity.gmailOrganizationId();
        if (!properties.isEnabled()) throw new Unavailable("FEATURE_DISABLED");

        OrganizationGoogleConnection connection = connections.findByOrganizationId(organizationId)
                .orElseThrow(() -> new Unavailable("NOT_CONNECTED"));
        Organization organization = organizations.findById(organizationId).orElseThrow(() -> new Unavailable("ORGANIZATION_NOT_FOUND"));

        String problem = GoogleSenderEligibility.problemWith(organization, connection);
        if (problem != null) throw new Unavailable(problem);
        // The identity was resolved earlier; the account it names must still be this organization's connected account.
        if (!connection.getGoogleAccountEmail().equalsIgnoreCase(identity.gmailSenderAddress())) {
            throw new Unavailable("SENDER_MISMATCH");
        }

        String raw = encode(buildMessage(connection.getGoogleAccountEmail(), identity, to, subject, htmlBody, plainTextBody, attachments));
        String accessToken = accessTokenFor(connection);
        try {
            googleClient.sendMessage(accessToken, raw);
        } catch (GoogleOAuthException failure) {
            handleSendFailure(connection, failure.errorCode());
        }
        recordSend(connection);
    }

    // ------------------------------------------------------------------ message

    private MimeMessage buildMessage(String senderAddress, EmailSenderIdentity identity, String to, String subject,
                                     String htmlBody, String plainTextBody, List<EmailAttachment> attachments) {
        if (!StringUtils.hasText(to)) throw new IllegalArgumentException("Recipient is required");
        if (!StringUtils.hasText(subject)) throw new IllegalArgumentException("Email subject is required");
        if (!StringUtils.hasText(htmlBody)) throw new IllegalArgumentException("Email body is required");
        List<EmailAttachment> files = attachments == null ? List.of() : attachments;
        try {
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            MimeMessageHelper helper = new MimeMessageHelper(message, StringUtils.hasText(plainTextBody) || !files.isEmpty(), "UTF-8");
            helper.setTo(to);
            if (StringUtils.hasText(identity.displayName())) helper.setFrom(senderAddress, identity.displayName());
            else helper.setFrom(senderAddress);
            if (StringUtils.hasText(identity.replyTo())) helper.setReplyTo(identity.replyTo());
            helper.setSubject(subject);
            if (StringUtils.hasText(plainTextBody)) helper.setText(plainTextBody, htmlBody);
            else helper.setText(htmlBody, true);
            for (EmailAttachment attachment : files) {
                helper.addAttachment(attachment.fileName(), new ByteArrayResource(attachment.content()), attachment.contentType());
            }
            return message;
        } catch (MessagingException | UnsupportedEncodingException failure) {
            throw new IllegalStateException("Failed to build email message", failure);
        }
    }

    private String encode(MimeMessage message) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            if (bytes.size() > MAX_RAW_MESSAGE_BYTES) throw new Unavailable("MESSAGE_TOO_LARGE");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (IOException | MessagingException failure) {
            throw new IllegalStateException("Failed to build email message", failure);
        }
    }

    // ------------------------------------------------------------------ access token

    private String accessTokenFor(OrganizationGoogleConnection connection) {
        String organizationId = connection.getOrganizationId();
        Object lock = refreshLocks.computeIfAbsent(organizationId, key -> new Object());
        synchronized (lock) {
            CachedAccessToken cached = accessTokens.get(organizationId);
            if (cached != null && cached.refreshTokenCiphertext().equals(connection.getRefreshTokenCiphertext())
                    && cached.expiresAt().isAfter(Instant.now())) {
                return cached.token();
            }
            String refreshToken;
            try {
                refreshToken = tokenCipher.decrypt(new TokenCipher.EncryptedValue(connection.getRefreshTokenCiphertext(),
                        connection.getRefreshTokenIv(), connection.getEncryptionKeyId()), organizationId);
            } catch (TokenCipherException unusable) {
                throw new Unavailable("TOKEN_UNREADABLE");
            }
            GoogleOAuthClient.AccessToken fresh;
            try {
                fresh = googleClient.refreshAccessToken(refreshToken);
            } catch (GoogleOAuthException failure) {
                accessTokens.remove(organizationId);
                if ("INVALID_GRANT".equals(failure.errorCode())) {
                    markReauthRequired(connection, failure.errorCode());
                    throw new Unavailable("REAUTH_REQUIRED");
                }
                throw new Unavailable("TOKEN_UNAVAILABLE");
            }
            int lifetime = fresh.expiresInSeconds() > 0 ? fresh.expiresInSeconds() : DEFAULT_TOKEN_LIFETIME_SECONDS;
            accessTokens.put(organizationId, new CachedAccessToken(connection.getRefreshTokenCiphertext(), fresh.value(),
                    Instant.now().plusSeconds(lifetime).minus(TOKEN_REFRESH_MARGIN)));
            return fresh.value();
        }
    }

    // ------------------------------------------------------------------ outcomes

    private void handleSendFailure(OrganizationGoogleConnection connection, String code) {
        accessTokens.remove(connection.getOrganizationId());
        if (GoogleOAuthClient.NO_RESPONSE.equals(code)) {
            log.warn("Gmail API gave no answer [organizationId={}]", connection.getOrganizationId());
            throw new DeliveryUncertain();
        }
        if ("HTTP_401".equals(code)) {
            markReauthRequired(connection, code);
            throw new Unavailable("REAUTH_REQUIRED");
        }
        recordError(connection, code);
        throw new Unavailable("GMAIL_" + code);
    }

    private void markReauthRequired(OrganizationGoogleConnection connection, String code) {
        boolean alreadyFlagged = connection.getStatus() == GoogleConnectionStatus.REAUTH_REQUIRED;
        try {
            connection.setStatus(GoogleConnectionStatus.REAUTH_REQUIRED);
            connection.setLastErrorCode(code);
            connection.setLastErrorAt(LocalDateTime.now());
            connection.setUpdatedAt(LocalDateTime.now());
            // Optimistic locking: if two sends fail at once only one save wins, so only one notification is raised.
            connections.save(connection);
        } catch (RuntimeException ignored) {
            // A concurrent change (for example a disconnect or the other failing send) wins; the message still falls back safely.
            return;
        }
        log.warn("Google connection needs to be reconnected [organizationId={}, reason={}]", connection.getOrganizationId(), code);
        if (alreadyFlagged) return;
        try {
            events.publishEvent(new GoogleConnectionReauthRequiredEvent(connection.getOrganizationId(), code));
        } catch (RuntimeException failure) {
            log.warn("Reconnect notification could not be raised [organizationId={}, exceptionType={}]",
                    connection.getOrganizationId(), failure.getClass().getSimpleName());
        }
    }

    private void recordError(OrganizationGoogleConnection connection, String code) {
        try {
            connection.setLastErrorCode(code);
            connection.setLastErrorAt(LocalDateTime.now());
            connections.save(connection);
        } catch (RuntimeException ignored) {
            // Diagnostic only.
        }
    }

    private void recordSend(OrganizationGoogleConnection connection) {
        LocalDateTime now = LocalDateTime.now();
        if (connection.getLastSendAt() != null && connection.getLastSendAt().isAfter(now.minus(LAST_SEND_UPDATE_INTERVAL))) return;
        try {
            connection.setLastSendAt(now);
            connection.setLastErrorCode(null);
            connection.setLastErrorAt(null);
            connections.save(connection);
        } catch (RuntimeException ignored) {
            // Diagnostic only; optimistic locking makes a lost race harmless.
        }
    }
}
