package com.autism.seedcracker.motion;

import com.autism.seedcracker.compat.ClientInventory;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.ContainerMutex;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Keeps what the planner may use within reach: pulls throwaway blocks and a water bucket from the
 * main inventory into the hotbar. Only while standing still on the ground with no screen open,
 * one swap per paced action, and never over a hotbar slot holding a tool, weapon or food.
 */
final class MotionInventory {
    private MotionInventory() {}

    /** Throwaways we want reachable before a plan that may bridge or pillar. */
    private static final int WANT_BLOCKS = 32;
    private static int cooldown;

    /** One step of tidying; true if a swap was sent this tick (caller should hold still). */
    static boolean tick(Minecraft mc, boolean wantBlocks, boolean wantBucket) {
        if (mc.player == null || mc.gameMode == null || mc.gui.screen() != null) return false;
        if (--cooldown > 0) return false;
        // 2b2t-style checks flag inventory clicks while moving.
        if (!mc.player.onGround() || mc.player.getDeltaMovement().horizontalDistanceSqr() > 1e-4) return false;
        if (ContainerMutex.containerBusy(mc)) return false;
        int from = -1;
        if (wantBucket && hotbarHas(mc, Items.WATER_BUCKET) < 0) from = mainSlot(mc, Items.WATER_BUCKET);
        if (from < 0 && wantBlocks && BridgeBlocks.count(mc) < WANT_BLOCKS) from = mainThrowaway(mc);
        if (from < 0) return false;
        int to = freeHotbarSlot(mc);
        if (to < 0 || !ActionPacer.tryAction()) return false;
        ContainerMutex.notifyContainerAction();
        ClientInventory.swapInventorySlots(mc, from, to);
        cooldown = 10;
        return true;
    }

    private static int hotbarHas(Minecraft mc, net.minecraft.world.item.Item item) {
        for (int i = 0; i < 9; i++) if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        return -1;
    }

    private static int mainSlot(Minecraft mc, net.minecraft.world.item.Item item) {
        for (int i = 9; i < 36; i++) if (mc.player.getInventory().getItem(i).getItem() == item) return i;
        return -1;
    }

    private static int mainThrowaway(Minecraft mc) {
        int best = -1, bestCount = 0;
        for (int i = 9; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (BridgeBlocks.isThrowaway(s) && s.getCount() > bestCount) {
                best = i;
                bestCount = s.getCount();
            }
        }
        return best;
    }

    /** Empty hotbar slot, else one holding junk we'd happily swap out; never the selected slot. */
    private static int freeHotbarSlot(Minecraft mc) {
        int sel = mc.player.getInventory().getSelectedSlot();
        for (int i = 8; i >= 0; i--) if (i != sel && mc.player.getInventory().getItem(i).isEmpty()) return i;
        for (int i = 8; i >= 0; i--) {
            if (i == sel) continue;
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.has(net.minecraft.core.component.DataComponents.TOOL) || s.has(net.minecraft.core.component.DataComponents.WEAPON)
                || s.has(net.minecraft.core.component.DataComponents.FOOD) || BridgeBlocks.isThrowaway(s)
                || s.getItem() == Items.WATER_BUCKET || s.getItem() == Items.BUCKET || s.getMaxStackSize() == 1) continue;
            // Totems, pearls, gapples, potions, named or enchanted items are things people keep on the hotbar on purpose.
            if (s.getItem() == Items.TOTEM_OF_UNDYING || s.getItem() == Items.ENDER_PEARL || s.getItem() == Items.GOLDEN_APPLE
                || s.getItem() == Items.ENCHANTED_GOLDEN_APPLE || s.getItem() == Items.FIREWORK_ROCKET || s.getItem() == Items.OBSIDIAN
                || s.getItem() == Items.END_CRYSTAL || s.isEnchanted()
                || s.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)
                || s.has(net.minecraft.core.component.DataComponents.POTION_CONTENTS)) continue;
            return i;
        }
        return -1;
    }
}
