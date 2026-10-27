package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RiptideCycleTest {
    @Test void noReleaseWithoutRealInputRiptideOrAcceptedWaterConditions() {
        var cycle = new RiptideCycle();
        assertFalse(cycle.canRelease(100, 20, 10, false, true, true));
        assertFalse(cycle.canRelease(100, 20, 10, true, false, true));
        assertFalse(cycle.canRelease(100, 20, 10, true, true, false));
        assertTrue(cycle.canRelease(100, 20, 10, true, true, true));
    }

    @Test void requiredChargeRemainsIndependentOfRepeatCooldown() {
        var cycle = new RiptideCycle();
        assertFalse(cycle.canRelease(100, 9, 10, true, true, true));
        assertTrue(cycle.canRelease(100, 10, 10, true, true, true));
        assertFalse(cycle.canRelease(100, 8, 1, true, true, true));
        assertTrue(cycle.canRelease(100, 9, 1, true, true, true));
    }

    @Test void pacerDenialDoesNotRecordAReleaseAndNineTickSpacingIsExact() {
        var cycle = new RiptideCycle();
        assertTrue(cycle.canRelease(100, 10, 10, true, true, true));
        // The module records only after the paced native release succeeded.
        assertTrue(cycle.canRelease(101, 11, 10, true, true, true));
        cycle.released(101);
        assertFalse(cycle.canRelease(101, 10, 10, true, true, true));
        assertFalse(cycle.canRelease(109, 10, 10, true, true, true));
        assertTrue(cycle.canRelease(110, 10, 10, true, true, true));
    }

    @Test void lettingGoCannotCircumventTheCooldown() {
        var cycle = new RiptideCycle();
        cycle.released(100);
        assertFalse(cycle.canRelease(101, 10, 10, false, true, true));
        assertFalse(cycle.canRelease(102, 10, 10, true, true, true));
    }

    @Test void disableAndWorldReplacementResetTheOldTickCounter() {
        var cycle = new RiptideCycle();
        cycle.released(10000);
        assertFalse(cycle.canRelease(10, 10, 10, true, true, true));
        cycle.reset();
        cycle.reset();
        assertTrue(cycle.canRelease(10, 10, 10, true, true, true));
    }
}
