/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

/** A disappearing source is not a successful batch; only conserved server transfers count. */
public final class SpawnerStorageProof {
    private int expected;
    private int confirmed;

    public void begin(int expected) {
        if (expected < 1) throw new IllegalArgumentException("A storage batch must contain items");
        this.expected = expected;
        confirmed = 0;
    }
    public void transferred(int serverConfirmedCount) {
        if (expected < 1 || serverConfirmedCount < 1) throw new IllegalStateException("No confirmed storage transfer");
        confirmed = Math.addExact(confirmed, serverConfirmedCount);
    }
    public boolean complete() { return expected > 0 && confirmed >= expected; }
    public void clear() { expected = confirmed = 0; }

    public static boolean placed(boolean blockPacket, boolean slotPacketAfterSend, boolean correctRemainingItem,
            int countBefore, int countAfter) {
        return blockPacket && slotPacketAfterSend && correctRemainingItem && countBefore > 0
            && countAfter >= 0 && countAfter == countBefore - 1;
    }
}
