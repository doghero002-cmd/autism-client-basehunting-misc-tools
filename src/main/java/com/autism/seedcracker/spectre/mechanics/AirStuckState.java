package com.autism.seedcracker.spectre.mechanics;

/** Session-bound latch for Spectre's manual AirStuck; a new world never inherits a freeze. */
public final class AirStuckState {
    private Object world;
    private Object player;

    public boolean arm(Object world, Object player) {
        clear();
        if (world == null || player == null) return false;
        this.world = world;
        this.player = player;
        return true;
    }

    public boolean activeFor(Object world, Object player) {
        if (this.world == null || this.player == null) return false;
        if (this.world != world || this.player != player) {
            clear();
            return false;
        }
        return true;
    }

    public boolean cancels(Object world, Object player, boolean movement, boolean tickEnd) {
        return activeFor(world, player) && (movement || tickEnd);
    }

    public void clear() {
        world = null;
        player = null;
    }
}
