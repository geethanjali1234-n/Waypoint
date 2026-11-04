package com.waypoint.service.google;

import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The limits around the Google connection. Generous enough that a legitimate connection (one "connect" and one callback)
 * is never affected, tight enough to stop automated abuse.
 */
@Component
public class GoogleRateLimits {

    /** Starting a connection, per authenticated user: protects the one-time state table from being flooded. */
    private final SlidingWindowLimiter connectAttempts = new SlidingWindowLimiter(10, TimeUnit.MINUTES.toMillis(10));
    /** Test emails, per organization: the test is a real send from the organization's own mailbox. */
    private final SlidingWindowLimiter testEmails = new SlidingWindowLimiter(5, TimeUnit.MINUTES.toMillis(10));
    /** OAuth callback, per client address: the browser makes one request per connection attempt. */
    private final SlidingWindowLimiter callbacks = new SlidingWindowLimiter(30, TimeUnit.MINUTES.toMillis(1));
    /**
     * OAuth callback, all clients together. The client address comes from X-Forwarded-For, whose first entry a caller can
     * choose, so the per-address limit alone could be dodged by rotating addresses. This shared ceiling cannot be dodged
     * that way. It is far above real use (one callback per connection attempt, and connecting is rare).
     */
    private final SlidingWindowLimiter allCallbacks = new SlidingWindowLimiter(300, TimeUnit.MINUTES.toMillis(1));
    private static final String ALL_CLIENTS = "all";

    public boolean allowConnectAttempt(String userId) {
        return connectAttempts.tryAcquire(userId);
    }

    public boolean allowTestEmail(String organizationId) {
        return testEmails.tryAcquire(organizationId);
    }

    public boolean allowCallback(String clientAddress) {
        return callbacks.tryAcquire(clientAddress) && allCallbacks.tryAcquire(ALL_CLIENTS);
    }
}
