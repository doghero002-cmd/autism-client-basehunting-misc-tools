package com.autism.seedcracker.compat;

import net.minecraft.client.Minecraft;

/**
 * Obfuscation-proof inventory helpers. The client's AutismInventoryHelper util was renamed in
 * later (obfuscated) builds. selectHotbarSlot keeps the server in sync by setting the slot AND
 * calling the AUTISM ensureHasSentCarriedItem hook (which survived under its accessor name), with
 * a graceful client-only fallback if the hook shape ever changes. swapInventorySlots uses the
 * standard container-input packet path (pure Minecraft, no client dependency).
 */
public final class ClientInventory {
    private ClientInventory() {}

    /** Select a hotbar slot (0-8) and sync the carried item to the server. */
    public static void selectHotbarSlot(Minecraft mc, int slot) {
        if (mc == null || mc.player == null || mc.gameMode == null) return;
        if (slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
        try {
            ((autismclient.mixin.accessor.AutismMultiPlayerGameModeAccessor) mc.gameMode)
                .autism$ensureHasSentCarriedItem();
        } catch (Throwable ignored) {
            // Hook shape changed: the slot is set client-side; the next vanilla carried-item
            // packet still syncs it shortly after. Acceptable degradation.
        }
    }

    /** Swap two inventory slots via the standard container-input packet (PICKUP swap). Slots are
     * PLAYER-INVENTORY indices as used by mc.player.getInventory().getItem(slot): 0-8 = hotbar,
     * 9-35 = main inventory. The player inventory CONTAINER lays these out differently (hotbar is
     * slots 36-44, main is 9-35), so we map the index before sending the packet. */
    public static void swapInventorySlots(Minecraft mc, int fromSlot, int toSlot) {
        if (mc == null || mc.player == null || mc.gameMode == null) return;
        var menu = mc.player.inventoryMenu;
        int id = menu.containerId;
        int from = toContainerSlot(fromSlot);
        int to = toContainerSlot(toSlot);
        if (from < 0 || to < 0 || from >= menu.slots.size() || to >= menu.slots.size()) return;
        mc.gameMode.handleContainerInput(id, from, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(id, to, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
        mc.gameMode.handleContainerInput(id, from, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player);
    }

    /** Map a getInventory() index (0-8 hotbar, 9-35 main) to the player inventory-menu container
     * slot index. Crafting/armour/offhand indices are rejected (-1). */
    private static int toContainerSlot(int inventoryIndex) {
        if (inventoryIndex >= 0 && inventoryIndex <= 8) return 36 + inventoryIndex; // hotbar
        if (inventoryIndex >= 9 && inventoryIndex <= 35) return inventoryIndex;     // main inventory
        return -1;
    }
}
