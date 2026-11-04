package com.waypoint.enums;

public enum GoogleConnectionStatus {
    /** A usable refresh token is stored for the organization's Google account. */
    CONNECTED,
    /** Google no longer accepts the stored authorization (revoked, expired or blocked); an admin must reconnect. */
    REAUTH_REQUIRED,
    /** The organization disconnected; the token has been deleted. */
    DISCONNECTED
}
