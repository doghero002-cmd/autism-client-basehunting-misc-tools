package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GcdMathTest {

    private static final double SENS_DEFAULT = 0.5;

    @Test
    void stepMatchesVanillaFormula() {
        // (0.5*0.6+0.2)^3 * 8 * 0.15 = 0.5^3 * 1.2 = 0.15 (vanilla's float constants add ~1e-8)
        assertEquals(0.15, GcdMath.step(SENS_DEFAULT), 1e-6);
    }

    @Test
    void quantizeSnapsToGrid() {
        double step = GcdMath.step(SENS_DEFAULT);
        float q = GcdMath.quantize(10.07f, step);
        // Result must be an integer multiple of the step.
        assertEquals(0.0, Math.abs(q / step - Math.round(q / step)), 1e-4);
    }

    @Test
    void quantizeDeltaProducesIntegerMultipleDeltas() {
        double step = GcdMath.step(SENS_DEFAULT);
        var q = GcdMath.quantizeDelta(0f, 10.07f, 0.0, step, 2.0, true);
        double delta = q.angle();
        double counts = delta / step;
        assertEquals(Math.round(counts), counts, 1e-4, "delta must be integer mouse counts");
    }

    @Test
    void remainderCarriesAcrossCalls() {
        double step = GcdMath.step(SENS_DEFAULT); // 0.15
        // Ask for sub-step turns: each too small to move alone (0.06 < 0.075 rounds to 0),
        // but the carried remainder must eventually force a full step.
        float angle = 0f;
        double rem = 0.0;
        boolean moved = false;
        for (int i = 0; i < 10; i++) {
            var q = GcdMath.quantizeDelta(angle, angle + 0.06f, rem, step, 2.0, true);
            if (q.angle() != angle) moved = true;
            rem = q.remainder();
            angle = q.angle();
        }
        assertTrue(moved, "accumulated sub-GCD error must eventually produce a real step");
    }

    @Test
    void remainderIsClamped() {
        double step = GcdMath.step(SENS_DEFAULT);
        // Feed an absurd banked remainder; the output remainder must be clamped to +-2*step.
        var q = GcdMath.quantizeDelta(0f, 0.01f, 100.0, step, 2.0, true);
        assertTrue(Math.abs(q.remainder()) <= 2.0 * step + 1e-9);
    }

    @Test
    void yawWrapTakesShortestPath() {
        double step = GcdMath.step(SENS_DEFAULT);
        // 179 -> -179 should be a +2 deg turn (through the seam), not -358.
        var q = GcdMath.quantizeDelta(179f, -179f, 0.0, step, 2.0, true);
        double applied = q.angle() - 179f;
        assertTrue(Math.abs(applied - 2.0) < step, "wrap must go through the seam: " + applied);
    }

    @Test
    void pitchDoesNotWrap() {
        double step = GcdMath.step(SENS_DEFAULT);
        var q = GcdMath.quantizeDelta(80f, -80f, 0.0, step, 2.0, false);
        // Pitch must travel the full -160, never wrap.
        assertTrue(q.angle() < 0f);
        assertEquals(-80f, q.angle(), (float) step);
    }

    @Test
    void zeroStepPassesThrough() {
        var q = GcdMath.quantizeDelta(0f, 42f, 0.0, 0.0, 2.0, true);
        assertEquals(42f, q.angle());
        assertEquals(0.0, q.remainder());
    }
}
