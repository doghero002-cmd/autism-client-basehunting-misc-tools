package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PacingTest {

    // ---- backoffHoldMs ----

    @Test
    void exponentialBase() {
        assertEquals(2000, Pacing.backoffHoldMs(1, 0));
        assertEquals(4000, Pacing.backoffHoldMs(2, 0));
        assertEquals(8000, Pacing.backoffHoldMs(3, 0));
        assertEquals(16000, Pacing.backoffHoldMs(4, 0));
        assertEquals(32000, Pacing.backoffHoldMs(5, 0));
    }

    @Test
    void jitterAddsUpToHalfBase() {
        assertEquals(3000, Pacing.backoffHoldMs(1, 1.0)); // 2000 + 50%
        long mid = Pacing.backoffHoldMs(2, 0.5);          // 4000 + 25% = 5000
        assertEquals(5000, mid);
    }

    @Test
    void capped() {
        // Level 5 with max jitter = 48s, still under cap; but the cap guards the formula.
        assertTrue(Pacing.backoffHoldMs(5, 1.0) <= Pacing.MAX_HOLD_MS);
        // Out-of-range levels clamp instead of overflowing the shift.
        assertEquals(Pacing.backoffHoldMs(5, 0), Pacing.backoffHoldMs(99, 0));
        assertEquals(Pacing.backoffHoldMs(1, 0), Pacing.backoffHoldMs(0, 0));
        assertEquals(Pacing.backoffHoldMs(1, 0), Pacing.backoffHoldMs(-3, 0));
    }

    // ---- fatigueScale ----

    @Test
    void freshSessionIsUnscaled() {
        assertEquals(1.0, Pacing.fatigueScale(0.0));
        assertEquals(1.0, Pacing.fatigueScale(0.5));
    }

    @Test
    void driftsTenPercentPerHour() {
        assertEquals(1.05, Pacing.fatigueScale(1.0), 1e-9);  // 0.5h past the grace = +5%
        assertEquals(1.25, Pacing.fatigueScale(3.0), 1e-9);
    }

    @Test
    void cappedAtOnePointFive() {
        assertEquals(1.5, Pacing.fatigueScale(6.0), 1e-9);
        assertEquals(1.5, Pacing.fatigueScale(100.0), 1e-9);
    }
}
