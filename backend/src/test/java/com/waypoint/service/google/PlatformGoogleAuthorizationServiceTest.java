package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.dto.myhr.GoogleConnectStartResponse;
import com.waypoint.entity.GoogleOAuthState;
import com.waypoint.entity.PlatformGoogleSenderConnection;
import com.waypoint.enums.UserRole;
import com.waypoint.repository.GoogleOAuthStateRepository;
import com.waypoint.repository.PlatformGoogleSenderConnectionRepository;
import com.waypoint.security.TokenCipher;
import com.waypoint.security.WaypointAdminAuthenticatedUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PlatformGoogleAuthorizationServiceTest {
    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private final TokenCipher cipher = new TokenCipher(key(), "v1");
    private final GoogleOAuthClient google = mock(GoogleOAuthClient.class);
    private final GoogleOAuthStateRepository states = mock(GoogleOAuthStateRepository.class);
    private final PlatformGoogleSenderConnectionRepository connections = mock(PlatformGoogleSenderConnectionRepository.class);
    private final PlatformGoogleSenderService sender = mock(PlatformGoogleSenderService.class);
    private final WaypointAdminAuthenticatedUserService admins = mock(WaypointAdminAuthenticatedUserService.class);
    private final GoogleRateLimits limits = mock(GoogleRateLimits.class);
    private final List<GoogleOAuthState> stateStore = new ArrayList<>();
    private PlatformGoogleAuthorizationService service;

    @BeforeEach void setUp() {
        properties.setPlatformSenderEnabled(true); properties.setPlatformSenderAddress(GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS);
        properties.setClientId("client-id"); properties.setRedirectUri("https://api.example.test/api/oauth/google/callback");
        service = new PlatformGoogleAuthorizationService(properties, cipher, google, states, connections, sender, admins, limits);
        when(admins.require("admin-1")).thenReturn(new WaypointAdminAuthenticatedUserService.AuthenticatedWaypointAdmin("admin-1", "WPADMIN", "admin@example.test", UserRole.WAYPOINT_ADMIN, false));
        when(limits.allowConnectAttempt("admin-1")).thenReturn(true);
        when(google.buildAuthorizationUrl(anyString(), anyString(), anyString()))
                .thenAnswer(call -> "https://accounts.example.test/auth?state=" + call.getArgument(0)
                        + "&login_hint=" + java.net.URLEncoder.encode(call.getArgument(2), java.nio.charset.StandardCharsets.UTF_8));
        when(states.save(any())).thenAnswer(call -> { stateStore.add(call.getArgument(0)); return call.getArgument(0); });
        when(states.findByStateHash(anyString())).thenAnswer(call -> stateStore.stream().filter(s -> s.getStateHash().equals(call.getArgument(0))).findFirst());
        when(states.consume(anyString(), any())).thenAnswer(call -> { LocalDateTime now = call.getArgument(1); for (GoogleOAuthState s : stateStore) if (s.getStateHash().equals(call.getArgument(0)) && s.getUsedAt() == null && s.getExpiresAt().isAfter(now)) { s.setUsedAt(now); return 1L; } return 0L; });
    }

    @Test void startCreatesServerSidePlatformStateWithEncryptedPkceAndAdminOnlyOwnership() {
        GoogleConnectStartResponse response = service.start("admin-1");
        assertThat(response.authorizationUrl()).contains("login_hint=waypointmyhr%40gmail.com");
        GoogleOAuthState state = stateStore.getFirst();
        assertThat(state.isPlatformSender()).isTrue();
        assertThat(state.getOrganizationId()).isNull();
        assertThat(state.getCodeVerifierCiphertext()).doesNotContain("refresh");
        assertThat(service.ownsState(extractState(response.authorizationUrl()))).isTrue();
    }

    @Test void callbackRoutesOnlyItsPlatformStateAndSavesExactAccountThroughPhaseOneService() {
        String raw = startState();
        when(google.exchangeCode(anyString(), anyString())).thenReturn(new GoogleOAuthClient.TokenResponse("access", "refresh-secret", "id", "openid email " + GoogleOAuthClient.SCOPE_GMAIL_SEND));
        GoogleOAuthClient.Identity identity = new GoogleOAuthClient.Identity("sub", GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS, null);
        when(google.parseIdentity(anyString(), any())).thenReturn(identity);
        assertThat(service.handleCallback("code", raw, null)).isEqualTo(GoogleConnectResult.CONNECTED);
        verify(sender).storeAuthorizedConnection(identity, "refresh-secret", "admin-1");
    }

    @Test void denialExpiredStateMissingTokenAndWrongAccountAreSafeAndNeverStored() {
        String denied = startState();
        assertThat(service.handleCallback(null, denied, "access_denied")).isEqualTo(GoogleConnectResult.ACCESS_DENIED);
        assertThat(service.handleCallback("code", denied, null)).isEqualTo(GoogleConnectResult.STATE_ALREADY_USED);
        String noToken = startState();
        when(google.exchangeCode(anyString(), anyString())).thenReturn(new GoogleOAuthClient.TokenResponse("a", null, "id", GoogleOAuthClient.SCOPE_GMAIL_SEND));
        assertThat(service.handleCallback("code", noToken, null)).isEqualTo(GoogleConnectResult.NO_REFRESH_TOKEN);
        String wrong = startState();
        when(google.exchangeCode(anyString(), anyString())).thenReturn(new GoogleOAuthClient.TokenResponse("a", "refresh", "id", GoogleOAuthClient.SCOPE_GMAIL_SEND));
        when(google.parseIdentity(anyString(), any())).thenReturn(new GoogleOAuthClient.Identity("sub", "wrong@gmail.com", null));
        doThrow(new PlatformGoogleSenderService.Unavailable("ACCOUNT_EMAIL_MISMATCH")).when(sender).storeAuthorizedConnection(any(), anyString(), anyString());
        assertThat(service.handleCallback("code", wrong, null)).isEqualTo(GoogleConnectResult.ACCOUNT_EMAIL_MISMATCH);
        verify(google).revoke("refresh");
    }

    @Test void statusRequiresWaypointAdminAndNeverReturnsCredentialFields() {
        when(connections.findById(PlatformGoogleSenderConnection.ID)).thenReturn(Optional.of(PlatformGoogleSenderConnection.builder().id(PlatformGoogleSenderConnection.ID).googleAccountEmail(GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS).refreshTokenCiphertext("cipher").connectedAt(LocalDateTime.now()).build()));
        var status = service.status("admin-1");
        assertThat(status.authorized()).isTrue(); assertThat(status.googleAccountEmail()).isEqualTo(GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS);
        assertThat(status.getClass().getRecordComponents()).extracting(c -> c.getName()).doesNotContain("refreshToken", "refreshTokenCiphertext", "accessToken");
    }

    private String startState() { return extractState(service.start("admin-1").authorizationUrl()); }
    private static String extractState(String url) { return java.net.URI.create(url).getQuery().replaceAll(".*(?:^|&)state=([^&]+).*", "$1"); }
    private static String key() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); return Base64.getEncoder().encodeToString(bytes); }
}
