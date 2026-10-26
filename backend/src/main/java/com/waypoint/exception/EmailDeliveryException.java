package com.waypoint.exception;

/**
 * An email could not be handed to the mail server. The message is safe to
 * show to users; SMTP details stay in the (credential-free) server log.
 */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message) {
        super(message);
    }
}
