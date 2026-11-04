package com.waypoint.security;

/** A stored token could not be encrypted or decrypted. The message never contains key or token material. */
public class TokenCipherException extends RuntimeException {

    public TokenCipherException(String message) {
        super(message);
    }

    public TokenCipherException(String message, Throwable cause) {
        super(message, cause);
    }
}
