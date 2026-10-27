/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.autism.seedcracker.spectre.mechanics.ProtectionInventoryTransaction.Result.*;

class ProtectionInventoryTransactionTest {
    private final Object session = new Object(), menu = new Object();
    private static ProtectionInventoryTransaction.Stack<String> s(String key, int count) {
        return count == 0 ? ProtectionInventoryTransaction.Stack.empty() : new ProtectionInventoryTransaction.Stack<>(key, count);
    }
    private ProtectionInventoryTransaction<String> deposit() {
        return new ProtectionInventoryTransaction<>(session, menu, 7, 8, 27, Set.of(0, 1),
            Map.of(0, s("spawner", 60), 1, s(null, 0), 27, s("spawner", 12), 28, s("diamond", 64)),
            String::equals, false, 100, 20);
    }
    private void slot(ProtectionInventoryTransaction<String> tx, int revision, int slot, String key, int count) {
        tx.observe(session, menu, 7, revision, slot, s(key, count), 102);
    }
    private ProtectionInventoryTransaction.Result poll(ProtectionInventoryTransaction<String> tx) {
        return tx.poll(session, menu, 7, 103);
    }

    @Test void predictedInventoryNeverConfirmsWithoutInboundEvidence() { assertEquals(PENDING, poll(deposit())); }
    @Test void sourceOnlyIsNotStorageConfirmation() {
        var tx = deposit(); slot(tx, 9, 27, null, 0); assertEquals(PENDING, poll(tx));
    }
    @Test void splitDestinationUpdatesMustConserveTheWholeTransfer() {
        var tx = deposit(); slot(tx, 9, 27, null, 0); slot(tx, 10, 0, "spawner", 64);
        assertEquals(PENDING, poll(tx)); assertEquals(0, tx.confirmedCount());
        slot(tx, 11, 1, "spawner", 8); assertEquals(CONFIRMED, poll(tx)); assertEquals(12, tx.confirmedCount());
    }
    @Test void destinationCanArriveBeforeSource() {
        var tx = deposit(); slot(tx, 9, 0, "spawner", 64); assertEquals(PENDING, poll(tx));
        slot(tx, 10, 27, "spawner", 8); assertEquals(CONFIRMED, poll(tx));
    }
    @Test void rejectedOrFullContainerTimesOutWithoutAdvancing() {
        var tx = deposit(); slot(tx, 9, 27, "spawner", 12);
        assertEquals(TIMED_OUT, tx.poll(session, menu, 7, 120));
    }
    @Test void packetAtDeadlineCannotResurrectTimeout() {
        var tx = deposit(); tx.observe(session, menu, 7, 9, 27, s(null, 0), 120);
        slot(tx, 10, 0, "spawner", 64); slot(tx, 11, 1, "spawner", 8); assertEquals(TIMED_OUT, poll(tx));
    }
    @Test void staleRevisionAndWrongContainerCannotConfirm() {
        var tx = deposit(); slot(tx, 8, 27, null, 0); slot(tx, 7, 0, "spawner", 64);
        tx.observe(session, menu, 8, 9, 1, s("spawner", 8), 102);
        assertEquals(PENDING, poll(tx));
    }
    @Test void newWorldOrReusedMenuIdCancels() {
        var tx = deposit(); assertEquals(CANCELLED, tx.poll(new Object(), menu, 7, 103));
        tx = deposit(); assertEquals(CANCELLED, tx.poll(session, new Object(), 7, 103));
    }
    @Test void oldSessionPacketsAreIgnored() {
        var tx = deposit(); tx.observe(new Object(), menu, 7, 9, 27, s(null, 0), 102);
        slot(tx, 10, 0, "spawner", 64); slot(tx, 11, 1, "spawner", 8); assertEquals(PENDING, poll(tx));
    }
    @Test void cancellationIsTerminal() {
        var tx = deposit(); tx.cancel(); slot(tx, 9, 27, null, 0); slot(tx, 10, 1, "spawner", 12);
        assertEquals(CANCELLED, poll(tx));
    }
    @Test void completedEvidenceCannotBeConsumedInAnotherWorld() {
        var tx = deposit(); slot(tx, 9, 27, null, 0); slot(tx, 10, 1, "spawner", 12);
        assertEquals(CONFIRMED, poll(tx));
        assertEquals(CANCELLED, tx.poll(new Object(), menu, 7, 104));
        assertEquals(0, tx.confirmedCount());
    }
    @Test void otherItemsAreNeverAcceptedAsTransferEvidence() {
        var tx = deposit(); slot(tx, 9, 27, null, 0); slot(tx, 10, 0, "diamond", 64);
        assertEquals(FAILED, poll(tx));
    }
    @Test void unrelatedSlotMutationAndCursorOwnershipFailClosed() {
        var tx = deposit(); slot(tx, 9, 28, "diamond", 63); assertEquals(FAILED, poll(tx));
        tx = deposit(); tx.observeCursor(false); assertEquals(FAILED, poll(tx));
    }
    @Test void swapRequiresBothServerSlotsAndAnEmptyDestination() {
        var tx = new ProtectionInventoryTransaction<>(session, menu, 0, 32767, 10, Set.of(36),
            Map.of(10, s("ender_chest", 1), 36, s(null, 0)), String::equals, true, 0, 20);
        tx.observe(session, menu, 0, 0, 36, s("ender_chest", 1), 1);
        assertEquals(PENDING, tx.poll(session, menu, 0, 2));
        tx.observe(session, menu, 0, 1, 10, s(null, 0), 2);
        assertEquals(CONFIRMED, tx.poll(session, menu, 0, 3));
        assertThrows(IllegalArgumentException.class, () -> new ProtectionInventoryTransaction<>(session, menu,
            0, 1, 10, Set.of(36), Map.of(10, s("ender_chest", 1), 36, s("diamond", 64)), String::equals, true, 0, 20));
    }
    @Test void revisionWrapAndDuplicatesAreHandledWithoutAcceptingOldData() {
        assertTrue(ProtectionInventoryTransaction.newerState(0, 32767));
        assertFalse(ProtectionInventoryTransaction.newerState(32767, 0));
        assertFalse(ProtectionInventoryTransaction.newerState(-1, 0));
        var tx = deposit(); slot(tx, 9, 0, "spawner", 64); slot(tx, 9, 0, "diamond", 64);
        slot(tx, 10, 27, "spawner", 8); assertEquals(CONFIRMED, poll(tx));
    }
}
