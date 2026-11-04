package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.dto.myhr.GoogleConnectStartResponse;
import com.waypoint.dto.myhr.GoogleConnectionStatusResponse;
import com.waypoint.entity.GoogleOAuthState;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ResourceNotFoundException;
import com.waypoint.repository.GoogleOAuthStateRepository;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.TokenCipher;
import com.waypoint.security.TokenCipherException;
import com.waypoint.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Connects an organization to its own Google account so its email can later be sent through the Gmail API.
 *
 * <p>Isolation rules enforced here:</p>
 * <ul>
 *   <li>Connect, status and disconnect require an active {@code COMPANY_ADMIN}; the organization is always the
 *       authenticated user's own, never a request parameter.</li>
 *   <li>The public OAuth callback trusts nothing in its query string except the one-time state. The state record
 *       (created by the authenticated "connect" call) names the organization and the initiating admin, and is
 *       consumed atomically, so it works once and only before it expires. The admin is re-checked at callback time.</li>
 *   <li>The connected account's domain must equal the Organization Email's domain; otherwise nothing is stored. On a
 *       consumer domain (gmail.com, googlemail.com) the domain proves nothing, so the account must be exactly the
 *       Organization Email.</li>
 *   <li>One connection per organization, and one organization per Google account.</li>
 *   <li>The refresh token is stored only as AES-256-GCM ciphertext bound to the organization id. Access tokens are
 *       never stored. Tokens, codes and secrets are never logged.</li>
 * </ul>
 * Sending is done by {@link GmailApiEmailService}; this class only manages the connection.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoogleConnectionService {

    private static final Set<UserRole> MANAGE_ROLES = Set.of(UserRole.COMPANY_ADMIN);
    /** Domains open to anyone, so a matching domain does not show that the account belongs to the organization. */
    private static final Set<String> CONSUMER_DOMAINS = Set.of("gmail.com", "googlemail.com");
    private static final String ENTITY_TYPE = "OrganizationGoogleConnection";
    private static final int MAX_STATE_LENGTH = 256;
    /** Google authorization codes are short; anything much larger is not from Google. */
    private static final int MAX_CODE_LENGTH = 2048;
    private static final int MAX_ERROR_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final GoogleOAuthProperties properties;
    private final TokenCipher tokenCipher;
    private final GoogleOAuthClient googleClient;
    private final GoogleOAuthStateRepository states;
    private final OrganizationGoogleConnectionRepository connections;
    private final OrganizationRepository organizations;
    private final MyHrAuthenticatedUserService authenticatedUserService;
    private final AuditService auditService;
    private final GoogleRateLimits rateLimits;

    /** What the callback controller needs to redirect the browser. {@code accountDomain} is set for DOMAIN_MISMATCH only. */
    public record CallbackOutcome(GoogleConnectResult result, String accountDomain) {
        public static CallbackOutcome of(GoogleConnectResult result) {
            return new CallbackOutcome(result, null);
        }
    }

    // ---------------------------------------------------------------- connect: start

    public GoogleConnectStartResponse start(String actorUserId) {
        var actor = authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);
        requireEnabled();
        if (!rateLimits.allowConnectAttempt(actor.userId())) {
            throw new BusinessException("Too many connection attempts. Please wait a few minutes and try again.");
        }
        Organization organization = organizations.findById(actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        if (domainOf(organization.getOrganizationEmail()) == null) {
            throw new BusinessException("Set a valid Organization Email before connecting a Google account");
        }

        String state = randomToken();
        String codeVerifier = randomToken();
        TokenCipher.EncryptedValue encryptedVerifier = tokenCipher.encrypt(codeVerifier, actor.organizationId());
        LocalDateTime now = LocalDateTime.now();
        states.save(GoogleOAuthState.builder()
                .stateHash(sha256Hex(state))
                .organizationId(actor.organizationId())
                .userId(actor.userId())
                .codeVerifierCiphertext(encryptedVerifier.ciphertext())
                .codeVerifierIv(encryptedVerifier.iv())
                .encryptionKeyId(encryptedVerifier.keyId())
                .createdAt(now)
                .expiresAt(now.plusMinutes(properties.getStateTtlMinutes()))
                .build());

        return new GoogleConnectStartResponse(googleClient.buildAuthorizationUrl(
                state, codeChallengeOf(codeVerifier), organization.getOrganizationEmail()));
    }

    // ---------------------------------------------------------------- connect: callback

    /**
     * Completes (or rejects) a connection. Never throws: every failure becomes a {@link GoogleConnectResult} so the
     * controller can always redirect the browser back to the settings page.
     */
    public CallbackOutcome handleCallback(String code, String stateValue, String error) {
        try {
            return process(code, stateValue, error);
        } catch (RuntimeException failure) {
            log.warn("Google connection failed [reason=CONNECT_FAILED, exceptionType={}]", failure.getClass().getSimpleName());
            return CallbackOutcome.of(GoogleConnectResult.CONNECT_FAILED);
        }
    }

    private CallbackOutcome process(String code, String stateValue, String error) {
        if (!StringUtils.hasText(stateValue) || stateValue.length() > MAX_STATE_LENGTH) {
            return CallbackOutcome.of(GoogleConnectResult.INVALID_STATE);
        }
        String stateHash = sha256Hex(stateValue);
        LocalDateTime now = LocalDateTime.now();

        // Atomic, single use. Everything below runs only for the request that wins this update.
        if (states.consume(stateHash, now) != 1) {
            return CallbackOutcome.of(classifyUnusableState(stateHash, now));
        }
        GoogleOAuthState state = states.findByStateHash(stateHash).orElse(null);
        if (state == null) return CallbackOutcome.of(GoogleConnectResult.INVALID_STATE);
        String organizationId = state.getOrganizationId();

        if (!properties.isEnabled()) return CallbackOutcome.of(GoogleConnectResult.FEATURE_DISABLED);
        if (StringUtils.hasText(error)) {
            if (error.length() > MAX_ERROR_LENGTH) return CallbackOutcome.of(GoogleConnectResult.AUTHORIZATION_FAILED);
            return CallbackOutcome.of("access_denied".equals(error)
                    ? GoogleConnectResult.ACCESS_DENIED : GoogleConnectResult.AUTHORIZATION_FAILED);
        }

        // The organization comes from the stored state; the initiating admin must still be an active Company Admin of it.
        MyHrAuthenticatedUserService.AuthenticatedMyHrUser actor;
        try {
            actor = authenticatedUserService.require(state.getUserId(), MANAGE_ROLES, false);
        } catch (RuntimeException notAllowed) {
            return CallbackOutcome.of(GoogleConnectResult.NOT_AUTHORIZED);
        }
        if (!organizationId.equals(actor.organizationId())) return CallbackOutcome.of(GoogleConnectResult.NOT_AUTHORIZED);
        Organization organization = organizations.findById(organizationId).orElse(null);
        if (organization == null) return CallbackOutcome.of(GoogleConnectResult.NOT_AUTHORIZED);
        String organizationDomain = domainOf(organization.getOrganizationEmail());
        if (organizationDomain == null) return CallbackOutcome.of(GoogleConnectResult.ORGANIZATION_EMAIL_MISSING);
        if (!StringUtils.hasText(code) || code.length() > MAX_CODE_LENGTH) {
            return CallbackOutcome.of(GoogleConnectResult.AUTHORIZATION_FAILED);
        }

        String codeVerifier;
        try {
            codeVerifier = tokenCipher.decrypt(new TokenCipher.EncryptedValue(
                    state.getCodeVerifierCiphertext(), state.getCodeVerifierIv(), state.getEncryptionKeyId()), organizationId);
        } catch (TokenCipherException unusable) {
            return CallbackOutcome.of(GoogleConnectResult.CONNECT_FAILED);
        }

        GoogleOAuthClient.TokenResponse tokens;
        try {
            tokens = googleClient.exchangeCode(code, codeVerifier);
        } catch (GoogleOAuthException failed) {
            log.warn("Google code exchange failed [organizationId={}, reason={}]", organizationId, failed.errorCode());
            return CallbackOutcome.of(GoogleConnectResult.TOKEN_EXCHANGE_FAILED);
        }
        if (!tokens.grantsScope(GoogleOAuthClient.SCOPE_GMAIL_SEND)) {
            // Google's consent screen lets the person untick individual permissions, so the granted scopes (public
            // permission names, not secrets) are the evidence of what was actually approved. Nothing is revoked here:
            // the grant is per Google account and client, and the same account may serve another sign-in.
            log.warn("Google connection blocked [organizationId={}, reason=MISSING_SCOPE, grantedScopes={}]", organizationId, tokens.scopes());
            return CallbackOutcome.of(GoogleConnectResult.MISSING_SCOPE);
        }
        if (!StringUtils.hasText(tokens.refreshToken())) return CallbackOutcome.of(GoogleConnectResult.NO_REFRESH_TOKEN);

        GoogleOAuthClient.Identity identity;
        try {
            identity = googleClient.parseIdentity(tokens.idToken(), Instant.now());
        } catch (GoogleOAuthException invalid) {
            log.warn("Google identity rejected [organizationId={}, reason={}]", organizationId, invalid.errorCode());
            return CallbackOutcome.of(GoogleConnectResult.INVALID_IDENTITY);
        }

        OrganizationGoogleConnection existing = connections.findByOrganizationId(organizationId).orElse(null);

        // Domain rule: the Google account must belong to the Organization Email's domain. Nothing is stored otherwise.
        String accountDomain = domainOf(identity.email());
        if (!organizationDomain.equals(accountDomain)) {
            revokeIfNotInUse(tokens.refreshToken(), identity.subject(), existing);
            log.warn("Google connection blocked [organizationId={}, reason=DOMAIN_MISMATCH]", organizationId);
            return new CallbackOutcome(GoogleConnectResult.DOMAIN_MISMATCH, accountDomain);
        }

        // Consumer domains: the domain match above is meaningless, so the account must be the Organization Email itself.
        if (!accountMatchesOrganizationEmail(organization.getOrganizationEmail(), identity.email())) {
            revokeIfNotInUse(tokens.refreshToken(), identity.subject(), existing);
            log.warn("Google connection blocked [organizationId={}, reason=ACCOUNT_EMAIL_MISMATCH]", organizationId);
            return CallbackOutcome.of(GoogleConnectResult.ACCOUNT_EMAIL_MISMATCH);
        }

        // One organization per Google account. Not revoked here: Google grants are per account and client, so revoking
        // could invalidate the other organization's working connection.
        var holder = connections.findByGoogleSubjectAndStatus(identity.subject(), GoogleConnectionStatus.CONNECTED);
        if (holder.isPresent() && !organizationId.equals(holder.get().getOrganizationId())) {
            log.warn("Google connection blocked [organizationId={}, reason=ACCOUNT_IN_USE]", organizationId);
            return CallbackOutcome.of(GoogleConnectResult.ACCOUNT_IN_USE);
        }

        String previousRefreshToken = tokenToRevokeWhenSwitching(existing, identity.subject());
        TokenCipher.EncryptedValue encrypted = tokenCipher.encrypt(tokens.refreshToken(), organizationId);
        OrganizationGoogleConnection connection = existing != null ? existing
                : OrganizationGoogleConnection.builder().organizationId(organizationId).createdAt(now).build();
        Map<String, String> before = auditSnapshot(existing);
        connection.setStatus(GoogleConnectionStatus.CONNECTED);
        connection.setGoogleAccountEmail(identity.email());
        connection.setGoogleSubject(identity.subject());
        connection.setHostedDomain(identity.hostedDomain());
        connection.setGrantedScopes(tokens.scopes());
        connection.setRefreshTokenCiphertext(encrypted.ciphertext());
        connection.setRefreshTokenIv(encrypted.iv());
        connection.setEncryptionKeyId(encrypted.keyId());
        connection.setConnectedByUserId(actor.userId());
        connection.setConnectedAt(now);
        connection.setLastErrorCode(null);
        connection.setLastErrorAt(null);
        connection.setDisconnectedAt(null);
        connection.setDisconnectedByUserId(null);
        connection.setUpdatedAt(now);
        OrganizationGoogleConnection saved;
        try {
            saved = connections.save(connection);
        } catch (DuplicateKeyException raced) {
            return CallbackOutcome.of(GoogleConnectResult.ACCOUNT_IN_USE);
        }

        // The previous account's grant is no longer needed once the organization switched accounts.
        if (previousRefreshToken != null) googleClient.revoke(previousRefreshToken);

        auditService.record(actor, AuditActionType.GOOGLE_EMAIL_CONNECTED, ENTITY_TYPE, saved.getId(), identity.email(),
                "Google email account connected", before, auditSnapshot(saved));
        return CallbackOutcome.of(GoogleConnectResult.CONNECTED);
    }

    private GoogleConnectResult classifyUnusableState(String stateHash, LocalDateTime now) {
        GoogleOAuthState state = states.findByStateHash(stateHash).orElse(null);
        if (state == null) return GoogleConnectResult.INVALID_STATE;
        if (state.getUsedAt() != null) return GoogleConnectResult.STATE_ALREADY_USED;
        if (state.getExpiresAt() == null || !state.getExpiresAt().isAfter(now)) return GoogleConnectResult.STATE_EXPIRED;
        return GoogleConnectResult.INVALID_STATE;
    }

    // ---------------------------------------------------------------- status and disconnect

    public GoogleConnectionStatusResponse status(String actorUserId) {
        var actor = authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);
        Organization organization = organizations.findById(actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        return toResponse(organization, connections.findByOrganizationId(actor.organizationId()).orElse(null));
    }

    public GoogleConnectionStatusResponse disconnect(String actorUserId) {
        var actor = authenticatedUserService.require(actorUserId, MANAGE_ROLES, false);
        Organization organization = organizations.findById(actor.organizationId())
                .orElseThrow(() -> new ResourceNotFoundException("Organization not found"));
        OrganizationGoogleConnection connection = connections.findByOrganizationId(actor.organizationId()).orElse(null);
        if (connection == null || connection.getStatus() == GoogleConnectionStatus.DISCONNECTED) {
            return toResponse(organization, connection);
        }

        String refreshToken = decryptQuietly(connection);
        Map<String, String> before = auditSnapshot(connection);
        LocalDateTime now = LocalDateTime.now();
        // Delete our copy first: the connection is gone locally even if Google cannot be reached.
        connection.setStatus(GoogleConnectionStatus.DISCONNECTED);
        connection.setRefreshTokenCiphertext(null);
        connection.setRefreshTokenIv(null);
        connection.setEncryptionKeyId(null);
        connection.setGoogleSubject(null);
        connection.setDisconnectedAt(now);
        connection.setDisconnectedByUserId(actor.userId());
        connection.setUpdatedAt(now);
        OrganizationGoogleConnection saved = connections.save(connection);

        // Safe to revoke: a Google account can be CONNECTED to only one organization, so this grant is this tenant's.
        googleClient.revoke(refreshToken);

        auditService.record(actor, AuditActionType.GOOGLE_EMAIL_DISCONNECTED, ENTITY_TYPE, saved.getId(),
                saved.getGoogleAccountEmail(), "Google email account disconnected", before, auditSnapshot(saved));
        return toResponse(organization, saved);
    }

    // ---------------------------------------------------------------- helpers

    private GoogleConnectionStatusResponse toResponse(Organization organization, OrganizationGoogleConnection connection) {
        boolean live = connection != null && connection.getStatus() != GoogleConnectionStatus.DISCONNECTED;
        return new GoogleConnectionStatusResponse(
                properties.isEnabled(),
                connection == null ? "NOT_CONNECTED" : connection.getStatus().name(),
                live ? connection.getGoogleAccountEmail() : null,
                domainOf(organization.getOrganizationEmail()),
                live ? connection.getConnectedAt() : null,
                live ? connection.getLastSendAt() : null,
                live ? connection.getLastErrorCode() : null);
    }

    /** The old account's refresh token, only when the organization is switching to a different Google account. */
    private String tokenToRevokeWhenSwitching(OrganizationGoogleConnection existing, String newSubject) {
        if (existing == null || existing.getStatus() == GoogleConnectionStatus.DISCONNECTED) return null;
        if (existing.getGoogleSubject() == null || existing.getGoogleSubject().equals(newSubject)) return null;
        return decryptQuietly(existing);
    }

    /**
     * Revokes a just-issued token that will not be used, but only if this Google account is not connected to any
     * organization (including this one), because revoking a grant could break that working connection.
     */
    private void revokeIfNotInUse(String refreshToken, String subject, OrganizationGoogleConnection existing) {
        boolean sameAsOwnConnection = existing != null && subject.equals(existing.getGoogleSubject());
        boolean connectedElsewhere = connections.findByGoogleSubjectAndStatus(subject, GoogleConnectionStatus.CONNECTED).isPresent();
        if (!sameAsOwnConnection && !connectedElsewhere) googleClient.revoke(refreshToken);
    }

    private String decryptQuietly(OrganizationGoogleConnection connection) {
        if (!tokenCipher.isConfigured() || connection.getRefreshTokenCiphertext() == null) return null;
        try {
            return tokenCipher.decrypt(new TokenCipher.EncryptedValue(connection.getRefreshTokenCiphertext(),
                    connection.getRefreshTokenIv(), connection.getEncryptionKeyId()), connection.getOrganizationId());
        } catch (TokenCipherException unusable) {
            return null;
        }
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) throw new BusinessException("Google email connection is not enabled");
    }

    /** Whether the domain is a consumer mail domain, where anyone can hold an address. */
    static boolean isConsumerDomain(String domain) {
        return domain != null && CONSUMER_DOMAINS.contains(domain);
    }

    /**
     * Whether a Google account may serve an organization: its domain must equal the Organization Email's domain and, when
     * that domain is a consumer domain, the whole address must be equal (ignoring case and surrounding spaces).
     */
    static boolean accountMatchesOrganizationEmail(String organizationEmail, String accountEmail) {
        String organizationDomain = domainOf(organizationEmail);
        if (organizationDomain == null || !organizationDomain.equals(domainOf(accountEmail))) return false;
        if (!isConsumerDomain(organizationDomain)) return true;
        return organizationEmail.trim().equalsIgnoreCase(accountEmail.trim());
    }

    /** Lowercased text after the last '@', or null when the address is blank or malformed. */
    static String domainOf(String email) {
        if (!StringUtils.hasText(email)) return null;
        String trimmed = email.trim();
        int at = trimmed.lastIndexOf('@');
        if (at < 1 || at == trimmed.length() - 1 || trimmed.indexOf('@') != at || trimmed.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return trimmed.substring(at + 1).toLowerCase(Locale.ROOT);
    }

    private static Map<String, String> auditSnapshot(OrganizationGoogleConnection connection) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        snapshot.put("Status", connection == null ? "NOT_CONNECTED" : connection.getStatus().name());
        snapshot.put("Google account", connection == null || connection.getGoogleAccountEmail() == null
                ? "none" : connection.getGoogleAccountEmail());
        return snapshot;
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String codeChallengeOf(String codeVerifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256(codeVerifier));
    }

    static String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is not available");
        }
    }
}
