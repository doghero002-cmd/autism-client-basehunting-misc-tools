package com.autism.seedcracker.util;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

/**
 * Container-open mutex.
 *
 * Anti-cheat (Grim PacketOrder / NCP inventory) flags any movement, attack, or block-break packet
 * sent while a container GUI is open (or within a couple ticks of a container click) - the client
 * is interacting with a menu, so simultaneous world interaction is impossible. This helper gives
 * every module a single check to suspend world actions while a container is open or was just used.
 *
 * {@link #containerBusy(Minecraft)} returns true while the world should NOT be touched. Modules
 * call it at the top of their tick / before any movement/attack/dig/place, and bail if true.
 */
public final class ContainerMutex {
    private ContainerMutex() {}

    /** Ticks after the last container interaction during which world actions stay suspended. */
    private static final int GRACE_TICKS = 2;
    private static long lastContainerActionMs = 0L;

    /** True if a container screen is open OR a container action happened within the grace window. */
    public static boolean containerBusy(Minecraft mc) {
        if (mc == null) return false;
        if (mc.gui.screen() instanceof AbstractContainerScreen<?>) return true;
        return System.currentTimeMillis() - lastContainerActionMs < GRACE_TICKS * 50L;
    }

    /** Call whenever a container click / handleContainerInput happens, to extend the grace window. */
    public static void notifyContainerAction() {
        lastContainerActionMs = System.currentTimeMillis();
    }
}
