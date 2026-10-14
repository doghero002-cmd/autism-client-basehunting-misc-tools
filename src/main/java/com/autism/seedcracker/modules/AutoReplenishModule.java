package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.ContainerMutex;
import com.autism.seedcracker.util.InvSync;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/**
 * Auto Replenish (Water client AutoReplenish port).
 *
 * When your held stack runs low (or out), refills it: prefers switching to another hotbar slot
 * holding the same item; otherwise pulls the next stack from the main inventory into the hotbar
 * with a 3-click pickup-swap (pick up, drop into hotbar slot, put remainder back). Clicks are
 * spread over ticks so it stays human-paced. Keeps tunnel-bot hotbar slots (obsidian, pearls,
 * food) topped up without opening the inventory.
 */
public final class AutoReplenishModule extends Module {

    private final IntSetting threshold = add(new IntSetting("threshold", "Refill at count", 1, 1, 63, 1)
        .description("Refill when the held stack falls to this many items (or empty).")
        .group("General"));
    private final BoolSetting onlyBlocks = add(new BoolSetting("only-blocks", "Only blocks", false)
        .description("Only replenish placeable block items.")
        .group("General"));
    private final BoolSetting allowSwap = add(new BoolSetting("allow-hotbar-swap", "Prefer hotbar swap", true)
        .description("Switch to another hotbar slot with the same item before pulling from the inventory.")
        .group("General"));
    private final IntSetting clickDelay = add(new IntSetting("click-delay", "Click delay (ticks)", 2, 1, 10, 1)
        .description("Ticks between the pickup-swap clicks.")
        .group("General"));

    private int cooldown = 0;
    private int trackedSlot = -1;
    private ItemStack lastHeld = ItemStack.EMPTY;
    private net.minecraft.client.multiplayer.ClientLevel trackedWorld;

    public AutoReplenishModule() {
        super(SeedcrackerAddon.ID + ":auto-replenish", "Auto Replenish",
            "Refills your held stack from the hotbar or inventory when it runs low.");
    }

    @Override
    public void onEnable() {
        resetTracking();
    }

    private void resetTracking() {
        trackedSlot = -1;
        lastHeld = ItemStack.EMPTY;
        trackedWorld = null;
        cooldown = 0;
    }

    @Override
    public void onDisable() {
        resetTracking();
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
        resetTracking();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (trackedWorld != mc.level) { resetTracking(); trackedWorld = mc.level; }
        if (mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu
            || !mc.player.inventoryMenu.getCarried().isEmpty() || mc.player.isUsingItem()
            || mc.gameMode.isDestroying() || ContainerMutex.containerBusy(mc)) return;
        if (cooldown > 0) { cooldown--; return; }

        AbstractContainerMenu menu = mc.player.inventoryMenu;
        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack held = mc.player.getMainHandItem();
        if (trackedSlot != selected) { trackedSlot = selected; lastHeld = ItemStack.EMPTY; }
        if (!held.isEmpty()) lastHeld = held.copy();
        ItemStack reference = held.isEmpty() ? lastHeld : held;
        boolean low = held.isEmpty() || held.getCount() <= threshold.get();
        if (!low) return;
        if (reference.isEmpty() || reference.getMaxStackSize() <= 1) return;
        if (onlyBlocks.get() && !(reference.getItem() instanceof BlockItem)) return;

        // 1) Another hotbar slot with the same item: just switch (cheapest, zero clicks).
        if (allowSwap.get()) {
            for (int i = 0; i < 9; i++) {
                if (i == selected) continue;
                ItemStack s = mc.player.getInventory().getItem(i);
                if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, reference) && s.getCount() > held.getCount()) {
                    InvSync.select(mc, i);
                    cooldown = com.autism.seedcracker.util.Humanizer.delay(5);
                    return;
                }
            }
        }

        // 2) A vanilla SWAP is atomic and never leaves our stack on the carried cursor.
        for (int inv = 9; inv < 36; inv++) {
            ItemStack s = mc.player.getInventory().getItem(inv);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, reference) || s.getCount() <= held.getCount()) continue;
            if (!com.autism.seedcracker.util.pure.InventoryPolicy.validReplenishSwap(inv, selected)
                || !com.autism.seedcracker.util.ActionPacer.tryAction()) return;
            ContainerMutex.notifyContainerAction();
            mc.gameMode.handleContainerInput(menu.containerId, inv, selected, ContainerInput.SWAP, mc.player);
            cooldown = com.autism.seedcracker.util.Humanizer.delay(clickDelay.get());
            return;
        }
    }

    @Override
    public String info() {
        return cooldown > 0 ? "refilling" : "";
    }
}
