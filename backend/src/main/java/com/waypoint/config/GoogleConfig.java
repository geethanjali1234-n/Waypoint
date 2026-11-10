package com.waypoint.config;

import com.waypoint.security.TokenCipher;
import com.waypoint.security.TokenCipherException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** Wiring for the per-organization Google email connection. Everything here is inert while the feature is disabled. */
@Slf4j
@Configuration
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleConfig {

    /**
     * When the feature is enabled, a missing or malformed key stops startup (a connection must never be stored
     * unencrypted). When it is disabled the cipher is unconfigured unless a valid key happens to be present, and no
     * startup failure occurs, so existing environments are unaffected.
     */
    @Bean
    public TokenCipher googleTokenCipher(GoogleOAuthProperties properties,
                                         @Value("${waypoint.app.frontend-base-url:http://localhost:5173}") String frontendBaseUrl) {
        properties.requireCompleteWhenEnabled();
        properties.validateFrontendBaseUrl(frontendBaseUrl);
        if (properties.isEnabled() || properties.isPlatformSenderEnabled()) {
            // Hosts only (no path, query or credentials): enough to spot a wrong environment at a glance.
            log.info("Google email connection is enabled [redirectHost={}, callbackPath={}]",
                    properties.redirectHostForLog(), GoogleOAuthProperties.CALLBACK_PATH);
        }
        if (!StringUtils.hasText(properties.getTokenEncryptionKey())) {
            return TokenCipher.unconfigured();
        }
        try {
            return new TokenCipher(properties.getTokenEncryptionKey(), properties.getTokenEncryptionKeyId());
        } catch (TokenCipherException invalid) {
            if (properties.isEnabled() || properties.isPlatformSenderEnabled()) {
                throw new IllegalStateException("GOOGLE_TOKEN_ENCRYPTION_KEY is invalid: " + invalid.getMessage());
            }
            log.warn("Google token encryption key ignored because the feature is disabled [reason=INVALID_KEY]");
            return TokenCipher.unconfigured();
        }
    }
}
