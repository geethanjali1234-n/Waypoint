package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleOAuthClientTest {

    private static final String CLIENT_ID = "test-client-id.apps.example";
    private static final String CLIENT_SECRET = "test-client-secret-value";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");

    private MockRestServiceServer server;
    private GoogleOAuthClient client;

    @BeforeEach
    void setUp() {
        GoogleOAuthProperties properties = new GoogleOAuthProperties();
        properties.setClientId(CLIENT_ID);
        properties.setClientSecret(CLIENT_SECRET);
        properties.setRedirectUri("https://api.example.test/api/oauth/google/callback");
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new GoogleOAuthClient(builder.build(), properties);
    }

    @Test
    void authorizationUrlRequestsMinimalScopesOfflineAccessAndPkceButNeverTheSecret() {
        String url = client.buildAuthorizationUrl("state-value", "challenge-value", "hr@itap.example");

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?")
                .contains("client_id=" + CLIENT_ID)
                .contains("response_type=code")
                .contains("state=state-value")
                .contains("code_challenge=challenge-value")
                .contains("code_challenge_method=S256")
                .contains("access_type=offline")
                .contains("prompt=consent")
                .contains("redirect_uri=https://api.example.test/api/oauth/google/callback")
                .contains("scope=openid%20email%20https://www.googleapis.com/auth/gmail.send")
                .contains("login_hint=hr@itap.example")
                .doesNotContain(CLIENT_SECRET);
        assertThat(GoogleOAuthClient.REQUESTED_SCOPES)
                .containsExactly("openid", "email", "https://www.googleapis.com/auth/gmail.send");
    }

    @Test
    void exchangesTheCodeWithTheVerifierAndParsesTheResponse() {
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=authorization_code"),
                        org.hamcrest.Matchers.containsString("code=the-code"),
                        org.hamcrest.Matchers.containsString("code_verifier=the-verifier"),
                        org.hamcrest.Matchers.containsString("client_id=" + CLIENT_ID),
                        org.hamcrest.Matchers.containsString("client_secret=" + CLIENT_SECRET))))
                .andRespond(withSuccess("""
                        {"access_token":"at-1","refresh_token":"rt-1","id_token":"id-1",
                         "scope":"openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/gmail.send","expires_in":3599}
                        """, MediaType.APPLICATION_JSON));

        GoogleOAuthClient.TokenResponse tokens = client.exchangeCode("the-code", "the-verifier");

        assertThat(tokens.refreshToken()).isEqualTo("rt-1");
        assertThat(tokens.idToken()).isEqualTo("id-1");
        assertThat(tokens.grantsScope(GoogleOAuthClient.SCOPE_GMAIL_SEND)).isTrue();
        assertThat(tokens.scopes()).hasSize(3);
        assertThat(tokens.toString()).doesNotContain("rt-1").doesNotContain("at-1");
        server.verify();
    }

    @Test
    void aMissingGmailSendScopeIsReportedAsNotGranted() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("{\"refresh_token\":\"rt\",\"id_token\":\"x\",\"scope\":\"openid email\"}", MediaType.APPLICATION_JSON));

        assertThat(client.exchangeCode("c", "v").grantsScope(GoogleOAuthClient.SCOPE_GMAIL_SEND)).isFalse();
    }

    @Test
    void googleRejectionsBecomeAShortCodeWithoutTheResponseBodyCodeOrSecret() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Bad code secret-code-123\"}"));

        assertThatThrownBy(() -> client.exchangeCode("secret-code-123", "verifier"))
                .isInstanceOf(GoogleOAuthException.class)
                .satisfies(failure -> {
                    assertThat(((GoogleOAuthException) failure).errorCode()).isEqualTo("INVALID_GRANT");
                    assertThat(failure.getMessage()).doesNotContain("secret-code-123").doesNotContain(CLIENT_SECRET);
                });
    }

    @Test
    void unexpectedStatusesAndMalformedBodiesAreHandled() {
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withStatus(HttpStatus.BAD_GATEWAY));
        assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(GoogleOAuthException.class)
                .extracting(f -> ((GoogleOAuthException) f).errorCode()).isEqualTo("HTTP_502");

        server.reset();
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess("not json at all", MediaType.TEXT_PLAIN));
        assertThatThrownBy(() -> client.exchangeCode("c", "v")).isInstanceOf(GoogleOAuthException.class)
                .extracting(f -> ((GoogleOAuthException) f).errorCode()).isEqualTo("MALFORMED_RESPONSE");
    }

    @Test
    void revokeReportsSuccessAndNeverThrows() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string("token=rt-1")).andRespond(withSuccess());
        assertThat(client.revoke("rt-1")).isTrue();

        server.reset();
        server.expect(requestTo("https://oauth2.googleapis.com/revoke")).andRespond(withStatus(HttpStatus.BAD_REQUEST));
        assertThat(client.revoke("rt-1")).isFalse();

        assertThat(client.revoke(null)).isFalse();
        assertThat(client.revoke(" ")).isFalse();
    }

    @Test
    void readsAValidIdentityAndLowercasesTheEmail() {
        String token = idToken("{\"iss\":\"https://accounts.google.com\",\"aud\":\"" + CLIENT_ID + "\",\"exp\":" + (NOW.getEpochSecond() + 600)
                + ",\"sub\":\"1234\",\"email\":\"Admin@ITAP.example\",\"email_verified\":true,\"hd\":\"itap.example\"}");

        GoogleOAuthClient.Identity identity = client.parseIdentity(token, NOW);

        assertThat(identity.subject()).isEqualTo("1234");
        assertThat(identity.email()).isEqualTo("admin@itap.example");
        assertThat(identity.hostedDomain()).isEqualTo("itap.example");
        assertThat(identity.toString()).doesNotContain("admin@itap.example");
    }

    @Test
    void rejectsIdentitiesThatAreForeignExpiredUnverifiedOrIncomplete() {
        String good = "\"iss\":\"accounts.google.com\",\"aud\":\"" + CLIENT_ID + "\",\"exp\":" + (NOW.getEpochSecond() + 600)
                + ",\"sub\":\"1\",\"email\":\"a@itap.example\",\"email_verified\":true";

        assertThat(client.parseIdentity(idToken("{" + good + "}"), NOW).subject()).isEqualTo("1");
        assertRejected("{" + good.replace("accounts.google.com", "evil.example") + "}", "INVALID_ISSUER");
        assertRejected("{" + good.replace(CLIENT_ID, "another-client") + "}", "INVALID_AUDIENCE");
        assertRejected("{" + good.replace(String.valueOf(NOW.getEpochSecond() + 600), String.valueOf(NOW.getEpochSecond() - 1)) + "}", "ID_TOKEN_EXPIRED");
        assertRejected("{" + good.replace("\"email_verified\":true", "\"email_verified\":false") + "}", "EMAIL_NOT_VERIFIED");
        assertRejected("{" + good.replace("\"sub\":\"1\",", "") + "}", "INCOMPLETE_IDENTITY");
        assertRejected("{" + good.replace("\"email\":\"a@itap.example\",", "") + "}", "INCOMPLETE_IDENTITY");
        assertThatThrownBy(() -> client.parseIdentity("not-a-jwt", NOW)).isInstanceOf(GoogleOAuthException.class);
        assertThatThrownBy(() -> client.parseIdentity(null, NOW)).isInstanceOf(GoogleOAuthException.class);
        assertThatThrownBy(() -> client.parseIdentity("a.%%%.c", NOW)).isInstanceOf(GoogleOAuthException.class);
    }

    private void assertRejected(String claims, String code) {
        assertThatThrownBy(() -> client.parseIdentity(idToken(claims), NOW))
                .isInstanceOf(GoogleOAuthException.class)
                .extracting(failure -> ((GoogleOAuthException) failure).errorCode()).isEqualTo(code);
    }

    private static String idToken(String claimsJson) {
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + encoder.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8)) + ".signature";
    }

    // ------------------------------------------------------------------ Phase 4: refresh and send

    @Test
    void refreshesAnAccessTokenFromTheStoredRefreshToken() {
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("grant_type=refresh_token"),
                        org.hamcrest.Matchers.containsString("refresh_token=rt-1"),
                        org.hamcrest.Matchers.containsString("client_id=" + CLIENT_ID),
                        org.hamcrest.Matchers.containsString("client_secret=" + CLIENT_SECRET))))
                .andRespond(withSuccess("{\"access_token\":\"at-9\",\"expires_in\":3599,\"token_type\":\"Bearer\"}", MediaType.APPLICATION_JSON));

        GoogleOAuthClient.AccessToken token = client.refreshAccessToken("rt-1");

        assertThat(token.value()).isEqualTo("at-9");
        assertThat(token.expiresInSeconds()).isEqualTo(3599);
        assertThat(token.toString()).doesNotContain("at-9");
        server.verify();
    }

    @Test
    void aRevokedRefreshTokenIsReportedAsInvalidGrantWithoutLeakingAnything() {
        server.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked rt-secret\"}"));

        assertThatThrownBy(() -> client.refreshAccessToken("rt-secret"))
                .isInstanceOf(GoogleOAuthException.class)
                .satisfies(failure -> {
                    assertThat(((GoogleOAuthException) failure).errorCode()).isEqualTo("INVALID_GRANT");
                    assertThat(failure.getMessage()).doesNotContain("rt-secret").doesNotContain(CLIENT_SECRET);
                });
    }

    @Test
    void refreshProblemsAreMappedToShortCodes() {
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        assertThatThrownBy(() -> client.refreshAccessToken("rt")).extracting(f -> ((GoogleOAuthException) f).errorCode()).isEqualTo("HTTP_503");

        server.reset();
        server.expect(requestTo("https://oauth2.googleapis.com/token")).andRespond(withSuccess("{\"expires_in\":10}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.refreshAccessToken("rt")).extracting(f -> ((GoogleOAuthException) f).errorCode()).isEqualTo("MALFORMED_RESPONSE");
    }

    @Test
    void sendsTheRawMessageToGmailWithTheBearerTokenOnly() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/send")).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer at-1"))
                .andExpect(jsonPath("$.raw").value("cmF3LW1lc3NhZ2U"))
                .andRespond(withSuccess("{\"id\":\"m1\",\"threadId\":\"t1\"}", MediaType.APPLICATION_JSON));

        client.sendMessage("at-1", "cmF3LW1lc3NhZ2U");

        server.verify();
    }

    @Test
    void anErrorAnswerFromGmailMeansNotSentAndNoAnswerMeansUncertain() {
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"))
                .andRespond(withStatus(HttpStatus.FORBIDDEN).contentType(MediaType.APPLICATION_JSON).body("{\"error\":{\"message\":\"body with at-secret\"}}"));
        assertThatThrownBy(() -> client.sendMessage("at-secret", "cmF3"))
                .isInstanceOf(GoogleOAuthException.class)
                .satisfies(failure -> {
                    assertThat(((GoogleOAuthException) failure).errorCode()).isEqualTo("HTTP_403");
                    assertThat(failure.getMessage()).doesNotContain("at-secret");
                });

        server.reset();
        server.expect(requestTo("https://gmail.googleapis.com/gmail/v1/users/me/messages/send"))
                .andRespond(request -> { throw new java.io.IOException("connection reset"); });
        assertThatThrownBy(() -> client.sendMessage("at-1", "cmF3"))
                .isInstanceOf(GoogleOAuthException.class)
                .extracting(failure -> ((GoogleOAuthException) failure).errorCode()).isEqualTo(GoogleOAuthClient.NO_RESPONSE);
    }

    @Test
    void aPlusInTheLoginHintIsEncodedSoGoogleDoesNotReadItAsASpace() {
        String url = client.buildAuthorizationUrl("state-value", "challenge-value", "hr+team@itap.example");

        assertThat(url).contains("login_hint=hr%2Bteam@itap.example").doesNotContain("+");
    }

    @Test
    void theAuthorizationUrlUsesExactlyTheConfiguredRedirectUri() {
        String url = client.buildAuthorizationUrl("s", "c", null);

        assertThat(url).contains("redirect_uri=https://api.example.test/api/oauth/google/callback").doesNotContain("login_hint");
    }
}
