package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StuckLogicTest {

    private static final double EPS = 0.35;

    @Test
    void firstSampleNeverCounts() {
        assertEquals(0, StuckLogic.update(0, false, 0.0, EPS));
    }

    @Test
    void movementResets() {
        int t = 50;
        t = StuckLogic.update(t, true, 1.0, EPS);
        assertEquals(0, t);
    }

    @Test
    void standingStillAccumulates() {
        int t = 0;
        for (int i = 0; i < 10; i++) t = StuckLogic.update(t, true, 0.1, EPS);
        assertEquals(10, t);
    }

    @Test
    void epsilonIsExclusive() {
        // Moving exactly epsilon does NOT reset (must exceed it).
        assertEquals(1, StuckLogic.update(0, true, EPS, EPS));
        assertEquals(0, StuckLogic.update(5, true, EPS + 0.01, EPS));
    }

    @Test
    void stuckThreshold() {
        assertFalse(StuckLogic.isStuck(79, 80));
        assertTrue(StuckLogic.isStuck(80, 80));
    }
}
