package com.waypoint.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TemporaryPasswordGeneratorTest {

    private final TemporaryPasswordGenerator generator = new TemporaryPasswordGenerator();

    @Test
    void generatesStrongNonSharedTemporaryPasswords() {
        String first = generator.generate();
        String second = generator.generate();

        assertThat(first).hasSize(16).isNotEqualTo(second);
        assertThat(first).matches(".*[a-z].*");
        assertThat(first).matches(".*[A-Z].*");
        assertThat(first).matches(".*[0-9].*");
        assertThat(first).matches(".*[!@#$%&*?].*");
    }
}
