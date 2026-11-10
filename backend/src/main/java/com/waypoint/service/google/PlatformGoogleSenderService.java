package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.entity.PlatformGoogleSenderConnection;
import com.waypoint.repository.PlatformGoogleSenderConnectionRepository;
import com.waypoint.security.TokenCipher;
import com.waypoint.security.TokenCipherException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * Secure storage and short-lived access-token management for WayPoint's one transactional Gmail account.
 *
 * <p>This service intentionally does not start OAuth, expose an endpoint, or send a message. Those pieces belong to
 * later phases. A future callback may call {@link #storeAuthorizedConnection(GoogleOAuthClient.Identity, String, String)}
 * only after exchanging an authorization code and validating its ID token with {@link GoogleOAuthClient}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformGoogleSenderService {

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final int DEFAULT_TOKEN_LIFETIME_SECONDS = 300;

    private final GoogleOAuthProperties properties;
    private final TokenCipher tokenCipher;
    private final GoogleOAuthClient googleClient;
    private final PlatformGoogleSenderConnectionRepository connections;

    private final Object refreshLock = new Object();
    private CachedAccessToken cachedAccessToken;

    /** A safe-to-display code; no token, account address, or provider response is included. */
    public static class Unavailable extends RuntimeException {
        private final String code;

        public Unavailable(String code) {
            super("Platform Gmail sender is unavailable: " + code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private record CachedAccessToken(String refreshTokenCiphertext, String token, Instant expiresAt) {
        @Override
        public String toString() {
            return "CachedAccessToken[redacted]";
        }
    }

    /**
     * Persists a newly-authorized platform account. The token is encrypted before it reaches MongoDB, and the Google
     * account must exactly equal the configured dedicated sender (case-insensitively).
     */
    public void storeAuthorizedConnection(GoogleOAuthClient.Identity identity, String refreshToken, String connectedByUserId) {
        requireFeatureEnabled();
        if (identity == null || !StringUtils.hasText(identity.subject()) || !StringUtils.hasText(identity.email())) {
            throw new Unavailable("INVALID_IDENTITY");
        }
        if (!StringUtils.hasText(refreshToken)) throw new Unavailable("NO_REFRESH_TOKEN");

        String expected = normalizedConfiguredAddress();
        String actual = normalizeAddress(identity.email());
        if (!expected.equals(actual)) {
            log.warn("Platform Gmail authorization rejected [reason=ACCOUNT_EMAIL_MISMATCH]");
            throw new Unavailable("ACCOUNT_EMAIL_MISMATCH");
        }

        TokenCipher.EncryptedValue encrypted;
        try {
            encrypted = tokenCipher.encrypt(refreshToken, PlatformGoogleSenderConnection.ID);
        } catch (TokenCipherException unavailable) {
            throw new Unavailable("TOKEN_ENCRYPTION_UNAVAILABLE");
        }

        LocalDateTime now = LocalDateTime.now();
        PlatformGoogleSenderConnection connection = connections.findById(PlatformGoogleSenderConnection.ID)
                .orElseGet(() -> PlatformGoogleSenderConnection.builder().id(PlatformGoogleSenderConnection.ID)
                        .createdAt(now).build());
        connection.setGoogleAccountEmail(actual);
        connection.setGoogleSubject(identity.subject());
        connection.setRefreshTokenCiphertext(encrypted.ciphertext());
        connection.setRefreshTokenIv(encrypted.iv());
        connection.setEncryptionKeyId(encrypted.keyId());
        connection.setConnectedByUserId(connectedByUserId);
        connection.setConnectedAt(now);
        connection.setLastErrorCode(null);
        connection.setLastErrorAt(null);
        connection.setUpdatedAt(now);
        connections.save(connection);
        synchronized (refreshLock) {
            cachedAccessToken = null;
        }
    }

    /**
     * Returns an in-memory access token for the configured dedicated sender. This is package-independent so a later
     * Gmail transport can use it, but callers must never persist or log the returned value.
     */
    public String accessToken() {
        requireFeatureEnabled();
        synchronized (refreshLock) {
            PlatformGoogleSenderConnection connection = connections.findById(PlatformGoogleSenderConnection.ID)
                    .orElseThrow(() -> new Unavailable("NOT_CONNECTED"));
            verifyStoredSender(connection);
            CachedAccessToken cached = cachedAccessToken;
            if (cached != null && cached.refreshTokenCiphertext().equals(connection.getRefreshTokenCiphertext())
                    && cached.expiresAt().isAfter(Instant.now())) {
                return cached.token();
            }
            String refreshToken = decrypt(connection);
            GoogleOAuthClient.AccessToken fresh;
            try {
                fresh = googleClient.refreshAccessToken(refreshToken);
            } catch (GoogleOAuthException rejected) {
                recordError(connection, rejected.errorCode());
                throw new Unavailable("TOKEN_UNAVAILABLE");
            }
            int lifetime = fresh.expiresInSeconds() > 0 ? fresh.expiresInSeconds() : DEFAULT_TOKEN_LIFETIME_SECONDS;
            cachedAccessToken = new CachedAccessToken(connection.getRefreshTokenCiphertext(), fresh.value(),
                    Instant.now().plusSeconds(lifetime).minus(TOKEN_REFRESH_MARGIN));
            return fresh.value();
        }
    }

    private void requireFeatureEnabled() {
        if (!properties.isPlatformSenderEnabled()) throw new Unavailable("FEATURE_DISABLED");
    }

    private String decrypt(PlatformGoogleSenderConnection connection) {
        if (!StringUtils.hasText(connection.getRefreshTokenCiphertext()) || !StringUtils.hasText(connection.getRefreshTokenIv())) {
            throw new Unavailable("NO_STORED_TOKEN");
        }
        try {
            return tokenCipher.decrypt(new TokenCipher.EncryptedValue(connection.getRefreshTokenCiphertext(),
                    connection.getRefreshTokenIv(), connection.getEncryptionKeyId()), PlatformGoogleSenderConnection.ID);
        } catch (TokenCipherException unusable) {
            throw new Unavailable("TOKEN_UNREADABLE");
        }
    }

    private void verifyStoredSender(PlatformGoogleSenderConnection connection) {
        if (!normalizedConfiguredAddress().equals(normalizeAddress(connection.getGoogleAccountEmail()))) {
            throw new Unavailable("ACCOUNT_EMAIL_MISMATCH");
        }
    }

    private String normalizedConfiguredAddress() {
        String address = normalizeAddress(properties.getPlatformSenderAddress());
        if (!StringUtils.hasText(address)) throw new Unavailable("SENDER_ADDRESS_MISSING");
        if (!GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS.equals(address)) {
            throw new Unavailable("SENDER_ADDRESS_MISMATCH");
        }
        return address;
    }

    private static String normalizeAddress(String address) {
        return address == null ? null : address.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void recordError(PlatformGoogleSenderConnection connection, String code) {
        cachedAccessToken = null;
        try {
            connection.setLastErrorCode(code);
            connection.setLastErrorAt(LocalDateTime.now());
            connection.setUpdatedAt(LocalDateTime.now());
            connections.save(connection);
        } catch (RuntimeException ignored) {
            // Diagnostics must not hide the original inability to obtain an access token.
        }
    }
}
