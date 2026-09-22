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

    // 3-click move state: 0 = idle, 1..3 = pickup/place/putback steps.
    private int moveStep = 0;
    private int moveFromSlot = -1;
    private int moveToSlot = -1;
    private int cooldown = 0;

    public AutoReplenishModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":auto-replenish", "Auto Replenish", category,
            "Refills your held stack from the hotbar or inventory when it runs low.");
    }

    @Override
    public void onEnable() {
        moveStep = 0;
        moveFromSlot = -1;
        moveToSlot = -1;
        cooldown = 0;
    }

    @Override
    public void onDisable() {
        moveStep = 0;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        // Never fight an open container screen (chest stealer / shop flows own the clicks there).
        if (mc.gui.screen() != null && moveStep == 0) return;
        if (cooldown > 0) { cooldown--; return; }

        AbstractContainerMenu menu = mc.player.inventoryMenu;

        // Mid-move: finish the 3-click sequence regardless of held-stack state.
        if (moveStep > 0) {
            switch (moveStep) {
                case 1 -> { clickSlot(mc, menu, moveFromSlot); moveStep = 2; }
                case 2 -> { clickSlot(mc, menu, moveToSlot); moveStep = 3; }
                case 3 -> {
                    // Put any remainder back where it came from (empty cursor no-ops).
                    clickSlot(mc, menu, moveFromSlot);
                    moveStep = 0;
                    moveFromSlot = -1;
                    moveToSlot = -1;
                }
            }
            cooldown = clickDelay.get();
            return;
        }

        int selected = mc.player.getInventory().getSelectedSlot();
        ItemStack held = mc.player.getMainHandItem();
        boolean low = held.isEmpty() || held.getCount() <= threshold.get();
        if (!low) return;
        if (held.isEmpty()) return; // nothing to match against - don't guess what to hold
        if (onlyBlocks.get() && !(held.getItem() instanceof BlockItem)) return;

        // 1) Another hotbar slot with the same item: just switch (cheapest, zero clicks).
        if (allowSwap.get()) {
            for (int i = 0; i < 9; i++) {
                if (i == selected) continue;
                ItemStack s = mc.player.getInventory().getItem(i);
                if (!s.isEmpty() && ItemStack.isSameItemSameComponents(s, held) && s.getCount() > held.getCount()) {
                    InvSync.select(mc, i);
                    cooldown = 5;
                    return;
                }
            }
        }

        // 2) Pull from main inventory (slots 9..35) via pickup-swap into the held hotbar slot.
        for (int inv = 9; inv < 36; inv++) {
            ItemStack s = mc.player.getInventory().getItem(inv);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, held)) continue;
            // InventoryMenu slot ids: main inv 9..35 -> 9..35, hotbar 0..8 -> 36..44.
            moveFromSlot = inv;
            moveToSlot = 36 + selected;
            moveStep = 1;
            cooldown = clickDelay.get();
            return;
        }
    }

    private static void clickSlot(Minecraft mc, AbstractContainerMenu menu, int slot) {
        ContainerMutex.notifyContainerAction();
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.PICKUP, mc.player);
    }

    @Override
    public String info() {
        return moveStep > 0 ? "moving" : "";
    }
}
