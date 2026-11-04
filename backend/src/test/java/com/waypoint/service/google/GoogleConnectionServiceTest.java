package com.waypoint.service.google;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.dto.myhr.GoogleConnectStartResponse;
import com.waypoint.dto.myhr.GoogleConnectionStatusResponse;
import com.waypoint.entity.GoogleOAuthState;
import com.waypoint.entity.Organization;
import com.waypoint.entity.OrganizationGoogleConnection;
import com.waypoint.enums.AccountStatus;
import com.waypoint.enums.AuditActionType;
import com.waypoint.enums.GoogleConnectionStatus;
import com.waypoint.enums.UserRole;
import com.waypoint.exception.BusinessException;
import com.waypoint.exception.ForbiddenException;
import com.waypoint.exception.UnauthorizedException;
import com.waypoint.repository.GoogleOAuthStateRepository;
import com.waypoint.repository.OrganizationGoogleConnectionRepository;
import com.waypoint.repository.OrganizationRepository;
import com.waypoint.security.MyHrAuthenticatedUserService;
import com.waypoint.security.TokenCipher;
import com.waypoint.security.TokenCipherException;
import com.waypoint.service.AuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Wires the real {@link GoogleConnectionService} and the real {@link TokenCipher} to in-memory stand-ins for the
 * repositories (including the atomic state consumption and the unique-index behavior) and a mocked Google client.
 */
class GoogleConnectionServiceTest {

    private static final String REFRESH_TOKEN = "refresh-token-secret-value";
    private static final String AUTH_CODE = "auth-code-secret-value";
    private static final String CLIENT_SECRET = "client-secret-value";

    private final List<GoogleOAuthState> stateStore = new ArrayList<>();
    private final Map<String, OrganizationGoogleConnection> connectionStore = new HashMap<>();
    private final Map<String, Organization> organizationStore = new HashMap<>();
    private final Map<String, MyHrAuthenticatedUserService.AuthenticatedMyHrUser> adminStore = new HashMap<>();

    private final GoogleOAuthStateRepository states = mock(GoogleOAuthStateRepository.class);
    private final OrganizationGoogleConnectionRepository connections = mock(OrganizationGoogleConnectionRepository.class);
    private final OrganizationRepository organizations = mock(OrganizationRepository.class);
    private final MyHrAuthenticatedUserService authenticated = mock(MyHrAuthenticatedUserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final GoogleOAuthClient google = mock(GoogleOAuthClient.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private final TokenCipher cipher = new TokenCipher(randomKey(), "v1");

    private GoogleConnectionService service;
    private GoogleOAuthClient.TokenResponse nextTokens;
    private GoogleOAuthClient.Identity nextIdentity;

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret(CLIENT_SECRET);
        properties.setRedirectUri("https://api.example.test/api/oauth/google/callback");

        organization("org-a", "hr@itap.example");
        organization("org-b", "hr@abc.example");
        admin("admin-a", "org-a", UserRole.COMPANY_ADMIN);
        admin("admin-b", "org-b", UserRole.COMPANY_ADMIN);
        when(authenticated.require(eq("hr-a"), anySet(), eq(false))).thenThrow(new ForbiddenException("You do not have access to this resource"));
        when(authenticated.require(eq("employee-a"), anySet(), eq(false))).thenThrow(new ForbiddenException("You do not have access to this resource"));
        when(authenticated.require(eq("ghost"), anySet(), eq(false))).thenThrow(new UnauthorizedException("Authenticated user no longer exists"));

        when(states.save(any(GoogleOAuthState.class))).thenAnswer(call -> {
            stateStore.add(call.getArgument(0));
            return call.getArgument(0);
        });
        when(states.findByStateHash(anyString())).thenAnswer(call -> stateStore.stream()
                .filter(s -> s.getStateHash().equals(call.getArgument(0))).findFirst());
        // Same contract as the real @Query/@Update: unused and unexpired only, and exactly one winner.
        when(states.consume(anyString(), any(LocalDateTime.class))).thenAnswer(call -> {
            synchronized (stateStore) {
                LocalDateTime now = call.getArgument(1);
                for (GoogleOAuthState s : stateStore) {
                    if (s.getStateHash().equals(call.getArgument(0)) && s.getUsedAt() == null && s.getExpiresAt().isAfter(now)) {
                        s.setUsedAt(now);
                        return 1L;
                    }
                }
                return 0L;
            }
        });

        when(connections.findByOrganizationId(anyString())).thenAnswer(call -> Optional.ofNullable(connectionStore.get(call.<String>getArgument(0))));
        when(connections.findByGoogleSubjectAndStatus(anyString(), any(GoogleConnectionStatus.class))).thenAnswer(call ->
                connectionStore.values().stream().filter(c -> call.getArgument(0).equals(c.getGoogleSubject())
                        && c.getStatus() == call.<GoogleConnectionStatus>getArgument(1)).findFirst());
        when(connections.save(any(OrganizationGoogleConnection.class))).thenAnswer(call -> {
            OrganizationGoogleConnection saved = call.getArgument(0);
            // Mirrors the partial unique index on googleSubject among CONNECTED rows.
            boolean duplicate = saved.getStatus() == GoogleConnectionStatus.CONNECTED && connectionStore.values().stream()
                    .anyMatch(c -> c != saved && c.getStatus() == GoogleConnectionStatus.CONNECTED
                            && saved.getGoogleSubject().equals(c.getGoogleSubject()));
            if (duplicate) throw new DuplicateKeyException("duplicate");
            if (saved.getId() == null) saved.setId("conn-" + saved.getOrganizationId());
            connectionStore.put(saved.getOrganizationId(), saved);
            return saved;
        });
        when(organizations.findById(anyString())).thenAnswer(call -> Optional.ofNullable(organizationStore.get(call.<String>getArgument(0))));

        when(google.buildAuthorizationUrl(anyString(), anyString(), any())).thenAnswer(call ->
                "https://google.test/auth?state=" + call.getArgument(0) + "&code_challenge=" + call.getArgument(1));
        when(google.exchangeCode(anyString(), anyString())).thenAnswer(call -> nextTokens);
        when(google.parseIdentity(any(), any())).thenAnswer(call -> nextIdentity);
        when(google.revoke(any())).thenReturn(true);

        service = new GoogleConnectionService(properties, cipher, google, states, connections, organizations, authenticated, audit, new GoogleRateLimits());
        givenGoogleReturns("admin@itap.example", "sub-a");

        logs.start();
        ((Logger) LoggerFactory.getLogger(GoogleConnectionService.class)).addAppender(logs);
        ((Logger) LoggerFactory.getLogger(GoogleOAuthClient.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(GoogleConnectionService.class)).detachAppender(logs);
        ((Logger) LoggerFactory.getLogger(GoogleOAuthClient.class)).detachAppender(logs);
        logs.stop();
    }

    // ------------------------------------------------------------------ helpers

    private void organization(String id, String organizationEmail) {
        organizationStore.put(id, Organization.builder().id(id).organizationName(id).organizationEmail(organizationEmail).build());
    }

    private void admin(String userId, String organizationId, UserRole role) {
        var user = new MyHrAuthenticatedUserService.AuthenticatedMyHrUser(userId, userId, userId + "@login.test", role,
                organizationId, AccountStatus.ACTIVE, false);
        adminStore.put(userId, user);
        when(authenticated.require(eq(userId), anySet(), eq(false))).thenReturn(user);
    }

    private void givenGoogleReturns(String accountEmail, String subject) {
        nextTokens = new GoogleOAuthClient.TokenResponse("access-token-value", REFRESH_TOKEN, "id-token",
                "openid email " + GoogleOAuthClient.SCOPE_GMAIL_SEND);
        nextIdentity = new GoogleOAuthClient.Identity(subject, accountEmail, null);
    }

    private record Flow(String state, String challenge) {
    }

    private Flow startAs(String userId) {
        GoogleConnectStartResponse response = service.start(userId);
        assertThat(response.authorizationUrl()).startsWith("https://google.test/auth?state=");
        ArgumentCaptor<String> state = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> challenge = ArgumentCaptor.forClass(String.class);
        verify(google, org.mockito.Mockito.atLeastOnce()).buildAuthorizationUrl(state.capture(), challenge.capture(), any());
        return new Flow(state.getValue(), challenge.getValue());
    }

    private GoogleConnectionService.CallbackOutcome callback(Flow flow) {
        return service.handleCallback(AUTH_CODE, flow.state(), null);
    }

    private void assertNothingStored(String organizationId) {
        assertThat(connectionStore.get(organizationId)).isNull();
    }

    private static String sha256Hex(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }

    // ------------------------------------------------------------------ start

    @Test
    void startStoresOnlyAHashOfTheStateAnEncryptedVerifierAndTheAuthenticatedOrganization() throws Exception {
        Flow flow = startAs("admin-a");

        assertThat(stateStore).hasSize(1);
        GoogleOAuthState stored = stateStore.get(0);
        assertThat(stored.getOrganizationId()).isEqualTo("org-a");
        assertThat(stored.getUserId()).isEqualTo("admin-a");
        assertThat(stored.getStateHash()).isEqualTo(sha256Hex(flow.state())).isNotEqualTo(flow.state());
        assertThat(stored.getStateHash()).doesNotContain(flow.state());
        assertThat(stored.getCodeVerifierCiphertext()).isNotBlank();
        assertThat(stored.getEncryptionKeyId()).isEqualTo("v1");
        assertThat(stored.getUsedAt()).isNull();
        assertThat(stored.getExpiresAt()).isAfter(LocalDateTime.now().plusMinutes(9)).isBefore(LocalDateTime.now().plusMinutes(11));
        // PKCE: the challenge sent to Google is the SHA-256 of the verifier kept (encrypted) on our side.
        String verifier = cipher.decrypt(new TokenCipher.EncryptedValue(stored.getCodeVerifierCiphertext(),
                stored.getCodeVerifierIv(), stored.getEncryptionKeyId()), "org-a");
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)))).isEqualTo(flow.challenge());
        assertThat(flow.state()).hasSizeGreaterThanOrEqualTo(43);
        verify(google).buildAuthorizationUrl(eq(flow.state()), eq(flow.challenge()), eq("hr@itap.example"));
    }

    @Test
    void everyStartProducesADifferentUnguessableState() {
        Flow first = startAs("admin-a");
        Flow second = startAs("admin-a");

        assertThat(first.state()).isNotEqualTo(second.state());
        assertThat(stateStore).hasSize(2);
    }

    @Test
    void onlyAnActiveCompanyAdminCanStart() {
        for (String user : List.of("hr-a", "employee-a")) {
            assertThatThrownBy(() -> service.start(user)).isInstanceOf(ForbiddenException.class);
        }
        assertThatThrownBy(() -> service.start("ghost")).isInstanceOf(UnauthorizedException.class);
        assertThat(stateStore).isEmpty();
    }

    @Test
    void startIsRefusedWhileTheFeatureIsDisabledOrTheOrganizationEmailIsMissing() {
        properties.setEnabled(false);
        assertThatThrownBy(() -> service.start("admin-a")).isInstanceOf(BusinessException.class).hasMessageContaining("not enabled");

        properties.setEnabled(true);
        organization("org-a", " ");
        assertThatThrownBy(() -> service.start("admin-a")).isInstanceOf(BusinessException.class).hasMessageContaining("Organization Email");
        organization("org-a", "not-an-email");
        assertThatThrownBy(() -> service.start("admin-a")).isInstanceOf(BusinessException.class);
        assertThat(stateStore).isEmpty();
    }

    // ------------------------------------------------------------------ successful connection

    @Test
    void anAdminConnectsTheirOrganizationsGoogleAccount() throws Exception {
        Flow flow = startAs("admin-a");

        var outcome = callback(flow);

        assertThat(outcome.result()).isEqualTo(GoogleConnectResult.CONNECTED);
        OrganizationGoogleConnection saved = connectionStore.get("org-a");
        assertThat(saved.getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
        assertThat(saved.getGoogleAccountEmail()).isEqualTo("admin@itap.example");
        assertThat(saved.getGoogleSubject()).isEqualTo("sub-a");
        assertThat(saved.getConnectedByUserId()).isEqualTo("admin-a");
        assertThat(saved.getGrantedScopes()).contains(GoogleOAuthClient.SCOPE_GMAIL_SEND);
        // The code was exchanged with the verifier whose hash is the challenge sent to Google.
        ArgumentCaptor<String> verifier = ArgumentCaptor.forClass(String.class);
        verify(google).exchangeCode(eq(AUTH_CODE), verifier.capture());
        assertThat(Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getValue().getBytes(StandardCharsets.US_ASCII)))).isEqualTo(flow.challenge());
        verify(audit).record(eq(adminStore.get("admin-a")), eq(AuditActionType.GOOGLE_EMAIL_CONNECTED), anyString(), anyString(),
                eq("admin@itap.example"), anyString(), any(), any());
    }

    @Test
    void theRefreshTokenIsStoredOnlyAsCiphertextBoundToTheOrganization() {
        callback(startAs("admin-a"));

        OrganizationGoogleConnection saved = connectionStore.get("org-a");
        assertThat(saved.getRefreshTokenCiphertext()).isNotBlank().doesNotContain(REFRESH_TOKEN);
        assertThat(saved.toString()).doesNotContain(REFRESH_TOKEN).doesNotContain("access-token-value")
                .doesNotContain(saved.getRefreshTokenCiphertext());
        TokenCipher.EncryptedValue encrypted = new TokenCipher.EncryptedValue(
                saved.getRefreshTokenCiphertext(), saved.getRefreshTokenIv(), saved.getEncryptionKeyId());
        assertThat(cipher.decrypt(encrypted, "org-a")).isEqualTo(REFRESH_TOKEN);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "org-b")).isInstanceOf(TokenCipherException.class);
    }

    @Test
    void theAccessTokenIsNeverPersistedAnywhere() {
        callback(startAs("admin-a"));

        verify(connections, times(1)).save(any());
        assertThat(connectionStore.get("org-a").toString()).doesNotContain("access-token-value");
        assertThat(stateStore.toString()).doesNotContain("access-token-value");
    }

    @Test
    void reconnectingTheSameAccountReplacesTheTokenWithoutRevokingAnything() {
        callback(startAs("admin-a"));
        String firstCiphertext = connectionStore.get("org-a").getRefreshTokenCiphertext();

        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.CONNECTED);

        assertThat(connectionStore).hasSize(1);
        assertThat(connectionStore.get("org-a").getRefreshTokenCiphertext()).isNotEqualTo(firstCiphertext);
        verify(google, never()).revoke(any());
    }

    @Test
    void switchingToADifferentAccountKeepsOneConnectionAndRevokesTheOldGrant() {
        callback(startAs("admin-a"));
        givenGoogleReturns("other.admin@itap.example", "sub-a2");

        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.CONNECTED);

        assertThat(connectionStore).hasSize(1);
        assertThat(connectionStore.get("org-a").getGoogleSubject()).isEqualTo("sub-a2");
        verify(google, times(1)).revoke(REFRESH_TOKEN);
    }

    // ------------------------------------------------------------------ state: invalid, expired, replayed

    @Test
    void anUnknownMissingOrOversizedStateIsRejectedWithoutTouchingGoogle() {
        startAs("admin-a");

        for (String state : new String[]{"made-up-state", null, "", "  ", "x".repeat(300)}) {
            assertThat(service.handleCallback(AUTH_CODE, state, null).result()).isEqualTo(GoogleConnectResult.INVALID_STATE);
        }
        verify(google, never()).exchangeCode(anyString(), anyString());
        assertThat(connectionStore).isEmpty();
        assertThat(stateStore.get(0).getUsedAt()).isNull();
    }

    @Test
    void anExpiredStateIsRejectedAndNothingIsStored() {
        Flow flow = startAs("admin-a");
        stateStore.get(0).setExpiresAt(LocalDateTime.now().minusSeconds(1));

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.STATE_EXPIRED);

        verify(google, never()).exchangeCode(anyString(), anyString());
        assertNothingStored("org-a");
    }

    @Test
    void aStateWorksOnlyOnce() {
        Flow flow = startAs("admin-a");

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.CONNECTED);
        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.STATE_ALREADY_USED);

        verify(google, times(1)).exchangeCode(anyString(), anyString());
    }

    @Test
    void twoConcurrentCallbacksWithTheSameStateProduceExactlyOneConnection() throws Exception {
        Flow flow = startAs("admin-a");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        var ready = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<GoogleConnectResult>> results = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            results.add(pool.submit(() -> {
                ready.await();
                return callback(flow).result();
            }));
        }
        ready.countDown();
        List<GoogleConnectResult> outcomes = new ArrayList<>();
        for (var future : results) outcomes.add(future.get());
        pool.shutdown();

        assertThat(outcomes.stream().filter(r -> r == GoogleConnectResult.CONNECTED)).hasSize(1);
        verify(google, times(1)).exchangeCode(anyString(), anyString());
    }

    @Test
    void theStateIsConsumedEvenWhenTheConnectionLaterFails() {
        Flow flow = startAs("admin-a");
        givenGoogleReturns("someone@gmail.com", "sub-x");

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);
        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.STATE_ALREADY_USED);
    }

    // ------------------------------------------------------------------ authorization denial

    @Test
    void denyingAccessOnGoogleStoresNothingAndBurnsTheState() {
        Flow flow = startAs("admin-a");

        var outcome = service.handleCallback(null, flow.state(), "access_denied");

        assertThat(outcome.result()).isEqualTo(GoogleConnectResult.ACCESS_DENIED);
        verify(google, never()).exchangeCode(anyString(), anyString());
        assertNothingStored("org-a");
        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.STATE_ALREADY_USED);
    }

    @Test
    void otherGoogleErrorsAreReportedAsAuthorizationFailedAndAnErrorWithABadStateIsStillInvalidState() {
        Flow flow = startAs("admin-a");

        assertThat(service.handleCallback(null, flow.state(), "server_error").result()).isEqualTo(GoogleConnectResult.AUTHORIZATION_FAILED);
        assertThat(service.handleCallback(null, "bogus", "access_denied").result()).isEqualTo(GoogleConnectResult.INVALID_STATE);
        assertNothingStored("org-a");
    }

    @Test
    void aMissingCodeIsAnAuthorizationFailure() {
        Flow flow = startAs("admin-a");

        assertThat(service.handleCallback(null, flow.state(), null).result()).isEqualTo(GoogleConnectResult.AUTHORIZATION_FAILED);
        assertThat(service.handleCallback("", startAs("admin-a").state(), null).result()).isEqualTo(GoogleConnectResult.AUTHORIZATION_FAILED);
    }

    // ------------------------------------------------------------------ cross-organization protection

    @Test
    void theOrganizationComesFromTheStoredStateNeverFromTheCallback() {
        Flow flowA = startAs("admin-a");
        givenGoogleReturns("admin@abc.example", "sub-b");
        Flow flowB = startAs("admin-b");

        // Org B's flow can only ever produce org B's connection, and org A's only org A's.
        assertThat(callback(flowB).result()).isEqualTo(GoogleConnectResult.CONNECTED);
        assertThat(connectionStore.keySet()).containsExactly("org-b");
        givenGoogleReturns("admin@itap.example", "sub-a");
        assertThat(callback(flowA).result()).isEqualTo(GoogleConnectResult.CONNECTED);
        assertThat(connectionStore.get("org-a").getGoogleAccountEmail()).isEqualTo("admin@itap.example");
        assertThat(connectionStore.get("org-b").getGoogleAccountEmail()).isEqualTo("admin@abc.example");
    }

    @Test
    void aStateIsRejectedWhenItsInitiatingUserIsNoLongerAnActiveCompanyAdmin() {
        Flow flow = startAs("admin-a");
        stateStore.get(0).setUserId("hr-a");

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.NOT_AUTHORIZED);
        verify(google, never()).exchangeCode(anyString(), anyString());

        Flow second = startAs("admin-a");
        stateStore.get(1).setUserId("ghost");
        assertThat(callback(second).result()).isEqualTo(GoogleConnectResult.NOT_AUTHORIZED);
        assertNothingStored("org-a");
    }

    @Test
    void aStateWhoseUserBelongsToAnotherOrganizationIsRejected() {
        Flow flow = startAs("admin-a");
        stateStore.get(0).setUserId("admin-b"); // tampered record: org-a state, org-b admin

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.NOT_AUTHORIZED);
        assertThat(connectionStore).isEmpty();
    }

    @Test
    void aVerifierEncryptedForAnotherOrganizationCannotBeUsed() {
        Flow flow = startAs("admin-a");
        stateStore.get(0).setOrganizationId("org-b");
        admin("admin-a", "org-b", UserRole.COMPANY_ADMIN);

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.CONNECT_FAILED);
        verify(google, never()).exchangeCode(anyString(), anyString());
        assertThat(connectionStore).isEmpty();
    }

    @Test
    void statusAndDisconnectNeverReachAnotherOrganizationsConnection() {
        callback(startAs("admin-a"));

        GoogleConnectionStatusResponse statusB = service.status("admin-b");
        assertThat(statusB.status()).isEqualTo("NOT_CONNECTED");
        assertThat(statusB.googleAccountEmail()).isNull();

        assertThat(service.disconnect("admin-b").status()).isEqualTo("NOT_CONNECTED");
        assertThat(connectionStore.get("org-a").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
        assertThat(connectionStore.get("org-a").getRefreshTokenCiphertext()).isNotNull();
        verify(google, never()).revoke(any());
    }

    @Test
    void employeesAndHrCannotReadConnectOrDisconnect() {
        callback(startAs("admin-a"));

        for (String user : List.of("hr-a", "employee-a")) {
            assertThatThrownBy(() -> service.status(user)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.start(user)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> service.disconnect(user)).isInstanceOf(ForbiddenException.class);
        }
        assertThat(connectionStore.get("org-a").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
    }

    // ------------------------------------------------------------------ domain rule

    @Test
    void aGoogleAccountOnADifferentDomainThanTheOrganizationEmailIsBlocked() {
        Flow flow = startAs("admin-a");
        givenGoogleReturns("someone@gmail.com", "sub-x");

        var outcome = callback(flow);

        assertThat(outcome.result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);
        assertThat(outcome.accountDomain()).isEqualTo("gmail.com");
        assertNothingStored("org-a");
        verify(audit, never()).record(any(MyHrAuthenticatedUserService.AuthenticatedMyHrUser.class),
                eq(AuditActionType.GOOGLE_EMAIL_CONNECTED), anyString(), anyString(), anyString(), anyString(), any(), any());
        // A grant nobody uses is revoked so no dangling access remains at Google.
        verify(google).revoke(REFRESH_TOKEN);
    }

    @Test
    void aGmailOrganizationConnectsOnlyTheGoogleAccountThatIsItsOrganizationEmail() {
        organization("org-a", "Tardus.org.example@Gmail.com");
        givenGoogleReturns("tardus.org.example@gmail.com", "sub-tardus");

        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.CONNECTED);
        assertThat(connectionStore.get("org-a").getGoogleAccountEmail()).isEqualTo("tardus.org.example@gmail.com");
    }

    @Test
    void aDifferentGmailAccountIsBlockedForAGmailOrganizationEvenThoughTheDomainMatches() {
        organization("org-a", "itap.org.example@gmail.com");
        Flow flow = startAs("admin-a");
        givenGoogleReturns("someone.else@gmail.com", "sub-x");

        var outcome = callback(flow);

        assertThat(outcome.result()).isEqualTo(GoogleConnectResult.ACCOUNT_EMAIL_MISMATCH);
        assertNothingStored("org-a");
        verify(audit, never()).record(any(MyHrAuthenticatedUserService.AuthenticatedMyHrUser.class),
                eq(AuditActionType.GOOGLE_EMAIL_CONNECTED), anyString(), anyString(), anyString(), anyString(), any(), any());
        verify(google).revoke(REFRESH_TOKEN); // nobody uses this grant
    }

    @Test
    void aBlockedGmailAccountThatIsConnectedElsewhereIsNotRevoked() {
        organization("org-b", "other.owner@gmail.com");
        givenGoogleReturns("other.owner@gmail.com", "sub-shared");
        assertThat(callback(startAs("admin-b")).result()).isEqualTo(GoogleConnectResult.CONNECTED);
        organization("org-a", "itap.org.example@gmail.com");
        Flow flow = startAs("admin-a");
        givenGoogleReturns("other.owner@gmail.com", "sub-shared");

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.ACCOUNT_EMAIL_MISMATCH);

        verify(google, never()).revoke(REFRESH_TOKEN);
        assertThat(connectionStore.get("org-b").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
    }

    @Test
    void googlemailAndGmailAreDifferentDomainsAndStayBlocked() {
        organization("org-a", "owner@googlemail.com");
        Flow flow = startAs("admin-a");
        givenGoogleReturns("owner@gmail.com", "sub-x");

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);
        assertNothingStored("org-a");
    }

    @Test
    void aCompanyDomainStillAllowsAnyAccountOnThatDomain() {
        organization("org-a", "hr@itap.example");
        givenGoogleReturns("ops.mailbox@itap.example", "sub-ops");

        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.CONNECTED);
    }

    @Test
    void anotherOrganizationsDomainIsAlsoABlockedMismatch() {
        Flow flow = startAs("admin-a");
        givenGoogleReturns("admin@abc.example", "sub-b");

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);
        assertNothingStored("org-a");
    }

    @Test
    void domainsMustMatchExactlyIgnoringCaseButNotSubdomains() {
        organization("org-a", "HR@ITAP.Example");
        givenGoogleReturns("admin@itap.example", "sub-a");
        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.CONNECTED);

        connectionStore.clear();
        givenGoogleReturns("admin@mail.itap.example", "sub-a");
        var outcome = callback(startAs("admin-a"));
        assertThat(outcome.result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);
        assertThat(outcome.accountDomain()).isEqualTo("mail.itap.example");

        givenGoogleReturns("admin@evilitap.example", "sub-a");
        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);
    }

    @Test
    void theDomainIsCheckedAgainstTheOrganizationEmailAtCallbackTimeNotOnlyAtStart() {
        Flow flow = startAs("admin-a");
        organization("org-a", "hr@changed.example");

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);
        organization("org-a", " ");
        assertThat(callback(startAs2("admin-a", "hr@itap.example")).result()).isEqualTo(GoogleConnectResult.ORGANIZATION_EMAIL_MISSING);
    }

    private Flow startAs2(String user, String emailAtStart) {
        organization("org-a", emailAtStart);
        Flow flow = startAs(user);
        organization("org-a", " ");
        return flow;
    }

    @Test
    void aMismatchedGrantIsNotRevokedWhenThatAccountIsAlreadyConnectedElsewhere() {
        // Org B legitimately uses sub-shared; org A's admin signs in with the same account on the wrong domain.
        organization("org-b", "hr@shared.example");
        givenGoogleReturns("admin@shared.example", "sub-shared");
        callback(startAs("admin-b"));
        assertThat(connectionStore.get("org-b").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);

        Flow flow = startAs("admin-a");
        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.DOMAIN_MISMATCH);

        verify(google, never()).revoke(any());
        assertThat(connectionStore.get("org-b").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
    }

    // ------------------------------------------------------------------ one account per organization

    @Test
    void aGoogleAccountAlreadyConnectedToAnotherOrganizationCannotBeConnectedAgain() {
        organization("org-b", "hr@itap.example"); // same domain on purpose, so only the account rule can block
        callback(startAs("admin-a"));
        String ciphertextOfA = connectionStore.get("org-a").getRefreshTokenCiphertext();

        var outcome = callback(startAs("admin-b"));

        assertThat(outcome.result()).isEqualTo(GoogleConnectResult.ACCOUNT_IN_USE);
        assertThat(connectionStore.get("org-b")).isNull();
        assertThat(connectionStore.get("org-a").getRefreshTokenCiphertext()).isEqualTo(ciphertextOfA);
        assertThat(connectionStore.get("org-a").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
        // Revoking would also break org A, because the grant belongs to the Google account, so it must not happen.
        verify(google, never()).revoke(any());
    }

    @Test
    void theUniqueIndexRaceIsReportedAsAccountInUse() {
        Flow flow = startAs("admin-a");
        when(connections.save(any(OrganizationGoogleConnection.class))).thenThrow(new DuplicateKeyException("race"));

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.ACCOUNT_IN_USE);
    }

    @Test
    void anAccountWhoseOtherConnectionNeedsReauthenticationIsNotConsideredInUse() {
        organization("org-b", "hr@itap.example");
        callback(startAs("admin-a"));
        connectionStore.get("org-a").setStatus(GoogleConnectionStatus.REAUTH_REQUIRED);

        assertThat(callback(startAs("admin-b")).result()).isEqualTo(GoogleConnectResult.CONNECTED);
    }

    // ------------------------------------------------------------------ Google-side failures

    @Test
    void failuresAtGoogleAreMappedAndNothingIsStored() {
        givenGoogleReturns("admin@itap.example", "sub-a");
        nextTokens = new GoogleOAuthClient.TokenResponse("a", REFRESH_TOKEN, "id", "openid email");
        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.MISSING_SCOPE);

        nextTokens = new GoogleOAuthClient.TokenResponse("a", null, "id", "openid email " + GoogleOAuthClient.SCOPE_GMAIL_SEND);
        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.NO_REFRESH_TOKEN);

        nextTokens = new GoogleOAuthClient.TokenResponse("a", REFRESH_TOKEN, "id", "openid email " + GoogleOAuthClient.SCOPE_GMAIL_SEND);
        org.mockito.Mockito.doThrow(new GoogleOAuthException("INVALID_AUDIENCE")).when(google).parseIdentity(any(), any());
        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.INVALID_IDENTITY);

        org.mockito.Mockito.doThrow(new GoogleOAuthException("INVALID_GRANT")).when(google).exchangeCode(anyString(), anyString());
        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.TOKEN_EXCHANGE_FAILED);

        org.mockito.Mockito.doThrow(new IllegalStateException("boom")).when(google).exchangeCode(anyString(), anyString());
        assertThat(callback(startAs("admin-a")).result()).isEqualTo(GoogleConnectResult.CONNECT_FAILED);

        assertThat(connectionStore).isEmpty();
    }

    @Test
    void aFirstAttemptConnectsWhateverFormOrOrderGoogleListsTheGrantedScopesIn() {
        // Google may list the identity scopes by their full addresses, in any order, with extra scopes mixed in.
        List<String> shapes = List.of(
                "openid email " + GoogleOAuthClient.SCOPE_GMAIL_SEND,
                GoogleOAuthClient.SCOPE_GMAIL_SEND + " openid email",
                "https://www.googleapis.com/auth/userinfo.email openid " + GoogleOAuthClient.SCOPE_GMAIL_SEND,
                "  " + GoogleOAuthClient.SCOPE_GMAIL_SEND + "   email  ");
        for (String granted : shapes) {
            connectionStore.clear();
            givenGoogleReturns("admin@itap.example", "sub-a");
            nextTokens = new GoogleOAuthClient.TokenResponse("a", REFRESH_TOKEN, "id", granted);

            assertThat(callback(startAs("admin-a")).result()).as(granted).isEqualTo(GoogleConnectResult.CONNECTED);
            assertThat(connectionStore.get("org-a").getGrantedScopes()).as(granted).contains(GoogleOAuthClient.SCOPE_GMAIL_SEND);
        }
    }

    @Test
    void sendPermissionThatWasNotGrantedIsReportedAsMissingEvenWhenOtherScopesAreAndNothingIsStored() {
        givenGoogleReturns("admin@itap.example", "sub-a");
        for (String granted : new String[] {"openid email", "openid email https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/gmail.send.extra", "", null}) {
            nextTokens = new GoogleOAuthClient.TokenResponse("a", REFRESH_TOKEN, "id", granted);

            assertThat(callback(startAs("admin-a")).result()).as(String.valueOf(granted)).isEqualTo(GoogleConnectResult.MISSING_SCOPE);
        }
        assertThat(connectionStore).isEmpty();
        verify(google, never()).revoke(any());
    }

    @Test
    void theCallbackIsRefusedWhileTheFeatureIsDisabled() {
        Flow flow = startAs("admin-a");
        properties.setEnabled(false);

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.FEATURE_DISABLED);
        verify(google, never()).exchangeCode(anyString(), anyString());
    }

    // ------------------------------------------------------------------ status

    @Test
    void statusReportsNotConnectedConnectedAndReauthRequiredWithoutAnySecret() {
        GoogleConnectionStatusResponse none = service.status("admin-a");
        assertThat(none.status()).isEqualTo("NOT_CONNECTED");
        assertThat(none.featureEnabled()).isTrue();
        assertThat(none.organizationEmailDomain()).isEqualTo("itap.example");

        callback(startAs("admin-a"));
        GoogleConnectionStatusResponse connected = service.status("admin-a");
        assertThat(connected.status()).isEqualTo("CONNECTED");
        assertThat(connected.googleAccountEmail()).isEqualTo("admin@itap.example");
        assertThat(connected.connectedAt()).isNotNull();

        OrganizationGoogleConnection stored = connectionStore.get("org-a");
        stored.setStatus(GoogleConnectionStatus.REAUTH_REQUIRED);
        stored.setLastErrorCode("INVALID_GRANT");
        GoogleConnectionStatusResponse reauth = service.status("admin-a");
        assertThat(reauth.status()).isEqualTo("REAUTH_REQUIRED");
        assertThat(reauth.lastErrorCode()).isEqualTo("INVALID_GRANT");

        assertThat(List.of(GoogleConnectionStatusResponse.class.getRecordComponents()).stream().map(c -> c.getName()))
                .noneMatch(name -> name.toLowerCase().contains("token") || name.toLowerCase().contains("subject")
                        || name.toLowerCase().contains("secret") || name.toLowerCase().contains("cipher"));
    }

    // ------------------------------------------------------------------ disconnect

    @Test
    void disconnectDeletesTheStoredTokenRevokesItAtGoogleAndRecordsAnAuditEntry() {
        callback(startAs("admin-a"));

        GoogleConnectionStatusResponse response = service.disconnect("admin-a");

        OrganizationGoogleConnection stored = connectionStore.get("org-a");
        assertThat(response.status()).isEqualTo("DISCONNECTED");
        assertThat(response.googleAccountEmail()).isNull();
        assertThat(stored.getStatus()).isEqualTo(GoogleConnectionStatus.DISCONNECTED);
        assertThat(stored.getRefreshTokenCiphertext()).isNull();
        assertThat(stored.getRefreshTokenIv()).isNull();
        assertThat(stored.getGoogleSubject()).isNull();
        assertThat(stored.getDisconnectedByUserId()).isEqualTo("admin-a");
        assertThat(stored.getDisconnectedAt()).isNotNull();
        verify(google).revoke(REFRESH_TOKEN);
        verify(audit).record(eq(adminStore.get("admin-a")), eq(AuditActionType.GOOGLE_EMAIL_DISCONNECTED), anyString(),
                anyString(), eq("admin@itap.example"), anyString(), any(), any());
    }

    @Test
    void disconnectStillSucceedsLocallyWhenGoogleCannotBeReached() {
        callback(startAs("admin-a"));
        when(google.revoke(any())).thenReturn(false);

        assertThat(service.disconnect("admin-a").status()).isEqualTo("DISCONNECTED");
        assertThat(connectionStore.get("org-a").getRefreshTokenCiphertext()).isNull();
    }

    @Test
    void disconnectingTwiceOrWhenNeverConnectedIsHarmless() {
        assertThat(service.disconnect("admin-a").status()).isEqualTo("NOT_CONNECTED");

        callback(startAs("admin-a"));
        service.disconnect("admin-a");
        clearInvocations();
        assertThat(service.disconnect("admin-a").status()).isEqualTo("DISCONNECTED");
        verify(google, never()).revoke(any()); // nothing left to revoke the second time
    }

    private void clearInvocations() {
        org.mockito.Mockito.clearInvocations(google);
    }

    @Test
    void anAccountCanBeConnectedAgainAfterDisconnectAndByAnotherOrganizationAfterwards() {
        organization("org-b", "hr@itap.example");
        callback(startAs("admin-a"));
        service.disconnect("admin-a");

        assertThat(callback(startAs("admin-b")).result()).isEqualTo(GoogleConnectResult.CONNECTED);
        assertThat(connectionStore.get("org-b").getStatus()).isEqualTo(GoogleConnectionStatus.CONNECTED);
    }

    // ------------------------------------------------------------------ secrets never reach the logs

    @Test
    void noLogLineEverContainsATokenCodeStateVerifierOrClientSecret() {
        Flow success = startAs("admin-a");
        callback(success);
        callback(success); // replay
        givenGoogleReturns("someone@gmail.com", "sub-x");
        Flow mismatch = startAs("admin-a");
        callback(mismatch);
        org.mockito.Mockito.doThrow(new GoogleOAuthException("INVALID_GRANT")).when(google).exchangeCode(anyString(), anyString());
        Flow failing = startAs("admin-a");
        callback(failing);
        service.disconnect("admin-a");

        String verifier = cipher.decrypt(new TokenCipher.EncryptedValue(stateStore.get(0).getCodeVerifierCiphertext(),
                stateStore.get(0).getCodeVerifierIv(), "v1"), "org-a");
        String everything = String.join("\n", logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(everything).doesNotContain(REFRESH_TOKEN).doesNotContain(AUTH_CODE).doesNotContain(CLIENT_SECRET)
                .doesNotContain("access-token-value").doesNotContain(success.state()).doesNotContain(mismatch.state())
                .doesNotContain(failing.state()).doesNotContain(verifier).doesNotContain("someone@gmail.com");
        assertThat(logs.list.stream().filter(e -> e.getThrowableProxy() != null)).isEmpty();
    }

    // ------------------------------------------------------------------ Phase 5: callback and start hardening

    @Test
    void startIsRateLimitedPerUserWithoutAffectingOtherAdmins() {
        for (int i = 0; i < 10; i++) startAs("admin-a");

        assertThatThrownBy(() -> service.start("admin-a")).isInstanceOf(BusinessException.class).hasMessageContaining("Too many");
        assertThat(stateStore).hasSize(10);
        assertThat(service.start("admin-b").authorizationUrl()).startsWith("https://google.test/auth?state=");
    }

    @Test
    void oversizedCodeOrErrorValuesAreRejectedButStillBurnTheState() {
        Flow flow = startAs("admin-a");
        assertThat(service.handleCallback("c".repeat(3000), flow.state(), null).result()).isEqualTo(GoogleConnectResult.AUTHORIZATION_FAILED);
        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.STATE_ALREADY_USED);

        Flow second = startAs("admin-a");
        assertThat(service.handleCallback(null, second.state(), "e".repeat(500)).result()).isEqualTo(GoogleConnectResult.AUTHORIZATION_FAILED);

        verify(google, never()).exchangeCode(anyString(), anyString());
        assertThat(connectionStore).isEmpty();
    }

    @Test
    void everyFailureResultIsAGenericCodeThatNamesNoTokenAddressOrDetail() {
        Flow flow = startAs("admin-a");
        givenGoogleReturns("someone@gmail.com", "sub-x");
        var outcome = callback(flow);

        assertThat(outcome.result().name()).matches("[A-Z_]+");
        assertThat(outcome.toString()).doesNotContain(REFRESH_TOKEN).doesNotContain(AUTH_CODE).doesNotContain("someone@gmail.com");
        for (GoogleConnectResult result : GoogleConnectResult.values()) {
            assertThat(result.name()).doesNotContain("@").doesNotContain(" ");
        }
    }

    @Test
    void aReplayedStateNeverRevealsWhetherAnOrganizationExists() {
        Flow flow = startAs("admin-a");
        callback(flow);

        assertThat(callback(flow).result()).isEqualTo(GoogleConnectResult.STATE_ALREADY_USED);
        assertThat(service.handleCallback(AUTH_CODE, "x".repeat(43), null).result()).isEqualTo(GoogleConnectResult.INVALID_STATE);
    }
}
