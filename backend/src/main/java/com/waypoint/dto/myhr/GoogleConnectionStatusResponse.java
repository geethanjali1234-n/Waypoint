package com.waypoint.dto.myhr;

import java.time.LocalDateTime;

/**
 * What an organization admin may see about the Google connection. Never contains tokens, ciphertext, the Google
 * account id or the OAuth client configuration.
 *
 * @param featureEnabled          whether the platform has Google email connection switched on
 * @param status                  NOT_CONNECTED, CONNECTED, REAUTH_REQUIRED or DISCONNECTED
 * @param googleAccountEmail      the connected account (null when not connected)
 * @param organizationEmailDomain domain of the Organization Email the connected account must match
 */
public record GoogleConnectionStatusResponse(
        boolean featureEnabled,
        String status,
        String googleAccountEmail,
        String organizationEmailDomain,
        LocalDateTime connectedAt,
        LocalDateTime lastSendAt,
        String lastErrorCode) {
}
