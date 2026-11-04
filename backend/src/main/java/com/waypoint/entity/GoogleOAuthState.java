package com.waypoint.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

/**
 * One in-flight Google authorization. Created by the authenticated "connect" call and consumed once by the public
 * callback. The organization and user come from the authenticated server-side context at creation, never from the
 * callback request.
 *
 * <p>The raw state value exists only in the redirect URL; only its SHA-256 hash is stored. The PKCE code verifier is
 * stored encrypted. Rows expire through a TTL index on {@code expiresAt} (created by
 * {@code GoogleConnectionIndexConfig}).</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "google_oauth_states")
public class GoogleOAuthState {

    @Id
    private String id;

    /** SHA-256 of the state value (hex). Unique. */
    private String stateHash;

    private String organizationId;
    private String userId;

    @ToString.Exclude
    private String codeVerifierCiphertext;

    @ToString.Exclude
    private String codeVerifierIv;

    private String encryptionKeyId;

    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;

    /** Set atomically when the callback consumes the state; a used state is never accepted again. */
    private LocalDateTime usedAt;
}
