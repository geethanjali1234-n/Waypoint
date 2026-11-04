package com.waypoint.dto.auth;

/** Result of asking for an account email change. The login email has NOT changed yet. */
public record AccountEmailChangeRequestedResponse(String newEmail, int expiresInSeconds, int resendAfterSeconds) {
}
