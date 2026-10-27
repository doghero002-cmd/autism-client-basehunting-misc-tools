package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;

import static com.autism.seedcracker.spectre.mechanics.AutoRelogCycle.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class AutoRelogCycleTest {
    private final AutoRelogCycle cycle = new AutoRelogCycle();

    @Test
    void requiresFortyHighTicksThenStrictlyBelowTheLine() {
        for (int i = 0; i < 39; i++) assertFalse(observe(4));
        assertFalse(observe(-1));
        assertEquals(0, cycle.aboveTicks());
        arm();
        assertFalse(observe(0));
        assertTrue(observe(-0.01));
        assertFalse(observe(-1));
    }

    @Test
    void hysteresisBandRetainsButDoesNotIncreaseUpstreamArmingCount() {
        for (int i = 0; i < 20; i++) observe(4);
        for (int i = 0; i < 100; i++) observe(2);
        assertEquals(20, cycle.aboveTicks());
        for (int i = 0; i < 20; i++) observe(4);
        assertTrue(observe(-1));
    }

    @Test
    void invalidWorldOrNonfiniteYResetsArming() {
        arm();
        assertFalse(cycle.observe(false, -1, 0, () -> fail("ineligible")));
        assertFalse(observe(-1));
        arm();
        assertFalse(observe(Double.NaN));
        assertFalse(observe(-1));
    }

    @Test
    void pacerCanDeferCrossingWithoutCreatingAPendingReconnect() {
        arm();
        assertFalse(cycle.observe(true, -1, 0, () -> false));
        assertFalse(cycle.pending());
        assertTrue(observe(-1));
    }

    @Test
    void waitStartsOnlyAfterTheOriginalWorldAndConnectionAreGone() {
        cycle.begin(0, 3);
        cycle.observeTeardown(4_000, true, false, true);
        assertEquals(DISCONNECTING, cycle.phase());
        cycle.observeTeardown(5_000, true, true, true);
        assertEquals(WAITING, cycle.phase());
        assertFalse(cycle.reconnectDue(7_999, true, () -> true));
        assertTrue(cycle.reconnectDue(8_000, true, () -> true));
    }

    @Test
    void stalledTeardownIsBoundedAndNeverReconnects() {
        cycle.begin(0, 3);
        cycle.observeTeardown(10_000, true, false, true);
        assertEquals(WATCHING, cycle.phase());
        assertFalse(cycle.reconnectDue(20_000, true, () -> true));
        assertTrue(cycle.stopReason().contains("timed out"));
    }

    @Test
    void changedConnectionDuringTeardownConsumesTheAttempt() {
        cycle.begin(0, 3);
        cycle.observeTeardown(1_000, false, false, true);
        cycle.observeTeardown(2_000, true, true, true);
        assertFalse(cycle.pending());
        assertFalse(cycle.reconnectDue(5_000, true, () -> true));
    }

    @Test
    void userNavigationCannotRearmAReconnectByReturningToTheScreen() {
        waiting();
        assertFalse(cycle.reconnectDue(1_000, false, () -> true));
        assertFalse(cycle.reconnectDue(3_000, true, () -> true));
        assertEquals(WATCHING, cycle.phase());
    }

    @Test
    void cancellingWhileWaitingOrConnectingNeverRetries() {
        waiting();
        cycle.cancel();
        assertFalse(cycle.reconnectDue(3_000, true, () -> true));
        waiting();
        assertTrue(cycle.reconnectDue(3_000, true, () -> true));
        cycle.cancel();
        assertFalse(cycle.pending());
        assertFalse(cycle.reconnectDue(30_000, true, () -> true));
        assertFalse(observe(-1));
    }

    @Test
    void onlyOneConnectionAttemptCanBeIssued() {
        waiting();
        assertTrue(cycle.reconnectDue(3_000, true, () -> true));
        for (int i = 0; i < 10; i++) assertFalse(cycle.reconnectDue(3_001 + i, true, () -> true));
        assertEquals(CONNECTING, cycle.phase());
    }

    @Test
    void pacerWaitAndConnectionAttemptHaveIndependentFiniteDeadlines() {
        waiting();
        assertFalse(cycle.reconnectDue(3_000, true, () -> false));
        assertFalse(cycle.reconnectDue(13_000, true, () -> true));
        assertFalse(cycle.pending());
        waiting();
        assertTrue(cycle.reconnectDue(3_000, true, () -> true));
        cycle.observeConnecting(62_999, true);
        assertEquals(CONNECTING, cycle.phase());
        cycle.observeConnecting(63_000, true);
        assertFalse(cycle.pending());
    }

    @Test
    void switchingServersDuringConnectionDoesNotStartAnotherAttempt() {
        waiting();
        cycle.reconnectDue(3_000, true, () -> true);
        cycle.observeConnecting(3_001, false);
        assertFalse(cycle.pending());
        assertFalse(cycle.reconnectDue(10_000, true, () -> true));
    }

    @Test
    void resetOnWorldChangeAndRejoinRequiresFreshArming() {
        arm();
        cycle.resetArming();
        assertFalse(observe(-1));
        arm();
        assertTrue(observe(-1));
        waiting();
        cycle.reconnectDue(3_000, true, () -> true);
        cycle.cancel();
        assertFalse(observe(-1));
        arm();
        assertTrue(observe(-1));
    }

    @Test
    void nonfiniteAndOutOfRangeDelayCannotCreateAnUnboundedWait() {
        cycle.begin(0, Double.NaN);
        cycle.observeTeardown(0, true, true, true);
        assertFalse(cycle.reconnectDue(2_999, true, () -> true));
        assertTrue(cycle.reconnectDue(3_000, true, () -> true));
        cycle.cancel();
        cycle.begin(0, 1_000_000);
        cycle.observeTeardown(0, true, true, true);
        assertFalse(cycle.reconnectDue(29_999, true, () -> true));
        assertTrue(cycle.reconnectDue(30_000, true, () -> true));
    }

    private boolean observe(double y) { return cycle.observe(true, y, 0, () -> true); }
    private void arm() { for (int i = 0; i < 40; i++) observe(4); }
    private void waiting() {
        cycle.begin(0, 3);
        cycle.observeTeardown(0, true, true, true);
    }
}
