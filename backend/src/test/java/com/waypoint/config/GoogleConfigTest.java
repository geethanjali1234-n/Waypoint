package com.waypoint.config;

import com.waypoint.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleConfigTest {

    private static final String SECRET_VALUE = "test-client-secret-value";

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(GoogleConfig.class);

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Test
    void featureIsOffByDefaultAndStartsWithNoConfigurationAtAll() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(GoogleOAuthProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(TokenCipher.class).isConfigured()).isFalse();
        });
    }

    @Test
    void enablingWithoutTheRestOfTheConfigurationStopsStartupNamingTheVariable() {
        runner.withPropertyValues("waypoint.google.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(IllegalStateException.class);
            assertThat(rootMessage(context.getStartupFailure())).contains("GOOGLE_OAUTH_CLIENT_ID");
        });
    }

    @Test
    void everyRequiredValueIsCheckedWhenEnabled() {
        String[][] missingOne = {
                {"GOOGLE_OAUTH_CLIENT_SECRET", "waypoint.google.client-id=id", "waypoint.google.redirect-uri=https://api.example.test/api/oauth/google/callback", "waypoint.app.frontend-base-url=https://app.example.test", "waypoint.google.token-encryption-key=" + key()},
                {"GOOGLE_OAUTH_REDIRECT_URI", "waypoint.google.client-id=id", "waypoint.google.client-secret=" + SECRET_VALUE, "waypoint.google.token-encryption-key=" + key()},
                {"GOOGLE_TOKEN_ENCRYPTION_KEY", "waypoint.google.client-id=id", "waypoint.google.client-secret=" + SECRET_VALUE, "waypoint.google.redirect-uri=https://api.example.test/api/oauth/google/callback", "waypoint.app.frontend-base-url=https://app.example.test"},
        };
        for (String[] row : missingOne) {
            String[] properties = new String[row.length];
            properties[0] = "waypoint.google.enabled=true";
            System.arraycopy(row, 1, properties, 1, row.length - 1);
            runner.withPropertyValues(properties).run(context -> {
                assertThat(context).hasFailed();
                assertThat(rootMessage(context.getStartupFailure())).contains(row[0]).doesNotContain(SECRET_VALUE);
            });
        }
    }

    @Test
    void aMalformedKeyStopsStartupWhenEnabledWithoutEchoingTheKey() {
        String badKey = Base64.getEncoder().encodeToString(new byte[7]);
        runner.withPropertyValues("waypoint.google.enabled=true", "waypoint.google.client-id=id",
                "waypoint.google.client-secret=" + SECRET_VALUE, "waypoint.google.redirect-uri=https://api.example.test/api/oauth/google/callback", "waypoint.app.frontend-base-url=https://app.example.test",
                "waypoint.google.token-encryption-key=" + badKey).run(context -> {
            assertThat(context).hasFailed();
            assertThat(rootMessage(context.getStartupFailure())).contains("GOOGLE_TOKEN_ENCRYPTION_KEY")
                    .doesNotContain(badKey).doesNotContain(SECRET_VALUE);
        });
    }

    @Test
    void aFullyConfiguredFeatureProvidesAWorkingCipher() {
        runner.withPropertyValues("waypoint.google.enabled=true", "waypoint.google.client-id=id",
                "waypoint.google.client-secret=" + SECRET_VALUE, "waypoint.google.redirect-uri=https://api.example.test/api/oauth/google/callback", "waypoint.app.frontend-base-url=https://app.example.test",
                "waypoint.google.token-encryption-key=" + key(), "waypoint.google.token-encryption-key-id=v7").run(context -> {
            assertThat(context).hasNotFailed();
            TokenCipher cipher = context.getBean(TokenCipher.class);
            assertThat(cipher.isConfigured()).isTrue();
            assertThat(cipher.keyId()).isEqualTo("v7");
            assertThat(cipher.decrypt(cipher.encrypt("token", "org-1"), "org-1")).isEqualTo("token");
        });
    }

    @Test
    void anInvalidKeyIsIgnoredWithoutFailingStartupWhileTheFeatureIsDisabled() {
        runner.withPropertyValues("waypoint.google.token-encryption-key=not-a-valid-key").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TokenCipher.class).isConfigured()).isFalse();
        });
    }

    @Test
    void propertiesDoNotExposeSecretsThroughToString() {
        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setClientSecret(SECRET_VALUE);
        properties.setTokenEncryptionKey("key-material");

        assertThat(properties.toString()).doesNotContain(SECRET_VALUE).doesNotContain("key-material");
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) root = root.getCause();
        return root.getMessage();
    }
}
