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
 * A Company Admin's requested login-email change, waiting for the one-time code that proves control of the new address.
 *
 * <p>The document id IS the user id, so there is at most one pending change per user without needing a unique index
 * (index creation is switched off in production). Expiry is enforced in code, never by a TTL index. The code is stored
 * only as a hash, and the organization id is recorded so a confirmation can be refused if the user moved.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString(exclude = "otpHash")
@Document(collection = "pending_account_email_changes")
public class PendingAccountEmailChange {

    /** The user id. */
    @Id
    private String id;

    private String organizationId;

    /** Normalized (trimmed, lower case). */
    private String newEmail;

    private String otpHash;

    private LocalDateTime expiresAt;

    @Builder.Default
    private Integer failedAttempts = 0;

    /** Set atomically by the one confirmation that wins; makes the code single use. */
    private LocalDateTime claimedAt;

    private LocalDateTime lastSentAt;

    /** Start of the current send-limit window, kept across re-requests so a new request cannot reset the limit. */
    private LocalDateTime sendWindowStartedAt;

    @Builder.Default
    private Integer sendCount = 0;

    private LocalDateTime createdAt;
}
