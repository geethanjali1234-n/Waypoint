package com.waypoint.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

/**
 * Singleton authorization for the WayPoint-owned transactional Gmail account.
 *
 * <p>Only an AES-256-GCM encrypted refresh token is persisted. Access tokens are deliberately kept in memory by
 * {@code PlatformGoogleSenderService}; the singleton id is also the token cipher's associated data, so a token copied
 * from an organization connection cannot be decrypted here.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "platform_google_sender_connection")
public class PlatformGoogleSenderConnection {
    public static final String ID = "platform-gmail-sender";

    @Id
    private String id;

    /** Lowercased address from the Google ID token; must equal GOOGLE_PLATFORM_SENDER_ADDRESS. */
    private String googleAccountEmail;

    /** Google's stable account id (the ID-token {@code sub} claim). */
    private String googleSubject;

    @ToString.Exclude private String refreshTokenCiphertext;
    @ToString.Exclude private String refreshTokenIv;
    private String encryptionKeyId;

    private String connectedByUserId;
    private LocalDateTime connectedAt;

    private String lastErrorCode;
    private LocalDateTime lastErrorAt;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
