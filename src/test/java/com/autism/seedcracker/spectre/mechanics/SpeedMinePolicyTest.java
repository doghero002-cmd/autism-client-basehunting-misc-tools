package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeedMinePolicyTest {
    @Test void spawnersAirAndAxeShovelBlocksAreExcludedButGravelIsNot() {
        assertFalse(SpeedMinePolicy.affects(true, false, false, false, false));
        assertFalse(SpeedMinePolicy.affects(false, true, false, false, false));
        assertFalse(SpeedMinePolicy.affects(false, false, false, true, false));
        assertFalse(SpeedMinePolicy.affects(false, false, false, false, true));
        assertTrue(SpeedMinePolicy.affects(false, false, true, true, false));
        assertTrue(SpeedMinePolicy.affects(false, false, false, false, false));
    }

    @Test void scalesOnlyPositiveEligibleProgressAtTheConfiguredThreshold() {
        assertEquals(1.0f, SpeedMinePolicy.scale(true, 0.7f, 0.7), 0.00001f);
        assertEquals(0.7f, SpeedMinePolicy.scale(false, 0.7f, 0.3));
        assertEquals(0.0f, SpeedMinePolicy.scale(true, 0.0f, 0.3));
        assertEquals(-1.0f, SpeedMinePolicy.scale(true, -1.0f, 0.3));
        assertEquals(0.7f, SpeedMinePolicy.scale(true, 0.7f, Double.NaN));
        assertEquals(1.0f, SpeedMinePolicy.scale(true, 0.3f, 0.0), 0.00001f);
    }

    @Test void instantDelaySettingAndSkipToggleBothAffectTheDecision() {
        assertTrue(SpeedMinePolicy.dropsDelay(true, 0.1f, true, true));
        assertFalse(SpeedMinePolicy.dropsDelay(true, 1.0f, true, true));
        assertTrue(SpeedMinePolicy.dropsDelay(true, 1.0f, true, false));
        assertFalse(SpeedMinePolicy.dropsDelay(true, 0.1f, false, false));
        assertFalse(SpeedMinePolicy.dropsDelay(false, 0.1f, true, false));
    }

    @Test void unbreakableAndInvalidProgressNeverRemoveDelay() {
        for (float progress : new float[] {0, -1, Float.NaN, Float.POSITIVE_INFINITY}) {
            assertFalse(SpeedMinePolicy.dropsDelay(true, progress, true, false));
        }
    }
}
