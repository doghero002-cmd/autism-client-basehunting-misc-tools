// SPDX-License-Identifier: GPL-3.0-only
// Adapted from https://github.com/cody-raves/Spectre, commit d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
// Upstream: src/main/java/net/spectre/client/util/staff/ShardTracker.java
package com.autism.seedcracker.spectre.staff;


import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.Level;

public final class ShardTracker {
    private Snapshot previous;

    public boolean moved(Minecraft mc) {
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            clear();
            return false;
        }
        boolean died = previous != null && previous.player() instanceof LocalPlayer player && player.isDeadOrDying();
        int region = mc.level.dimension() == Level.OVERWORLD
            ? DonutGoliathMapData.goliathAt(mc.player.getX(), mc.player.getZ()) : -1;
        return update(new Snapshot(mc.level, mc.player, mc.getConnection(), mc.getConnection().serverBrand(), region), died);
    }

    boolean update(Snapshot next, boolean previousPlayerDied) {
        boolean changed = previous != null && next != null && (
            next.level() != previous.level() || next.connection() != previous.connection()
                || next.player() != previous.player() && !previousPlayerDied
                || next.region() != previous.region()
                || next.brand() != null && previous.brand() != null && !next.brand().equals(previous.brand()));
        // A transient missing brand must not erase the last known shard identity.
        if (next != null && previous != null && next.brand() == null
            && next.level() == previous.level() && next.connection() == previous.connection()) {
            next = new Snapshot(next.level(), next.player(), next.connection(), previous.brand(), next.region());
        }
        previous = next;
        return changed;
    }

    public void clear() {
        previous = null;
    }

    record Snapshot(Object level, Object player, Object connection, String brand, int region) {}
}
