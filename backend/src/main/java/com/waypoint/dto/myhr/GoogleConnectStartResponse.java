package com.waypoint.dto.myhr;

/** The Google consent URL the browser must be sent to. Contains the one-time state, so it is never logged. */
public record GoogleConnectStartResponse(String authorizationUrl) {
    @Override
    public String toString() {
        return "GoogleConnectStartResponse[redacted]";
    }
}
