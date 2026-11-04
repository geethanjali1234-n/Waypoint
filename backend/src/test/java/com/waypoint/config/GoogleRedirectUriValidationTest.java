package com.waypoint.config;

import com.waypoint.security.TokenCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GOOGLE_OAUTH_REDIRECT_URI is sent to Google unchanged and must exactly match the OAuth client, and the backend only
 * serves one callback path. Startup refuses a value that can never work, with a message that names the rule but never
 * echoes the value.
 */
class GoogleRedirectUriValidationTest {

    private static final String CALLBACK = "/api/oauth/google/callback";
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(GoogleConfig.class);

    private static String key() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static GoogleOAuthProperties enabledWith(String redirectUri) {
        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setEnabled(true);
        properties.setClientId("client-id");
        properties.setClientSecret("client-secret-value");
        properties.setRedirectUri(redirectUri);
        properties.setTokenEncryptionKey(key());
        return properties;
    }

    // ------------------------------------------------------------------ accepted

    @ParameterizedTest
    @ValueSource(strings = {
            "https://waypoint-backend.example.com" + CALLBACK,
            "https://api.example.co.uk" + CALLBACK,
            "https://api.example.com:8443" + CALLBACK,
            "HTTPS://API.EXAMPLE.COM" + CALLBACK,
            "http://localhost:8081" + CALLBACK,
            "http://localhost" + CALLBACK,
            "http://127.0.0.1:8081" + CALLBACK,
            "https://localhost:8443" + CALLBACK,
            "http://localhost:5173" + CALLBACK,
    })
    void acceptsTheExactCallbackPathOnHttpsOrLocalhost(String redirectUri) {
        assertThat(GoogleOAuthProperties.redirectUriProblem(redirectUri)).isNull();
        enabledWith(redirectUri).requireCompleteWhenEnabled();
    }

    // ------------------------------------------------------------------ rejected

    @ParameterizedTest
    @ValueSource(strings = {
            "https://api.example.com/api/oauth/google/callback/",      // trailing slash
            "https://api.example.com/API/oauth/google/callback",       // case-sensitive path
            "https://api.example.com/oauth/google/callback",           // missing /api
            "https://api.example.com/api/oauth/google",                // incomplete
            "https://api.example.com/prefix/api/oauth/google/callback",// no context path is served
            "https://api.example.com",                                 // no path at all
            "https://api.example.com/cb",                              // arbitrary path
            "https://api.example.com/api/oauth/google/callback?x=1",   // query string
            "https://api.example.com/api/oauth/google/callback#frag",  // fragment
            "https://*.example.com/api/oauth/google/callback",         // wildcard
            "https://user:pass@api.example.com/api/oauth/google/callback", // credentials
    })
    void rejectsAnythingThatIsNotExactlyTheCallback(String redirectUri) {
        assertThat(GoogleOAuthProperties.redirectUriProblem(redirectUri)).isNotNull();
        assertThatThrownBy(() -> enabledWith(redirectUri).requireCompleteWhenEnabled())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_OAUTH_REDIRECT_URI is invalid")
                .hasMessageContaining(CALLBACK);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://api.example.com" + CALLBACK,        // http on a public host
            "http://192.168.1.20:8081" + CALLBACK,      // http on a LAN address
            "https://203.0.113.10" + CALLBACK,          // raw IP address
            "https://[2001:db8::1]" + CALLBACK,         // raw IPv6 address
            "https://backend" + CALLBACK,               // not a full public domain name
            "ftp://api.example.com" + CALLBACK,         // not http(s)
            "api.example.com" + CALLBACK,               // no scheme
            "/api/oauth/google/callback",               // relative
            "https://" + CALLBACK,                      // no host
            "not a url at all",
    })
    void rejectsNonLocalHttpIpAddressesAndMalformedValues(String redirectUri) {
        assertThat(GoogleOAuthProperties.redirectUriProblem(redirectUri)).isNotNull();
        assertThatThrownBy(() -> enabledWith(redirectUri).requireCompleteWhenEnabled())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GOOGLE_OAUTH_REDIRECT_URI is invalid");
    }

    @Test
    void theErrorNamesTheRuleAndExpectedShapeButNeverEchoesTheValue() {
        String secretLooking = "https://user:topsecret@api.example.com/api/oauth/google/callback?token=abc123";

        assertThatThrownBy(() -> enabledWith(secretLooking).requireCompleteWhenEnabled())
                .isInstanceOf(IllegalStateException.class)
                .satisfies(failure -> assertThat(failure.getMessage())
                        .doesNotContain("topsecret").doesNotContain("abc123").doesNotContain("user:")
                        .doesNotContain("client-secret-value")
                        .contains("https://<your-backend-host>" + CALLBACK).contains("http://localhost:8081" + CALLBACK)
                        .contains("authorized redirect URI"));
    }

    @Test
    void anEmptyRedirectUriIsStillReportedAsMissingNotAsMalformed() {
        assertThatThrownBy(() -> enabledWith(" ").requireCompleteWhenEnabled())
                .hasMessageContaining("GOOGLE_OAUTH_REDIRECT_URI must be configured");
    }

    @Test
    void nothingIsCheckedWhileTheFeatureIsDisabled() {
        GoogleOAuthProperties disabled = new GoogleOAuthProperties();
        disabled.setRedirectUri("totally wrong");

        disabled.requireCompleteWhenEnabled();
        disabled.validateFrontendBaseUrl("also wrong");
    }

    // ------------------------------------------------------------------ the frontend address used for the return redirect

    @Test
    void aPublicRedirectUriRequiresAPublicHttpsFrontendAddress() {
        GoogleOAuthProperties properties = enabledWith("https://api.example.com" + CALLBACK);

        properties.validateFrontendBaseUrl("https://app.example.com");
        properties.validateFrontendBaseUrl("https://app.example.com/");
        assertThatThrownBy(() -> properties.validateFrontendBaseUrl("http://localhost:5173"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_FRONTEND_URL");
        assertThatThrownBy(() -> properties.validateFrontendBaseUrl("http://app.example.com"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_FRONTEND_URL");
        assertThatThrownBy(() -> properties.validateFrontendBaseUrl("https://127.0.0.1:5173"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("APP_FRONTEND_URL");
    }

    @Test
    void aLocalRedirectUriAllowsTheLocalFrontend() {
        GoogleOAuthProperties properties = enabledWith("http://localhost:8081" + CALLBACK);

        properties.validateFrontendBaseUrl("http://localhost:5173");
        properties.validateFrontendBaseUrl("http://127.0.0.1:5173");
    }

    @Test
    void aMalformedFrontendAddressIsRejectedWithoutEchoingIt() {
        GoogleOAuthProperties properties = enabledWith("http://localhost:8081" + CALLBACK);

        for (String bad : new String[]{"", "   ", "not a url", "ftp://app.example.com", "https://user:pw-secret@app.example.com",
                "https://app.example.com?x=1", "https://app.example.com#frag"}) {
            assertThatThrownBy(() -> properties.validateFrontendBaseUrl(bad))
                    .isInstanceOf(IllegalStateException.class)
                    .satisfies(failure -> assertThat(failure.getMessage()).contains("APP_FRONTEND_URL").doesNotContain("pw-secret"));
        }
    }

    // ------------------------------------------------------------------ at application startup

    @Test
    void startupFailsClearlyWhenTheRedirectUriIsWrong() {
        runner.withPropertyValues("waypoint.google.enabled=true", "waypoint.google.client-id=id", "waypoint.google.client-secret=client-secret-value",
                "waypoint.google.redirect-uri=https://api.example.com/oauth/callback", "waypoint.google.token-encryption-key=" + key(),
                "waypoint.app.frontend-base-url=https://app.example.com").run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) root = root.getCause();
            assertThat(root.getMessage()).contains("GOOGLE_OAUTH_REDIRECT_URI is invalid").contains(CALLBACK)
                    .doesNotContain("client-secret-value").doesNotContain("api.example.com/oauth/callback");
        });
    }

    @Test
    void startupFailsClearlyWhenAPublicDeploymentLeavesTheFrontendAddressAtItsLocalDefault() {
        runner.withPropertyValues("waypoint.google.enabled=true", "waypoint.google.client-id=id", "waypoint.google.client-secret=client-secret-value",
                "waypoint.google.redirect-uri=https://api.example.com" + CALLBACK, "waypoint.google.token-encryption-key=" + key()).run(context -> {
            assertThat(context).hasFailed();
            Throwable root = context.getStartupFailure();
            while (root.getCause() != null) root = root.getCause();
            assertThat(root.getMessage()).contains("APP_FRONTEND_URL");
        });
    }

    @Test
    void startupSucceedsWithCorrectPublicOrLocalConfigurationAndWhenDisabled() {
        runner.withPropertyValues("waypoint.google.enabled=true", "waypoint.google.client-id=id", "waypoint.google.client-secret=client-secret-value",
                "waypoint.google.redirect-uri=https://api.example.com" + CALLBACK, "waypoint.google.token-encryption-key=" + key(),
                "waypoint.app.frontend-base-url=https://app.example.com").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TokenCipher.class).isConfigured()).isTrue();
        });
        runner.withPropertyValues("waypoint.google.enabled=true", "waypoint.google.client-id=id", "waypoint.google.client-secret=client-secret-value",
                "waypoint.google.redirect-uri=http://localhost:8081" + CALLBACK, "waypoint.google.token-encryption-key=" + key()).run(context -> {
            assertThat(context).hasNotFailed();
        });
        runner.run(context -> assertThat(context).hasNotFailed()); // default: disabled, nothing configured
    }
}
