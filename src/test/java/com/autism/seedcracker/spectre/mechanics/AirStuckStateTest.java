package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AirStuckStateTest {
    @Test void onlyMovementAndTickEndAreCancelledInTheArmedSession() {
        var state = new AirStuckState();
        Object world = new Object(), player = new Object();
        assertFalse(state.cancels(world, player, true, false));
        assertTrue(state.arm(world, player));
        assertTrue(state.cancels(world, player, true, false));
        assertTrue(state.cancels(world, player, false, true));
        assertFalse(state.cancels(world, player, false, false));
    }

    @Test void worldReplacementInvalidatesRatherThanAutomaticallyRearming() {
        var state = new AirStuckState();
        Object world = new Object(), next = new Object(), player = new Object();
        state.arm(world, player);
        assertFalse(state.activeFor(next, player));
        assertFalse(state.activeFor(world, player));
        assertFalse(state.activeFor(next, player));
        assertTrue(state.arm(next, player));
        assertTrue(state.activeFor(next, player));
    }

    @Test void respawnAndDisconnectCannotKeepTheOldPlayer() {
        var state = new AirStuckState();
        Object world = new Object(), player = new Object();
        state.arm(world, player);
        assertFalse(state.activeFor(world, new Object()));
        assertFalse(state.activeFor(world, player));
        state.arm(world, player);
        assertFalse(state.activeFor(null, null));
        assertFalse(state.activeFor(world, player));
    }

    @Test void failedEnableAndRepeatedDisableAreSafe() {
        var state = new AirStuckState();
        Object world = new Object(), player = new Object();
        state.arm(world, player);
        assertFalse(state.arm(world, null));
        assertFalse(state.activeFor(world, player));
        state.clear();
        state.clear();
        assertFalse(state.arm(null, player));
    }
}
