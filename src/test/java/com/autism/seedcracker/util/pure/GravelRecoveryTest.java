package com.autism.seedcracker.util.pure;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class GravelRecoveryTest {
    @Test
    void persistentGravelRetreatsOnceThenClearsInsteadOfRearming() {
        var recovery = new GravelRecovery(6, 300);
        for (int tick = 0; tick < 6; tick++) assertEquals(GravelRecovery.Step.RETREAT, recovery.tick(true, true));
        for (int tick = 0; tick < 100; tick++) assertEquals(GravelRecovery.Step.CLEAR, recovery.tick(true, true));
    }

    @Test
    void unsafeBackstepStopsRatherThanWalkingIntoAnotherHazard() {
        assertEquals(GravelRecovery.Step.STOP, new GravelRecovery(6, 300).tick(true, false));
    }

    @Test
    void clearingDoesNotNeedTheOldRetreatPathToRemainOpen() {
        var recovery = new GravelRecovery(1, 300);
        assertEquals(GravelRecovery.Step.RETREAT, recovery.tick(true, true));
        assertEquals(GravelRecovery.Step.CLEAR, recovery.tick(true, false));
    }

    @Test
    void prolongedThreatTimesOutAndClearingRearmsForTheNextColumn() {
        var recovery = new GravelRecovery(1, 3);
        assertEquals(GravelRecovery.Step.RETREAT, recovery.tick(true, true));
        assertEquals(GravelRecovery.Step.CLEAR, recovery.tick(true, true));
        assertEquals(GravelRecovery.Step.CLEAR, recovery.tick(true, true));
        assertEquals(GravelRecovery.Step.STOP, recovery.tick(true, true));
        assertEquals(GravelRecovery.Step.IDLE, recovery.tick(false, true));
        assertEquals(GravelRecovery.Step.RETREAT, recovery.tick(true, true));
    }
}