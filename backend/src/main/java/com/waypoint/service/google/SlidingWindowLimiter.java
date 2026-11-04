package com.waypoint.service.google;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Small in-memory sliding-window limiter: at most {@code maxEvents} per key within {@code windowMillis}. Used to bound
 * how often one user, organization or client address can start a Google connection, send a test email or hit the OAuth
 * callback, without affecting anyone else (each key has its own window). Memory is bounded: idle keys are purged.
 *
 * <p>Per instance, so with several application instances the effective limit is per instance. That is acceptable for
 * abuse protection; it is not an authorization control.</p>
 */
public final class SlidingWindowLimiter {

    private static final int PURGE_THRESHOLD = 5_000;

    private final int maxEvents;
    private final long windowMillis;
    private final LongSupplier clock;
    private final Map<String, Deque<Long>> events = new ConcurrentHashMap<>();

    public SlidingWindowLimiter(int maxEvents, long windowMillis) {
        this(maxEvents, windowMillis, System::currentTimeMillis);
    }

    SlidingWindowLimiter(int maxEvents, long windowMillis, LongSupplier clock) {
        if (maxEvents < 1 || windowMillis < 1) throw new IllegalArgumentException("Limit and window must be positive");
        this.maxEvents = maxEvents;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    /** Records an attempt for the key and returns whether it is within the limit. A blocked attempt is not counted. */
    public boolean tryAcquire(String key) {
        long now = clock.getAsLong();
        if (events.size() > PURGE_THRESHOLD) purge(now);
        boolean[] allowed = {false};
        events.compute(key == null ? "unknown" : key, (ignored, existing) -> {
            Deque<Long> window = existing != null ? existing : new ArrayDeque<>();
            while (!window.isEmpty() && now - window.peekFirst() >= windowMillis) window.pollFirst();
            if (window.size() < maxEvents) {
                window.addLast(now);
                allowed[0] = true;
            }
            return window;
        });
        return allowed[0];
    }

    private void purge(long now) {
        events.entrySet().removeIf(entry -> {
            Long newest = entry.getValue().peekLast();
            return newest == null || now - newest >= windowMillis;
        });
    }
}
