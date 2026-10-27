package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DonutFallStateTest {
    private static DonutFallState falling() {
        var state = new DonutFallState();
        state.observeFall(3.1);
        state.observeFall(4.0);
        return state;
    }

    @Test void triggerUsesThePreviousSampleAndIsStrictlyOverThree() {
        var state = new DonutFallState();
        state.observeFall(3.0);
        state.observeFall(3.0);
        assertNull(state.prepare(true));
        state.observeFall(3.1);
        assertFalse(state.active());
        state.observeFall(4.0);
        assertTrue(state.active());
    }

    @Test void exactlyFourConfirmedGroundPacketsEndTheFallAndClearItsHistory() {
        var state = falling();
        for (int n = 1; n <= 4; n++) {
            var adjustment = state.prepare(true);
            assertEquals(n, adjustment.groundedPackets());
            assertEquals(n % 2 == 1 ? 1.0E-8 : 0.0, adjustment.yOffset());
            state.sent(adjustment);
            assertEquals(n < 4, state.active());
        }
        state.observeFall(0.0);
        assertNull(state.prepare(true));
    }

    @Test void airbornePacketsDoNotCountAsLandingConfirmations() {
        var state = falling();
        for (int i = 0; i < 30; i++) state.sent(state.prepare(false));
        assertTrue(state.active());
        assertEquals(1, state.prepare(true).groundedPackets());
    }

    @Test void unsentOrRepeatedAcknowledgementsCannotSkipGroundPackets() {
        var state = falling();
        var failed = state.prepare(true);
        assertEquals(1, state.prepare(true).groundedPackets());
        state.sent(failed);
        state.sent(failed);
        assertEquals(2, state.prepare(true).groundedPackets());
    }

    @Test void disableDisconnectCorrectionAndRespawnDiscardOutstandingDecisions() {
        var state = falling();
        var stale = state.prepare(true);
        state.reset();
        state.observeFall(8);
        state.observeFall(9);
        state.sent(stale);
        assertEquals(1, state.prepare(true).groundedPackets());
        state.reset();
        assertFalse(state.active());
        assertNull(state.prepare(true));
    }

    @Test void invalidDistanceFailsClosed() {
        for (double value : new double[] {Double.NaN, Double.POSITIVE_INFINITY, -1.0}) {
            var state = falling();
            state.observeFall(value);
            assertFalse(state.active());
            state.observeFall(0.0);
            assertFalse(state.active());
        }
    }
}
