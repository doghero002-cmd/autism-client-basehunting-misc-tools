/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrapRemovalPolicyTest {
    @Test void preventSpawnDoesNotErasePreexistingEntitiesWhenRemoveExistingIsOff() {
        var p = new TrapRemovalPolicy(); var session = new Object(); var existing = UUID.randomUUID();
        p.bind(session); assertFalse(p.observe(session, existing, false, true, true)); p.finishFrame();
        assertFalse(p.observe(session, existing, false, true, true));
        assertTrue(p.observe(session, UUID.randomUUID(), false, true, false));
    }
    @Test void removeExistingRespectsPeriodicSweepAndBothTogglesOffIsNoOp() {
        var p = new TrapRemovalPolicy(); var s = new Object(); var id = UUID.randomUUID(); p.bind(s);
        assertTrue(p.observe(s, id, true, false, false)); p.finishFrame();
        assertFalse(p.observe(s, id, true, false, false));
        assertTrue(p.observe(s, id, true, false, true));
        assertFalse(p.observe(s, UUID.randomUUID(), false, false, true));
    }
    @Test void reconnectAndReusedNumericEntityIdsCannotUseOldQueue() {
        var p = new TrapRemovalPolicy(); var s = new Object(); p.bind(s); p.finishFrame();
        var next = new Object(); p.bind(next);
        assertFalse(p.observe(s, UUID.randomUUID(), true, true, true));
        assertFalse(p.observe(next, UUID.randomUUID(), false, true, false));
        p.clear(); assertFalse(p.observe(next, UUID.randomUUID(), true, true, true));
    }
    @Test void cameraPassengerRemovedAndOtherWorldEntitiesAreProtected() {
        assertTrue(TrapRemovalPolicy.removable(true, false, false, false, true));
        assertFalse(TrapRemovalPolicy.removable(true, true, false, false, true));
        assertFalse(TrapRemovalPolicy.removable(true, false, true, false, true));
        assertFalse(TrapRemovalPolicy.removable(true, false, false, true, true));
        assertFalse(TrapRemovalPolicy.removable(false, false, false, false, true));
        assertFalse(TrapRemovalPolicy.removable(true, false, false, false, false));
    }
}
