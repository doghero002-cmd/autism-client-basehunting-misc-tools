package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BiteDetectTest {

    @Test
    void sharpDropAfterFloatIsBite() {
        assertTrue(BiteDetect.isBite(-0.12, -0.01, 0.0));
        assertTrue(BiteDetect.isBite(-0.08, 0.02, 0.0005));
    }

    @Test
    void gentleBobIsNotBite() {
        assertFalse(BiteDetect.isBite(-0.02, -0.01, 0.0));
        assertFalse(BiteDetect.isBite(-0.05, -0.02, 0.0)); // below float but above bite threshold
    }

    @Test
    void castingFlightIsNotBite() {
        // Falling fast during the cast arc: dy very negative on consecutive ticks.
        assertFalse(BiteDetect.isBite(-0.5, -0.4, 0.2));
        // Horizontal drift too high even if dy pattern matches.
        assertFalse(BiteDetect.isBite(-0.12, -0.01, 0.01));
    }

    @Test
    void sinkingBobberIsNotBite() {
        // Already sinking last tick (lastDy below float band) - not a fresh jerk.
        assertFalse(BiteDetect.isBite(-0.12, -0.10, 0.0));
    }
}
