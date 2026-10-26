package com.waypoint.service;

/** One file attached to an outgoing email. */
public record EmailAttachment(String fileName, byte[] content, String contentType) {
}
