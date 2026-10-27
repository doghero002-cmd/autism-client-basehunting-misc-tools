/* SPDX-License-Identifier: GPL-3.0-only */
package com.autism.seedcracker.spectre.mechanics;

/** Identity, not dimension names/entity ids, bounds actions to one world/player/connection. */
public final class ProtectionSession {
    private Object world;
    private Object player;
    private Object connection;
    private Object token = new Object();
    private int originalSlot = -1;
    private int ownedSlot = -1;

    public void bind(Object world, Object player, Object connection) {
        clear();
        this.world = java.util.Objects.requireNonNull(world);
        this.player = java.util.Objects.requireNonNull(player);
        this.connection = java.util.Objects.requireNonNull(connection);
    }

    public boolean owns(Object world, Object player, Object connection) {
        return this.world != null && this.world == world && this.player == player && this.connection == connection;
    }
    public Object token() { return token; }
    public boolean isBound() { return world != null; }

    /** Call before InvSync.select. A user/other module selecting another slot revokes the lease. */
    public boolean select(int current, int wanted) {
        if (world == null || current < 0 || current > 8 || wanted < 0 || wanted > 8) return false;
        if (ownedSlot >= 0 && current != ownedSlot) { releaseSlot(); return false; }
        if (originalSlot < 0) originalSlot = current;
        ownedSlot = wanted;
        return true;
    }

    public boolean slotUnchanged(int current) { return ownedSlot < 0 || current == ownedSlot; }
    public int restoreSlot(Object world, Object player, Object connection, int current) {
        int restore = owns(world, player, connection) && ownedSlot >= 0 && current == ownedSlot ? originalSlot : -1;
        releaseSlot();
        return restore;
    }
    public void releaseSlot() { originalSlot = ownedSlot = -1; }

    public void clear() {
        world = player = connection = null;
        token = new Object();
        releaseSlot();
    }
}
