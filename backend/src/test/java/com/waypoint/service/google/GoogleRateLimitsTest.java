package com.waypoint.service.google;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GoogleRateLimitsTest {

    @Test
    void oneClientAddressIsLimitedWithoutAffectingAnother() {
        GoogleRateLimits limits = new GoogleRateLimits();
        for (int i = 0; i < 30; i++) assertThat(limits.allowCallback("203.0.113.1")).isTrue();

        assertThat(limits.allowCallback("203.0.113.1")).isFalse();
        assertThat(limits.allowCallback("203.0.113.2")).isTrue();
    }

    @Test
    void rotatingForgedAddressesCannotDodgeTheSharedCeiling() {
        GoogleRateLimits limits = new GoogleRateLimits();
        int allowed = 0;
        for (int i = 0; i < 1000; i++) {
            if (limits.allowCallback("198.51.100." + (i % 250) + "-" + i)) allowed++;
        }

        assertThat(allowed).isEqualTo(300);
    }
}
