package com.waypoint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Settings for connecting an organization's own Google account to send its email. Off unless GOOGLE_EMAIL_ENABLED=true.
 * The OAuth client belongs to the WayPoint platform; organizations only authorize their own Google accounts.
 *
 * <p>Deliberately has no {@code toString}: it holds the OAuth client secret and the token encryption key.</p>
 */
@ConfigurationProperties(prefix = "waypoint.google")
public class GoogleOAuthProperties {

    /** The only path the backend serves for the Google callback ({@code GoogleOAuthCallbackController}). */
    public static final String CALLBACK_PATH = "/api/oauth/google/callback";
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    private boolean enabled = false;
    private String clientId;
    private String clientSecret;
    private String redirectUri;
    private String tokenEncryptionKey;
    private String tokenEncryptionKeyId = "v1";
    // Google's public endpoints. Not secret; overridable so tests can point at a local stub.
    private String authorizationUrl = "https://accounts.google.com/o/oauth2/v2/auth";
    private String tokenUrl = "https://oauth2.googleapis.com/token";
    private String revokeUrl = "https://oauth2.googleapis.com/revoke";
    private String gmailSendUrl = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private int stateTtlMinutes = 10;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getClientId() { return clientId; }
    public void setClientId(String clientId) { this.clientId = clientId; }

    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }

    public String getRedirectUri() { return redirectUri; }
    public void setRedirectUri(String redirectUri) { this.redirectUri = redirectUri; }

    public String getTokenEncryptionKey() { return tokenEncryptionKey; }
    public void setTokenEncryptionKey(String tokenEncryptionKey) { this.tokenEncryptionKey = tokenEncryptionKey; }

    public String getTokenEncryptionKeyId() { return tokenEncryptionKeyId; }
    public void setTokenEncryptionKeyId(String tokenEncryptionKeyId) { this.tokenEncryptionKeyId = tokenEncryptionKeyId; }

    public String getAuthorizationUrl() { return authorizationUrl; }
    public void setAuthorizationUrl(String authorizationUrl) { this.authorizationUrl = authorizationUrl; }

    public String getTokenUrl() { return tokenUrl; }
    public void setTokenUrl(String tokenUrl) { this.tokenUrl = tokenUrl; }

    public String getRevokeUrl() { return revokeUrl; }
    public void setRevokeUrl(String revokeUrl) { this.revokeUrl = revokeUrl; }

    public String getGmailSendUrl() { return gmailSendUrl; }
    public void setGmailSendUrl(String gmailSendUrl) { this.gmailSendUrl = gmailSendUrl; }

    public int getStateTtlMinutes() { return stateTtlMinutes; }
    public void setStateTtlMinutes(int stateTtlMinutes) { this.stateTtlMinutes = Math.max(1, stateTtlMinutes); }

    /**
     * Stops startup, naming only the environment variable, when the feature is enabled but not fully configured.
     * Values are never included in the message.
     */
    public void requireCompleteWhenEnabled() {
        if (!enabled) return;
        require("GOOGLE_OAUTH_CLIENT_ID", clientId);
        require("GOOGLE_OAUTH_CLIENT_SECRET", clientSecret);
        require("GOOGLE_OAUTH_REDIRECT_URI", redirectUri);
        require("GOOGLE_TOKEN_ENCRYPTION_KEY", tokenEncryptionKey);
        String redirectProblem = redirectUriProblem(redirectUri);
        if (redirectProblem != null) {
            throw new IllegalStateException("GOOGLE_OAUTH_REDIRECT_URI is invalid: " + redirectProblem
                    + ". Expected https://<your-backend-host>" + CALLBACK_PATH + " (or http://localhost:8081" + CALLBACK_PATH
                    + " for local development), and the same value must be listed as an authorized redirect URI on the Google OAuth client.");
        }
    }

    /**
     * When the feature is enabled, the address the browser is sent back to after Google must be usable too. A missing
     * APP_FRONTEND_URL silently falls back to http://localhost:5173, which would strand every real admin on localhost, so
     * a public (non-local) redirect URI requires a public https frontend address. Values are never echoed.
     */
    public void validateFrontendBaseUrl(String frontendBaseUrl) {
        if (!enabled) return;
        URI frontend = parseHttpUri(frontendBaseUrl);
        if (frontend == null || frontend.getUserInfo() != null || frontend.getQuery() != null || frontend.getFragment() != null) {
            throw new IllegalStateException("APP_FRONTEND_URL must be the web address of the WayPoint web app, for example "
                    + "https://app.example.com (an http or https address with no credentials, query or fragment) "
                    + "when GOOGLE_EMAIL_ENABLED is true");
        }
        URI redirect = parseHttpUri(redirectUri);
        boolean redirectIsLocal = redirect == null || isLocalHost(redirect.getHost());
        if (!redirectIsLocal && (!"https".equalsIgnoreCase(frontend.getScheme()) || isLocalHost(frontend.getHost()))) {
            throw new IllegalStateException("APP_FRONTEND_URL must be the public https address of the WayPoint web app when "
                    + "GOOGLE_OAUTH_REDIRECT_URI is a public address; otherwise admins would be sent back to a local address "
                    + "after connecting Google");
        }
    }

    /** Host-only description for the startup log; never includes credentials, path or query. */
    public String redirectHostForLog() {
        URI uri = parseHttpUri(redirectUri);
        return uri == null || uri.getHost() == null ? "unknown" : uri.getHost();
    }

    /**
     * Why the redirect URI cannot work, or null when it can. The backend sends this value to Google unchanged and Google
     * requires an exact match with the OAuth client, so the shape is checked here against what the backend and Google
     * accept: the exact callback path, https (http only for localhost), no credentials, query or fragment, no wildcards,
     * and a real domain name rather than an IP address (loopback excepted).
     */
    static String redirectUriProblem(String value) {
        if (value == null || value.isBlank()) return "it is empty";
        if (value.contains("*")) return "it must not contain wildcards";
        URI uri = parseHttpUri(value);
        if (uri == null) return "it must be an absolute http(s) address with a host";
        if (uri.getUserInfo() != null) return "it must not contain credentials";
        if (uri.getFragment() != null) return "it must not contain a fragment (#)";
        if (uri.getQuery() != null) return "it must not contain a query string";
        if (!CALLBACK_PATH.equals(uri.getPath())) {
            return "its path must be exactly " + CALLBACK_PATH + " (case-sensitive, no trailing slash, no prefix)";
        }
        String host = uri.getHost();
        boolean local = isLocalHost(host);
        if (!local && !"https".equalsIgnoreCase(uri.getScheme())) {
            return "it must use https unless the host is localhost";
        }
        if (!local && (IPV4.matcher(host).matches() || host.contains(":"))) {
            return "it must use a domain name, not an IP address";
        }
        if (!local && !host.contains(".")) return "its host must be a full public domain name";
        return null;
    }

    private static URI parseHttpUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            URI uri = new URI(value.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            return uri;
        } catch (URISyntaxException invalid) {
            return null;
        }
    }

    /** localhost and loopback addresses: the only hosts Google allows over plain http (and as raw IPs). */
    private static boolean isLocalHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("localhost") || normalized.equals("127.0.0.1") || normalized.equals("[::1]") || normalized.equals("::1");
    }

    private static void require(String environmentVariable, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(environmentVariable + " must be configured when GOOGLE_EMAIL_ENABLED is true");
        }
    }
}
