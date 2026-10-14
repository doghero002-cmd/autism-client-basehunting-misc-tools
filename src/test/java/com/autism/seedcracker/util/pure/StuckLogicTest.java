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

    @Test
    void slowTunnelMovementAccumulatesUntilItResetsTheCounter() {
        double anchor = 0;
        int ticks = 0;
        for (int sample = 1; sample <= 60; sample++) {
            double position = sample * 0.02;
            ticks = StuckLogic.update(ticks, true, Math.abs(position - anchor), 0.05);
            if (ticks == 0) anchor = position;
            assertTrue(ticks < 3);
        }
    }

    @Test
    void blockedTunnelStillReachesRecoveryThreshold() {
        int ticks = 0;
        for (int sample = 0; sample < 20; sample++) ticks = StuckLogic.update(ticks, true, 0, 0.05);
        assertTrue(StuckLogic.isStuck(ticks, 20));
    }

    @Test
    void walkingProgressAccumulatesAcrossSharedDetectorSamples() {
        double anchor = 0;
        int ticks = 0;
        for (int sample = 1; sample <= 100; sample++) {
            double position = sample * 0.1;
            ticks = StuckLogic.update(ticks, true, position - anchor, EPS);
            if (ticks == 0) anchor = position;
            assertTrue(ticks < 4);
        }
    }
}
