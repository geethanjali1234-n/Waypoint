package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.dto.myhr.GoogleConnectStartResponse;
import com.waypoint.dto.platform.PlatformGoogleSenderStatusResponse;
import com.waypoint.entity.GoogleOAuthState;
import com.waypoint.entity.PlatformGoogleSenderConnection;
import com.waypoint.repository.GoogleOAuthStateRepository;
import com.waypoint.repository.PlatformGoogleSenderConnectionRepository;
import com.waypoint.security.TokenCipher;
import com.waypoint.security.TokenCipherException;
import com.waypoint.security.WaypointAdminAuthenticatedUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;

/** OAuth authorization flow for the singleton platform Gmail sender. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlatformGoogleAuthorizationService {
    private static final int MAX_STATE_LENGTH = 256;
    private static final int MAX_CODE_LENGTH = 2048;
    private static final int MAX_ERROR_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoogleOAuthProperties properties;
    private final TokenCipher tokenCipher;
    private final GoogleOAuthClient googleClient;
    private final GoogleOAuthStateRepository states;
    private final PlatformGoogleSenderConnectionRepository connections;
    private final PlatformGoogleSenderService platformSender;
    private final WaypointAdminAuthenticatedUserService authenticatedAdmins;
    private final GoogleRateLimits rateLimits;

    public GoogleConnectStartResponse start(String actorUserId) {
        var actor = authenticatedAdmins.require(actorUserId);
        requireEnabled();
        if (!rateLimits.allowConnectAttempt(actor.userId())) {
            throw new com.waypoint.exception.BusinessException("Too many connection attempts. Please wait a few minutes and try again.");
        }
        String state = randomToken();
        String verifier = randomToken();
        TokenCipher.EncryptedValue encrypted = tokenCipher.encrypt(verifier, PlatformGoogleSenderConnection.ID);
        LocalDateTime now = LocalDateTime.now();
        states.save(GoogleOAuthState.builder().stateHash(hash(state)).userId(actor.userId()).platformSender(true)
                .codeVerifierCiphertext(encrypted.ciphertext()).codeVerifierIv(encrypted.iv()).encryptionKeyId(encrypted.keyId())
                .createdAt(now).expiresAt(now.plusMinutes(properties.getStateTtlMinutes())).build());
        return new GoogleConnectStartResponse(googleClient.buildAuthorizationUrl(state, codeChallenge(verifier),
                GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS));
    }

    /** The callback router uses only server-side state to decide which flow owns the callback. */
    public boolean ownsState(String rawState) {
        if (!StringUtils.hasText(rawState) || rawState.length() > MAX_STATE_LENGTH) return false;
        return states.findByStateHash(hash(rawState)).map(GoogleOAuthState::isPlatformSender).orElse(false);
    }

    public GoogleConnectResult handleCallback(String code, String rawState, String error) {
        try {
            if (!StringUtils.hasText(rawState) || rawState.length() > MAX_STATE_LENGTH) return GoogleConnectResult.INVALID_STATE;
            String stateHash = hash(rawState);
            LocalDateTime now = LocalDateTime.now();
            if (states.consume(stateHash, now) != 1) return unusableState(stateHash, now);
            GoogleOAuthState state = states.findByStateHash(stateHash).orElse(null);
            if (state == null || !state.isPlatformSender()) return GoogleConnectResult.INVALID_STATE;
            if (!properties.isPlatformSenderEnabled()) return GoogleConnectResult.FEATURE_DISABLED;
            if (StringUtils.hasText(error)) return error.length() > MAX_ERROR_LENGTH ? GoogleConnectResult.AUTHORIZATION_FAILED
                    : "access_denied".equals(error) ? GoogleConnectResult.ACCESS_DENIED : GoogleConnectResult.AUTHORIZATION_FAILED;
            try { authenticatedAdmins.require(state.getUserId()); } catch (RuntimeException denied) { return GoogleConnectResult.NOT_AUTHORIZED; }
            if (!StringUtils.hasText(code) || code.length() > MAX_CODE_LENGTH) return GoogleConnectResult.AUTHORIZATION_FAILED;
            String verifier;
            try {
                verifier = tokenCipher.decrypt(new TokenCipher.EncryptedValue(state.getCodeVerifierCiphertext(), state.getCodeVerifierIv(),
                        state.getEncryptionKeyId()), PlatformGoogleSenderConnection.ID);
            } catch (TokenCipherException invalid) { return GoogleConnectResult.CONNECT_FAILED; }
            GoogleOAuthClient.TokenResponse tokens;
            try { tokens = googleClient.exchangeCode(code, verifier); }
            catch (GoogleOAuthException failed) { return GoogleConnectResult.TOKEN_EXCHANGE_FAILED; }
            if (!tokens.grantsScope(GoogleOAuthClient.SCOPE_GMAIL_SEND)) return GoogleConnectResult.MISSING_SCOPE;
            if (!StringUtils.hasText(tokens.refreshToken())) return GoogleConnectResult.NO_REFRESH_TOKEN;
            GoogleOAuthClient.Identity identity;
            try { identity = googleClient.parseIdentity(tokens.idToken(), Instant.now()); }
            catch (GoogleOAuthException invalid) { return GoogleConnectResult.INVALID_IDENTITY; }
            try { platformSender.storeAuthorizedConnection(identity, tokens.refreshToken(), state.getUserId()); }
            catch (PlatformGoogleSenderService.Unavailable failure) {
                if ("ACCOUNT_EMAIL_MISMATCH".equals(failure.code())) {
                    // This grant is unusable by the singleton platform sender, so remove it rather than leaving access
                    // behind on an account that WayPoint must never use.
                    googleClient.revoke(tokens.refreshToken());
                    return GoogleConnectResult.ACCOUNT_EMAIL_MISMATCH;
                }
                return GoogleConnectResult.CONNECT_FAILED;
            }
            return GoogleConnectResult.CONNECTED;
        } catch (RuntimeException failure) {
            log.warn("Platform Google authorization failed [reason=CONNECT_FAILED, exceptionType={}]", failure.getClass().getSimpleName());
            return GoogleConnectResult.CONNECT_FAILED;
        }
    }

    public PlatformGoogleSenderStatusResponse status(String actorUserId) {
        authenticatedAdmins.require(actorUserId);
        PlatformGoogleSenderConnection connection = connections.findById(PlatformGoogleSenderConnection.ID).orElse(null);
        boolean authorized = connection != null && GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS
                .equalsIgnoreCase(connection.getGoogleAccountEmail()) && StringUtils.hasText(connection.getRefreshTokenCiphertext());
        return new PlatformGoogleSenderStatusResponse(properties.isPlatformSenderEnabled(), authorized,
                authorized ? connection.getGoogleAccountEmail() : null, authorized ? connection.getConnectedAt() : null,
                authorized ? connection.getLastErrorCode() : null);
    }

    private void requireEnabled() {
        if (!properties.isPlatformSenderEnabled()) throw new com.waypoint.exception.BusinessException("Platform Google email is not enabled");
    }
    private GoogleConnectResult unusableState(String hash, LocalDateTime now) {
        GoogleOAuthState state = states.findByStateHash(hash).orElse(null);
        if (state == null || !state.isPlatformSender()) return GoogleConnectResult.INVALID_STATE;
        if (state.getUsedAt() != null) return GoogleConnectResult.STATE_ALREADY_USED;
        return state.getExpiresAt() == null || !state.getExpiresAt().isAfter(now) ? GoogleConnectResult.STATE_EXPIRED : GoogleConnectResult.INVALID_STATE;
    }
    private static String randomToken() { byte[] bytes = new byte[32]; RANDOM.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String codeChallenge(String verifier) { return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier)); }
    private static String hash(String state) { return HexFormat.of().formatHex(digest(state)); }
    private static byte[] digest(String value) { try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); } }
}
