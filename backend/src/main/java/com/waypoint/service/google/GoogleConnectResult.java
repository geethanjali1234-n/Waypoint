package com.waypoint.service.google;

/**
 * The outcome of the OAuth callback. The name is placed in the redirect back to the settings page
 * ({@code ?google=error&reason=NAME}); the frontend maps it to a message for the Company Admin.
 */
public enum GoogleConnectResult {
    CONNECTED,
    /** The admin declined or cancelled on Google's consent screen. */
    ACCESS_DENIED,
    /** Google reported some other authorization error. */
    AUTHORIZATION_FAILED,
    /** Unknown, missing or malformed state. */
    INVALID_STATE,
    STATE_EXPIRED,
    STATE_ALREADY_USED,
    /** The admin who started the connection is no longer an active Company Admin of the organization. */
    NOT_AUTHORIZED,
    FEATURE_DISABLED,
    ORGANIZATION_EMAIL_MISSING,
    /** The admin did not grant the permission to send email. */
    MISSING_SCOPE,
    NO_REFRESH_TOKEN,
    TOKEN_EXCHANGE_FAILED,
    INVALID_IDENTITY,
    /** The Google account's domain differs from the Organization Email's domain. */
    DOMAIN_MISMATCH,
    /**
     * The Organization Email is on a consumer domain (gmail.com, googlemail.com), where the domain says nothing about who
     * owns the account, so the Google account must be exactly the Organization Email.
     */
    ACCOUNT_EMAIL_MISMATCH,
    /** The Google account is already connected to another organization. */
    ACCOUNT_IN_USE,
    /** Too many callback requests from one client address in a short time. */
    TOO_MANY_ATTEMPTS,
    CONNECT_FAILED
}
