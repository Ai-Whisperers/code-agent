package com.eneve.agent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JdkResolverTest {

    @Test
    void pickSupportedMajor_mapsAbove21To21() {
        assertEquals(21, JdkResolver.pickSupportedMajor(22));
        assertEquals(21, JdkResolver.pickSupportedMajor(25));
    }

    @Test
    void pickSupportedMajor_unchangedForSupportedRange() {
        assertEquals(8, JdkResolver.pickSupportedMajor(8));
        assertEquals(17, JdkResolver.pickSupportedMajor(11));
        assertEquals(21, JdkResolver.pickSupportedMajor(21));
    }

    @Test
    void pickSupportedMajor_zeroOrNegative() {
        assertEquals(0, JdkResolver.pickSupportedMajor(0));
        assertEquals(0, JdkResolver.pickSupportedMajor(-1));
    }
}
