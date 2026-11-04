package com.waypoint.entity;

import com.waypoint.enums.GoogleConnectionStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The Google account an organization has authorized to send its email. At most one per organization.
 *
 * <p>Only the refresh token is stored, and only as AES-256-GCM ciphertext (see {@code TokenCipher}) with the
 * organization id as associated data. Access tokens are never persisted. The indexes (unique organizationId; unique
 * googleSubject among CONNECTED rows) are created by {@code GoogleConnectionIndexConfig} because one of them is a
 * partial index and they are only needed when the feature is enabled.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "organization_google_connections")
public class OrganizationGoogleConnection {

    @Id
    private String id;

    private String organizationId;

    private GoogleConnectionStatus status;

    /** Lowercased address of the connected Google account; this is the From address of organization email. */
    private String googleAccountEmail;

    /** Google's stable account id (the {@code sub} claim). One CONNECTED organization per Google account. */
    private String googleSubject;

    /** The {@code hd} claim for Google Workspace accounts; informational. */
    private String hostedDomain;

    private List<String> grantedScopes;

    @ToString.Exclude
    private String refreshTokenCiphertext;

    @ToString.Exclude
    private String refreshTokenIv;

    private String encryptionKeyId;

    private String connectedByUserId;
    private LocalDateTime connectedAt;

    private LocalDateTime lastSendAt;

    /** A short error code only (for example INVALID_GRANT); never a token, address or message body. */
    private String lastErrorCode;
    private LocalDateTime lastErrorAt;

    private LocalDateTime disconnectedAt;
    private String disconnectedByUserId;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Version
    private Long version;
}
