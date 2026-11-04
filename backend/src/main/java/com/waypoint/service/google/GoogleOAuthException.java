package com.waypoint.service.google;

/** A Google OAuth call failed. Carries only a short error code; never a token, secret or response body. */
public class GoogleOAuthException extends RuntimeException {

    private final String errorCode;

    public GoogleOAuthException(String errorCode) {
        super("Google OAuth request failed: " + errorCode);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
