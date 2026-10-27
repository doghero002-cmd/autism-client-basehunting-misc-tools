package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RenderDistanceCycleTest {
    private final RenderDistanceCycle cycle = new RenderDistanceCycle();

    @Test
    void lowersRenderDistanceAtThresholdAndRestoresAfterConfiguredHold() {
        assertEquals(OptionalInt.of(2), tick(16, -1, 20));
        for (int i = 1; i < 20; i++) assertTrue(tick(2, -1, 20).isEmpty());
        assertEquals(OptionalInt.of(16), tick(2, -1, 20));
        assertFalse(cycle.ownsDistance());
        assertTrue(tick(16, -1, 20).isEmpty());
    }

    @Test
    void rearmRequiresStrictlyMoreThanTwoBlocksAboveThreshold() {
        tick(16, -1, 5);
        cycle.release(2);
        assertTrue(tick(16, 1, 5).isEmpty());
        assertTrue(tick(16, -1, 5).isEmpty());
        assertTrue(tick(16, 2, 5).isEmpty());
        assertEquals(OptionalInt.of(2), tick(16, -1, 5));
    }

    @Test
    void manualChangeWinsDuringHoldAndOnLaterCleanup() {
        tick(16, -1, 20);
        assertTrue(tick(10, -1, 20).isEmpty());
        assertFalse(cycle.ownsDistance());
        assertTrue(cycle.release(10).isEmpty());
        assertTrue(tick(10, -1, 20).isEmpty());
    }

    @Test
    void disableRestoresOnlyTheStillOwnedValueExactlyOnce() {
        tick(16, -1, 20);
        assertEquals(OptionalInt.of(16), cycle.release(2));
        assertTrue(cycle.release(2).isEmpty());
    }

    @Test
    void disableDoesNotOverwriteUserChangeWithoutAnInterveningTick() {
        tick(16, -1, 20);
        assertTrue(cycle.release(24).isEmpty());
    }

    @Test
    void worldCleanupDoesNotCarryThePreviousBaselineIntoANewSession() {
        tick(16, -1, 20);
        assertEquals(OptionalInt.of(16), cycle.release(2));
        cycle.reset();
        assertEquals(OptionalInt.of(2), tick(24, -1, 20));
        assertEquals(OptionalInt.of(24), cycle.release(2));
    }

    @Test
    void alreadyAtLowDistanceDoesNotAcquireOwnershipOrSpendPacerBudget() {
        assertTrue(cycle.tick(2, -1, -1, 2, 20, () -> fail("unnecessary action")).isEmpty());
        assertFalse(cycle.ownsDistance());
        assertTrue(cycle.release(2).isEmpty());
    }

    @Test
    void pacingDefersStartButNeverBlocksCleanup() {
        AtomicInteger attempts = new AtomicInteger();
        assertTrue(cycle.tick(16, -1, -1, 2, 5, () -> {
            attempts.incrementAndGet();
            return false;
        }).isEmpty());
        assertFalse(cycle.ownsDistance());
        assertEquals(OptionalInt.of(2), tick(16, -1, 5));
        assertEquals(1, attempts.get());
        for (int i = 1; i < 5; i++) {
            assertTrue(cycle.tick(2, -1, -1, 2, 5, () -> fail("cleanup must bypass pacer")).isEmpty());
        }
        assertEquals(OptionalInt.of(16),
            cycle.tick(2, -1, -1, 2, 5, () -> fail("cleanup must bypass pacer")));
    }

    @Test
    void settingsChangedMidCycleCannotChangeTheOwnedValueOrHold() {
        tick(16, -1, 5);
        for (int i = 1; i < 5; i++) {
            assertTrue(cycle.tick(2, -1, 40, 8, 200, () -> true).isEmpty());
        }
        assertEquals(OptionalInt.of(16), cycle.tick(2, -1, 40, 8, 200, () -> true));
    }

    @Test
    void invalidLowDistanceAndHoldAreBounded() {
        assertEquals(OptionalInt.of(2), cycle.tick(16, -1, -1, -500, -1, () -> true));
        for (int i = 1; i < 5; i++) assertTrue(tick(2, -1, 20).isEmpty());
        assertEquals(OptionalInt.of(16), tick(2, -1, 20));
    }

    private OptionalInt tick(int current, int y, int hold) {
        return cycle.tick(current, y, -1, 2, hold, () -> true);
    }
}
