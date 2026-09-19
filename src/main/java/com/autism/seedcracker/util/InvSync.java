package com.autism.seedcracker.util;

import net.minecraft.client.Minecraft;

/**
 * Hotbar-select that keeps the server in sync.
 *
 * Calling {@code mc.player.getInventory().setSelectedSlot(slot)} only changes the client-side
 * slot - the server still thinks you're holding the old item until it happens to see a carried-
 * item packet, so an immediate place/mine right after the swap desyncs (and flags Grim/NCP).
 * {@link autismclient.util.AutismInventoryHelper#selectHotbarSlot} sets the slot AND calls the
 * AUTISM {@code ensureHasSentCarriedItem} hook so the carried-item packet is sent immediately.
 *
 * Use {@link #select(Minecraft, int)} everywhere instead of {@code setSelectedSlot}.
 */
public final class InvSync {
    private InvSync() {}

    private static boolean fallbackLogged = false;

    /** Select a hotbar slot (0-8) and sync the carried item to the server. */
    public static void select(Minecraft mc, int slot) {
        if (mc == null || mc.player == null) return;
        if (slot < 0 || slot > 8) return;
        // Grim BadPacketsA: a duplicate SetCarriedItem packet for the ALREADY-selected slot is an
        // instant flag (vanilla never re-sends it). Only ensure the sync, don't re-select.
        if (mc.player.getInventory().getSelectedSlot() == slot) {
            try {
                ((autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor) mc.gameMode)
                    .autism$ensureHasSentCarriedItem();
            } catch (Throwable ignored) {}
            return;
        }
        try {
            autismclient.util.AutismInventoryHelper.selectHotbarSlot(mc, slot);
        } catch (Throwable t) {
            // Client-only fallback DESYNCS the held item server-side (flag risk) - log once.
            if (!fallbackLogged) {
                fallbackLogged = true;
                FlagLog.flag("ERROR", "InvSync", "selectHotbarSlot failed, client-only slot set: " + t);
            }
            mc.player.getInventory().setSelectedSlot(slot);
        }
    }
}
