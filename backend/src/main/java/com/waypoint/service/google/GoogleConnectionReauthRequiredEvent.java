package com.waypoint.service.google;

/**
 * Published once when an organization's Google connection moves from CONNECTED to REAUTH_REQUIRED. Carries only the
 * organization id and a short reason code: no token, address or Google response detail. It is a Spring application
 * event so the sender ({@link GmailApiEmailService}) never depends on the notification services, which themselves send
 * email through the sender; that dependency would be circular.
 */
public record GoogleConnectionReauthRequiredEvent(String organizationId, String reasonCode) {
}
