package com.waypoint.security;

/**
 * Expected-failure helper for documented security gaps. The secure expectation is written in full
 * and must currently FAIL; the test passes while the gap still reproduces and fails as soon as the
 * secure behaviour holds, so a fixed gap is never left behind as a stale characterization.
 */
public final class KnownSecurityGap {

    @FunctionalInterface
    public interface Expectation {
        void check() throws Exception;
    }

    private KnownSecurityGap() {
    }

    public static void expectedToFail(String gapId, String secureBehaviour, Expectation secureExpectation) throws Exception {
        try {
            secureExpectation.check();
        } catch (AssertionError reproduced) {
            System.out.println("KNOWN SECURITY GAP " + gapId + " reproduced (expected failure): " + secureBehaviour);
            return;
        }
        throw new AssertionError("Known security gap " + gapId + " no longer reproduces: \"" + secureBehaviour
                + "\" now holds. Replace this expected failure with a regular assertion.");
    }
}
