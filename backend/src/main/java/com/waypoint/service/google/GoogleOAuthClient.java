package com.waypoint.service.google;

import com.waypoint.config.GoogleOAuthProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The only class that talks to Google's OAuth endpoints (authorization URL, code exchange, revoke), using the shared
 * Spring {@link RestClient}. Nothing here logs: tokens, the authorization code, the client secret and Google's
 * response bodies are never written to a log or an exception message.
 */
@Slf4j
@Component
public class GoogleOAuthClient {

    public static final String SCOPE_GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send";
    public static final List<String> REQUESTED_SCOPES = List.of("openid", "email", SCOPE_GMAIL_SEND);

    /** Error code of {@link #sendMessage} when Google never answered, so delivery is uncertain. */
    public static final String NO_RESPONSE = "NO_RESPONSE";

    private static final Set<String> ISSUERS = Set.of("https://accounts.google.com", "accounts.google.com");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient restClient;
    private final GoogleOAuthProperties properties;

    @Autowired
    public GoogleOAuthClient(RestClient.Builder builder, GoogleOAuthProperties properties) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.restClient = builder.clone().requestFactory(requestFactory).build();
        this.properties = properties;
    }

    /** For tests, which supply a client already bound to a mock server. */
    GoogleOAuthClient(RestClient restClient, GoogleOAuthProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /**
     * The Google consent URL. Asks for offline access and forces the consent screen so Google issues a refresh token,
     * and uses PKCE (S256). {@code loginHint} only pre-fills the account chooser; it is not trusted for anything.
     */
    public String buildAuthorizationUrl(String state, String codeChallenge, String loginHint) {
        UriComponentsBuilder url = UriComponentsBuilder.fromUriString(properties.getAuthorizationUrl())
                .queryParam("client_id", properties.getClientId())
                .queryParam("redirect_uri", properties.getRedirectUri())
                .queryParam("response_type", "code")
                .queryParam("scope", String.join(" ", REQUESTED_SCOPES))
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256")
                .queryParam("access_type", "offline")
                .queryParam("prompt", "consent");
        if (StringUtils.hasText(loginHint)) url.queryParam("login_hint", loginHint);
        // Spring leaves a literal '+' alone in a query value, but Google reads '+' as a space; an address such as
        // hr+team@example.com would otherwise reach the account chooser as "hr team@example.com". No other part of this
        // URL can contain a literal '+' (the scope separator is encoded as %20).
        return url.build().encode().toUriString().replace("+", "%2B");
    }

    public TokenResponse exchangeCode(String code, String codeVerifier) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        form.add("redirect_uri", properties.getRedirectUri());
        String body;
        try {
            body = restClient.post().uri(properties.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException rejected) {
            throw new GoogleOAuthException(errorCodeOf(rejected));
        } catch (RestClientException unreachable) {
            throw new GoogleOAuthException("UNREACHABLE");
        }
        try {
            JsonNode json = JSON.readTree(body);
            return new TokenResponse(text(json, "access_token"), text(json, "refresh_token"),
                    text(json, "id_token"), text(json, "scope"));
        } catch (RuntimeException malformed) {
            throw new GoogleOAuthException("MALFORMED_RESPONSE");
        }
    }

    /**
     * Exchanges a stored refresh token for a short-lived access token. The access token is for immediate use only and
     * must never be persisted. Failures carry Google's short error code (for example INVALID_GRANT when the
     * authorization was revoked or has expired).
     */
    public AccessToken refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());
        String body;
        try {
            body = restClient.post().uri(properties.getTokenUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form)
                    .retrieve().body(String.class);
        } catch (RestClientResponseException rejected) {
            throw new GoogleOAuthException(errorCodeOf(rejected));
        } catch (RestClientException unreachable) {
            throw new GoogleOAuthException("UNREACHABLE");
        }
        try {
            JsonNode json = JSON.readTree(body);
            String token = text(json, "access_token");
            if (!StringUtils.hasText(token)) throw new GoogleOAuthException("MALFORMED_RESPONSE");
            return new AccessToken(token, json.path("expires_in").asInt(0));
        } catch (GoogleOAuthException failure) {
            throw failure;
        } catch (RuntimeException malformed) {
            throw new GoogleOAuthException("MALFORMED_RESPONSE");
        }
    }

    /**
     * Sends one already-built RFC 822 message (base64url) as the account that owns the access token. Throws
     * {@link GoogleOAuthException} with {@code HTTP_<status>} when Google answered with an error, which means the
     * message was not accepted, or {@link #NO_RESPONSE} when there was no answer (timeout, connection reset), in which
     * case the message may or may not have been sent. Neither the token, the message nor Google's response body is
     * ever logged or placed in the exception.
     */
    public void sendMessage(String accessToken, String rawBase64Url) {
        try {
            restClient.post().uri(properties.getGmailSendUrl())
                    .header("Authorization", "Bearer " + accessToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(java.util.Map.of("raw", rawBase64Url))
                    .retrieve().toBodilessEntity();
        } catch (RestClientResponseException rejected) {
            throw new GoogleOAuthException("HTTP_" + rejected.getStatusCode().value());
        } catch (RestClientException noAnswer) {
            throw new GoogleOAuthException(NO_RESPONSE);
        }
    }

    /** Best effort. Returns whether Google accepted the revocation; never throws and never logs the token. */
    public boolean revoke(String token) {
        if (!StringUtils.hasText(token)) return false;
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        try {
            restClient.post().uri(properties.getRevokeUrl()).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form).retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException failure) {
            log.warn("Google token revocation was not confirmed [exceptionType={}]", failure.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Reads the identity out of an ID token received <em>directly from Google's token endpoint over HTTPS</em>, which
     * Google documents as sufficient without verifying the signature. Still checks issuer, audience, expiry, the
     * subject and a verified email.
     */
    public Identity parseIdentity(String idToken, Instant now) {
        try {
            String[] parts = idToken == null ? new String[0] : idToken.split("\\.");
            if (parts.length != 3) throw new GoogleOAuthException("INVALID_ID_TOKEN");
            JsonNode claims = JSON.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            if (!ISSUERS.contains(text(claims, "iss"))) throw new GoogleOAuthException("INVALID_ISSUER");
            if (!properties.getClientId().equals(audience(claims))) throw new GoogleOAuthException("INVALID_AUDIENCE");
            if (!claims.has("exp") || Instant.ofEpochSecond(claims.get("exp").asLong(0)).isBefore(now)) {
                throw new GoogleOAuthException("ID_TOKEN_EXPIRED");
            }
            String subject = text(claims, "sub");
            String email = text(claims, "email");
            if (!StringUtils.hasText(subject) || !StringUtils.hasText(email)) {
                throw new GoogleOAuthException("INCOMPLETE_IDENTITY");
            }
            if (!claims.path("email_verified").asBoolean(false)) throw new GoogleOAuthException("EMAIL_NOT_VERIFIED");
            return new Identity(subject, email.trim().toLowerCase(Locale.ROOT), text(claims, "hd"));
        } catch (GoogleOAuthException invalid) {
            throw invalid;
        } catch (RuntimeException malformed) {
            throw new GoogleOAuthException("INVALID_ID_TOKEN");
        }
    }

    private static String audience(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        return aud == null || aud.isNull() ? null : aud.asString();
    }

    private static String text(JsonNode json, String field) {
        JsonNode node = json.get(field);
        return node == null || node.isNull() ? null : node.asString();
    }

    private static String errorCodeOf(RestClientResponseException rejected) {
        try {
            String code = text(JSON.readTree(rejected.getResponseBodyAsString()), "error");
            if (code != null && code.matches("[a-z_]{1,40}")) return code.toUpperCase(Locale.ROOT);
        } catch (RuntimeException ignored) {
            // fall through to the status-based code
        }
        return "HTTP_" + rejected.getStatusCode().value();
    }

    /** Token endpoint result. {@code toString} is redacted. */
    public record TokenResponse(String accessToken, String refreshToken, String idToken, String scope) {
        public boolean grantsScope(String wanted) {
            return scopes().contains(wanted);
        }

        public List<String> scopes() {
            return scope == null || scope.isBlank() ? List.of() : List.of(scope.trim().split("\\s+"));
        }

        @Override
        public String toString() {
            return "TokenResponse[redacted]";
        }
    }

    /** A short-lived access token. {@code toString} is redacted. */
    public record AccessToken(String value, int expiresInSeconds) {
        @Override
        public String toString() {
            return "AccessToken[redacted]";
        }
    }

    /** The Google account behind an ID token. */
    public record Identity(String subject, String email, String hostedDomain) {
        @Override
        public String toString() {
            return "Identity[redacted]";
        }
    }
}
