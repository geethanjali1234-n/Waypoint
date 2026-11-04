package com.waypoint.service.google;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SlidingWindowLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    @Test
    void allowsUpToTheLimitThenBlocksUntilTheWindowPasses() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(3, 60_000, now::get);

        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isTrue();
        assertThat(limiter.tryAcquire("a")).isFalse();
        now.addAndGet(59_999);
        assertThat(limiter.tryAcquire("a")).isFalse();
        now.addAndGet(1);
        assertThat(limiter.tryAcquire("a")).isTrue();
    }

    @Test
    void aBlockedAttemptIsNotCountedAgainstTheKey() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, 1_000, now::get);

        assertThat(limiter.tryAcquire("a")).isTrue();
        for (int i = 0; i < 50; i++) assertThat(limiter.tryAcquire("a")).isFalse();
        now.addAndGet(1_000);
        assertThat(limiter.tryAcquire("a")).as("recovers as soon as the first event leaves the window").isTrue();
    }

    @Test
    void keysAreIndependentSoOneClientCannotBlockAnother() {
        SlidingWindowLimiter limiter = new SlidingWindowLimiter(1, 60_000, now::get);

        assertThat(limiter.tryAcquire("org-itap")).isTrue();
        assertThat(limiter.tryAcquire("org-itap")).isFalse();
        assertThat(limiter.tryAcquire("org-abc")).isTrue();
        assertThat(limiter.tryAcquire(null)).isTrue();
    }

    @Test
    void aLegitimateConnectionIsNeverAffectedByTheConfiguredLimits() {
        GoogleRateLimits limits = new GoogleRateLimits();

        // One connect and one callback is all a normal connection needs; a few retries must still pass.
        for (int i = 0; i < 5; i++) {
            assertThat(limits.allowConnectAttempt("admin-1")).isTrue();
            assertThat(limits.allowCallback("203.0.113.9")).isTrue();
        }
        assertThat(limits.allowTestEmail("org-itap")).isTrue();
    }

    @Test
    void theConfiguredLimitsStopRepeatedAbuseFromOneSourceOnly() {
        GoogleRateLimits limits = new GoogleRateLimits();

        for (int i = 0; i < 10; i++) assertThat(limits.allowConnectAttempt("admin-1")).isTrue();
        assertThat(limits.allowConnectAttempt("admin-1")).isFalse();
        assertThat(limits.allowConnectAttempt("admin-2")).isTrue();

        for (int i = 0; i < 5; i++) assertThat(limits.allowTestEmail("org-itap")).isTrue();
        assertThat(limits.allowTestEmail("org-itap")).isFalse();
        assertThat(limits.allowTestEmail("org-abc")).isTrue();

        for (int i = 0; i < 30; i++) assertThat(limits.allowCallback("198.51.100.1")).isTrue();
        assertThat(limits.allowCallback("198.51.100.1")).isFalse();
        assertThat(limits.allowCallback("198.51.100.2")).isTrue();
    }

    @Test
    void invalidLimitsAreRejected() {
        assertThatThrownBy(() -> new SlidingWindowLimiter(0, 1_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SlidingWindowLimiter(1, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
