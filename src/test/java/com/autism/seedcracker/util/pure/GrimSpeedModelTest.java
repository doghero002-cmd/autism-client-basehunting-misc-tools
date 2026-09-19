package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GrimSpeedModelTest {

    private static final float GROUND = 0.31f;
    private static final float AIR = 0.341f;

    @Test
    void baseThresholds() {
        assertEquals(GROUND, GrimSpeedModel.threshold(true, 0, 10, false, false, false, false, GROUND, AIR), 1e-6);
        assertEquals(AIR, GrimSpeedModel.threshold(false, 0, 0, false, false, false, false, GROUND, AIR), 1e-6);
    }

    @Test
    void speedEffectScalesWithGroundTicks() {
        // First 5 ground ticks use the bigger 0.06 modifier (jump boost window).
        double fresh = GrimSpeedModel.threshold(true, 2, 3, false, false, false, false, GROUND, AIR);
        double settled = GrimSpeedModel.threshold(true, 2, 10, false, false, false, false, GROUND, AIR);
        assertEquals(GROUND + 2 * 0.06f, fresh, 1e-5);
        assertEquals(GROUND + 2 * 0.046f, settled, 1e-5);
        assertTrue(fresh > settled);
    }

    @Test
    void multipliersStack() {
        double stairs = GrimSpeedModel.threshold(true, 0, 10, false, true, false, false, GROUND, AIR);
        assertEquals(GROUND * 1.8f, stairs, 1e-5);
        double ice = GrimSpeedModel.threshold(true, 0, 3, false, false, true, false, GROUND, AIR);
        assertEquals(GROUND * 1.7f, ice, 1e-5);
        double underBlock = GrimSpeedModel.threshold(true, 0, 10, false, false, false, true, GROUND, AIR);
        assertEquals(GROUND * 2.0f, underBlock, 1e-5);
    }

    @Test
    void iceOnlyAppliesInJumpWindow() {
        // Ice multiplier requires groundTicks < 5 (Grim's model).
        double iceSettled = GrimSpeedModel.threshold(true, 0, 10, false, false, true, false, GROUND, AIR);
        assertEquals(GROUND, iceSettled, 1e-6);
    }

    @Test
    void bufferRisesAndDrains() {
        int b = 0;
        b = GrimSpeedModel.updateBuffer(b, 0.5, 0.31); // over
        b = GrimSpeedModel.updateBuffer(b, 0.5, 0.31); // over
        assertEquals(2, b);
        b = GrimSpeedModel.updateBuffer(b, 0.1, 0.31); // under
        assertEquals(1, b);
        b = GrimSpeedModel.updateBuffer(b, 0.1, 0.31);
        b = GrimSpeedModel.updateBuffer(b, 0.1, 0.31);
        assertEquals(0, b, "buffer must floor at 0");
    }

    @Test
    void tickStateBoundedAndDecays() {
        int t = 0;
        for (int i = 0; i < 30; i++) t = GrimSpeedModel.updateTickState(t, true, 3);
        assertEquals(60, t, "tick state caps at 60");
        for (int i = 0; i < 100; i++) t = GrimSpeedModel.updateTickState(t, false, 3);
        assertEquals(0, t, "tick state decays to 0, never negative");
    }
}
