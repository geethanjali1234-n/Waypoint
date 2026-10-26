package com.waypoint.exception;

/**
 * The mail server may already have accepted the message but its final
 * confirmation never arrived (e.g. a read timeout after the message body was
 * sent). The email is not reported as failed and must not be resent blindly.
 */
public class EmailDeliveryUnconfirmedException extends RuntimeException {

    public EmailDeliveryUnconfirmedException(String message) {
        super(message);
    }
}
