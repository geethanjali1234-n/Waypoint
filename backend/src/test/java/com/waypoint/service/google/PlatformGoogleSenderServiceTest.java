package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import com.waypoint.entity.PlatformGoogleSenderConnection;
import com.waypoint.repository.PlatformGoogleSenderConnectionRepository;
import com.waypoint.security.TokenCipher;
import com.waypoint.security.TokenCipherException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformGoogleSenderServiceTest {

    private static final String EXPECTED_ADDRESS = GoogleOAuthProperties.PLATFORM_SENDER_ADDRESS;
    private static final String REFRESH_TOKEN = "platform-refresh-token-secret";

    private final GoogleOAuthProperties properties = new GoogleOAuthProperties();
    private final TokenCipher cipher = new TokenCipher(key(), "v1");
    private final GoogleOAuthClient google = mock(GoogleOAuthClient.class);
    private final PlatformGoogleSenderConnectionRepository connections = mock(PlatformGoogleSenderConnectionRepository.class);

    private PlatformGoogleSenderService service;
    private PlatformGoogleSenderConnection stored;

    @BeforeEach
    void setUp() {
        properties.setPlatformSenderEnabled(true);
        properties.setPlatformSenderAddress(EXPECTED_ADDRESS);
        service = new PlatformGoogleSenderService(properties, cipher, google, connections);
        when(connections.findById(PlatformGoogleSenderConnection.ID)).thenAnswer(call -> Optional.ofNullable(stored));
        when(connections.save(any(PlatformGoogleSenderConnection.class))).thenAnswer(call -> {
            stored = call.getArgument(0);
            return stored;
        });
    }

    @Test
    void storesOnlyEncryptedRefreshTokenForTheExactDedicatedGoogleAccount() {
        service.storeAuthorizedConnection(identity("WayPointMyHr@Gmail.com"), REFRESH_TOKEN, "admin-1");

        assertThat(stored.getId()).isEqualTo(PlatformGoogleSenderConnection.ID);
        assertThat(stored.getGoogleAccountEmail()).isEqualTo(EXPECTED_ADDRESS);
        assertThat(stored.getRefreshTokenCiphertext()).isNotBlank().doesNotContain(REFRESH_TOKEN);
        assertThat(stored.getRefreshTokenIv()).isNotBlank();
        assertThat(stored.getEncryptionKeyId()).isEqualTo("v1");
        assertThat(stored.toString()).doesNotContain(REFRESH_TOKEN).doesNotContain(stored.getRefreshTokenCiphertext());
        TokenCipher.EncryptedValue encrypted = new TokenCipher.EncryptedValue(stored.getRefreshTokenCiphertext(),
                stored.getRefreshTokenIv(), stored.getEncryptionKeyId());
        assertThat(cipher.decrypt(encrypted, PlatformGoogleSenderConnection.ID)).isEqualTo(REFRESH_TOKEN);
        assertThatThrownBy(() -> cipher.decrypt(encrypted, "org-a")).isInstanceOf(TokenCipherException.class);
    }

    @Test
    void rejectsAnyGoogleAccountOtherThanTheConfiguredDedicatedSenderWithoutStoringItsToken() {
        assertThatThrownBy(() -> service.storeAuthorizedConnection(identity("other@gmail.com"), REFRESH_TOKEN, "admin-1"))
                .isInstanceOf(PlatformGoogleSenderService.Unavailable.class)
                .extracting(failure -> ((PlatformGoogleSenderService.Unavailable) failure).code())
                .isEqualTo("ACCOUNT_EMAIL_MISMATCH");

        verify(connections, never()).save(any());
    }

    @Test
    void refreshesTheEncryptedTokenOnceAndKeepsTheAccessTokenOnlyInMemory() {
        connect();
        when(google.refreshAccessToken(REFRESH_TOKEN)).thenReturn(new GoogleOAuthClient.AccessToken("access-token-secret", 3600));

        assertThat(service.accessToken()).isEqualTo("access-token-secret");
        assertThat(service.accessToken()).isEqualTo("access-token-secret");

        verify(google, times(1)).refreshAccessToken(REFRESH_TOKEN);
        assertThat(stored.toString()).doesNotContain("access-token-secret");
        assertThat(stored.getRefreshTokenCiphertext()).doesNotContain("access-token-secret");
    }

    @Test
    void refusesAStoredConnectionWhoseAccountNoLongerMatchesTheConfiguredSender() {
        connect();
        stored.setGoogleAccountEmail("wrong@gmail.com");

        assertThatThrownBy(() -> service.accessToken())
                .isInstanceOf(PlatformGoogleSenderService.Unavailable.class)
                .extracting(failure -> ((PlatformGoogleSenderService.Unavailable) failure).code())
                .isEqualTo("ACCOUNT_EMAIL_MISMATCH");
        verify(google, never()).refreshAccessToken(REFRESH_TOKEN);
    }

    @Test
    void recordsAProviderRefreshFailureWithoutPersistingAnAccessToken() {
        connect();
        when(google.refreshAccessToken(REFRESH_TOKEN)).thenThrow(new GoogleOAuthException("INVALID_GRANT"));

        assertThatThrownBy(() -> service.accessToken())
                .isInstanceOf(PlatformGoogleSenderService.Unavailable.class)
                .extracting(failure -> ((PlatformGoogleSenderService.Unavailable) failure).code())
                .isEqualTo("TOKEN_UNAVAILABLE");

        assertThat(stored.getLastErrorCode()).isEqualTo("INVALID_GRANT");
        assertThat(stored.getLastErrorAt()).isNotNull();
        assertThat(stored.toString()).doesNotContain(REFRESH_TOKEN);
    }

    @Test
    void featureMustBeEnabledBeforeTokensCanBeStoredOrRefreshed() {
        properties.setPlatformSenderEnabled(false);

        assertThatThrownBy(() -> service.storeAuthorizedConnection(identity(EXPECTED_ADDRESS), REFRESH_TOKEN, "admin-1"))
                .isInstanceOf(PlatformGoogleSenderService.Unavailable.class)
                .extracting(failure -> ((PlatformGoogleSenderService.Unavailable) failure).code())
                .isEqualTo("FEATURE_DISABLED");
        verify(connections, never()).save(any());
    }

    @Test
    void rejectsConfigurationForAnyPlatformMailboxOtherThanWaypointMyHr() {
        properties.setPlatformSenderAddress("another-sender@gmail.com");

        assertThatThrownBy(() -> service.storeAuthorizedConnection(identity("another-sender@gmail.com"), REFRESH_TOKEN, "admin-1"))
                .isInstanceOf(PlatformGoogleSenderService.Unavailable.class)
                .extracting(failure -> ((PlatformGoogleSenderService.Unavailable) failure).code())
                .isEqualTo("SENDER_ADDRESS_MISMATCH");
        verify(connections, never()).save(any());
    }

    private void connect() {
        service.storeAuthorizedConnection(identity(EXPECTED_ADDRESS), REFRESH_TOKEN, "admin-1");
    }

    private static GoogleOAuthClient.Identity identity(String email) {
        return new GoogleOAuthClient.Identity("google-subject-1", email, null);
    }

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
