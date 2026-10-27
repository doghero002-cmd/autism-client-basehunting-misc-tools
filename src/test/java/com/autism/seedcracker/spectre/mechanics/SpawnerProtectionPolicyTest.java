/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpawnerProtectionPolicyTest {
    @Test void remoteDetectionExcludesSpawnSelfShulkersAndFriendlyBreaks() {
        assertTrue(SpawnerProtectionPolicy.remoteBreak(false, 30, false, true, false, false, 100, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(true, 30, false, true, false, false, 100, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(false, -64, false, true, false, false, 100, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(false, 30, false, true, true, false, 100, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(false, 30, false, true, false, true, 100, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(false, 30, false, true, false, false, 35, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(false, 30, true, true, false, false, 100, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(false, 30, false, false, false, false, 100, 100, 64));
        assertFalse(SpawnerProtectionPolicy.remoteBreak(false, 30, false, true, false, false, 100, Double.NaN, 64));
    }
    @Test void detectionWindowDeduplicatesPacketsAndResetsAcrossSilenceOrSession() {
        var p = new SpawnerProtectionPolicy();
        assertFalse(p.recordBreak(1, 10, 2, 1)); assertFalse(p.recordBreak(1, 20, 2, 1));
        assertTrue(p.recordBreak(2, 30, 2, 1)); p.clear();
        assertFalse(p.recordBreak(3, 40, 2, 1));
        assertFalse(p.recordBreak(4, 2_000_000_040L, 2, 1));
        assertTrue(p.recordBreak(5, 2_000_000_050L, 2, 1));
    }
    @Test void whitelistRetainsLegacyDelimitersAndCaseInsensitivity() {
        var names = SpawnerProtectionPolicy.whitelist("  Alice;BOB,Carol  alice\nDave ");
        assertEquals(java.util.Set.of("alice", "bob", "carol", "dave"), names);
        assertTrue(SpawnerProtectionPolicy.whitelist(null).isEmpty());
    }
    @Test void shopTransitionRequiresExpectedTitleAndButtonAndNeverPlayerSlots() {
        assertTrue(SpawnerProtectionPolicy.shopButton(1, "SHOP", "The End", false));
        assertTrue(SpawnerProtectionPolicy.shopButton(2, "END", "Ender Chest", true));
        assertTrue(SpawnerProtectionPolicy.shopButton(3, "ENDER CHEST", "Confirm purchase", false));
        assertFalse(SpawnerProtectionPolicy.shopButton(3, "Your Ender Chest", "Diamond", false));
        assertFalse(SpawnerProtectionPolicy.shopButton(1, "Inventory", "The End", false));
        assertFalse(SpawnerProtectionPolicy.shopButton(2, "END", "Stone", false));
        assertFalse(SpawnerProtectionPolicy.shopButton(0, "SHOP", "The End", false));
        assertEquals(11, SpawnerProtectionPolicy.shopSlot(1));
        assertEquals(9, SpawnerProtectionPolicy.shopSlot(2));
        assertEquals(25, SpawnerProtectionPolicy.shopSlot(3));
        assertEquals(-1, SpawnerProtectionPolicy.shopSlot(4));
    }
    @Test void movementCannotLeakAcrossScreensPacerHoldDisableOrManualInput() {
        assertTrue(SpawnerProtectionPolicy.mayDrive(true, true, false, false, false));
        assertFalse(SpawnerProtectionPolicy.mayDrive(false, true, false, false, false));
        assertFalse(SpawnerProtectionPolicy.mayDrive(true, false, false, false, false));
        assertFalse(SpawnerProtectionPolicy.mayDrive(true, true, true, false, false));
        assertFalse(SpawnerProtectionPolicy.mayDrive(true, true, false, true, false));
        assertFalse(SpawnerProtectionPolicy.mayDrive(true, true, false, false, true));
    }
    @Test void internalPacketsDoNotCancelButUserPacketsInterruptWithoutBeingCancelled() {
        assertTrue(SpawnerProtectionPolicy.manualActionInterrupts(true, false, true));
        assertFalse(SpawnerProtectionPolicy.manualActionInterrupts(true, true, true));
        assertFalse(SpawnerProtectionPolicy.manualActionInterrupts(false, false, true));
        assertFalse(SpawnerProtectionPolicy.manualActionInterrupts(true, false, false));
    }
}
