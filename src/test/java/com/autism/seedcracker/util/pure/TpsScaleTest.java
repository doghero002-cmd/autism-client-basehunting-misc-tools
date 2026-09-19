package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TpsScaleTest {

    private static final float FLOOR = 19.5f;

    @Test
    void healthyServerUnchanged() {
        assertEquals(10, TpsScale.scale(10, 20.0f, FLOOR));
        assertEquals(10, TpsScale.scale(10, 19.6f, FLOOR));
        assertEquals(500L, TpsScale.scaleMs(500L, 20.0f, FLOOR));
    }

    @Test
    void halfTpsDoublesDelays() {
        assertEquals(20, TpsScale.scale(10, 10.0f, FLOOR));
        assertEquals(1000L, TpsScale.scaleMs(500L, 10.0f, FLOOR));
    }

    @Test
    void extremeLagIsBounded() {
        // TPS floor of 1.0 inside the formula: never divides by less than 1.
        assertEquals(200, TpsScale.scale(10, 0.1f, FLOOR));
    }

    @Test
    void minimumOneTick() {
        assertEquals(1, TpsScale.scale(1, 19.0f, FLOOR));
        assertTrue(TpsScale.scale(0, 10.0f, FLOOR) >= 1);
    }
}
