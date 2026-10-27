/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtectionSessionTest {
    @Test void worldPlayerAndConnectionMustAllMatchByIdentity() {
        var s = new ProtectionSession(); Object world = new Object(), player = new Object(), connection = new Object();
        s.bind(world, player, connection);
        assertTrue(s.owns(world, player, connection));
        assertFalse(s.owns(new Object(), player, connection));
        assertFalse(s.owns(world, new Object(), connection));
        assertFalse(s.owns(world, player, new Object()));
    }
    @Test void disableOrRelogInvalidatesOldEvidenceAndSlotLease() {
        var s = new ProtectionSession(); Object w = new Object(), p = new Object(), c = new Object();
        s.bind(w, p, c); Object old = s.token(); assertTrue(s.select(3, 5));
        s.clear(); assertNotSame(old, s.token()); assertFalse(s.isBound());
        assertEquals(-1, s.restoreSlot(w, p, c, 5));
        s.bind(w, p, c); assertNotSame(old, s.token());
    }
    @Test void restoresOriginalSlotOnlyWhileStillOwnedInSameSession() {
        var s = new ProtectionSession(); Object w = new Object(), p = new Object(), c = new Object();
        s.bind(w, p, c); assertTrue(s.select(3, 5)); assertTrue(s.select(5, 7));
        assertEquals(3, s.restoreSlot(w, p, c, 7)); assertEquals(-1, s.restoreSlot(w, p, c, 7));
    }
    @Test void manualSlotChangeRevokesLeaseWithoutOverwritingSelection() {
        var s = new ProtectionSession(); Object w = new Object(), p = new Object(), c = new Object();
        s.bind(w, p, c); assertTrue(s.select(3, 5)); assertFalse(s.select(8, 2));
        assertEquals(-1, s.restoreSlot(w, p, c, 8));
    }
    @Test void deathRespawnAndDimensionChangeCannotRestoreIntoAnotherPlayer() {
        var s = new ProtectionSession(); Object w = new Object(), p = new Object(), c = new Object();
        s.bind(w, p, c); assertTrue(s.select(3, 5));
        assertEquals(-1, s.restoreSlot(w, new Object(), c, 5));
    }
    @Test void explicitManualSelectionRevokesLeaseEvenWhenIndexIsUnchanged() {
        var s = new ProtectionSession(); Object w = new Object(), p = new Object(), c = new Object();
        s.bind(w, p, c); assertTrue(s.select(3, 5)); s.releaseSlot();
        assertEquals(-1, s.restoreSlot(w, p, c, 5));
    }
    @Test void invalidHotbarIndicesAndUnboundClaimsAreRejected() {
        var s = new ProtectionSession(); assertFalse(s.select(0, 1));
        s.bind(new Object(), new Object(), new Object()); assertFalse(s.select(0, 9)); assertFalse(s.select(-1, 2));
    }
}
