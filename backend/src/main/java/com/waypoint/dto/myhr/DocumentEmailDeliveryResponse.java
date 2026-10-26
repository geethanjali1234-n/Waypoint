package com.waypoint.dto.myhr;

/** Delivery outcome for a completed document email; document completion is independent of this result. */
public record DocumentEmailDeliveryResponse(String status, String recipientEmail, String message) {}
