package com.waypoint.dto.myhr;

/**
 * Result of the "send test email" action. {@code code} is SENT or a short reason such as NOT_CONNECTED, REAUTH_REQUIRED,
 * DOMAIN_MISMATCH or NO_RESPONSE. {@code recipient} is always the calling organization's own Organization Email and
 * {@code sender} the organization's connected Google account; neither is ever taken from the request.
 */
public record GoogleTestEmailResponse(boolean sent, String code, String recipient, String sender) {
}
