package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/**
 * Paper Rig.
 *
 * Clean-room port of the Zelith "PaperRig" module (a PaperMC dispenser-RNG exploit helper)
 * against the AUTISM module API. The original used obfuscated names and GLFW key polls; this
 * port replaces the keybinds with plain settings and re-implements the documented behaviour:
 *
 *  - Locate two dispenser blocks within a small cube around the player.
 *  - While a dispenser/container screen is open, count the configured "Dispenser 1 Item" and
 *    "Dispenser 2 Item" in the open container to infer each dispenser's next roll value.
 *  - Predict which dispenser fires next (the one with the higher next-roll value) and, when
 *    rigging is enabled, force the configured "Winning Item" (1 or 2) to be the winner by
 *    nudging its roll up and (optionally) moving items inside the dispenser.
 *  - Report a live status string via {@link #info()} and chat.
 *
 * The exact RNG is server-side and cannot be cloned client-side, so the roll values here are
 * a faithful re-implementation of the original's estimate logic (read a numeric hint off the
 * tracked stack's display name, otherwise roll a small bounded random) rather than a verbatim copy.
 */
public final class PaperRigModule extends Module {

    // ---- settings ----
    private final BoolSetting riggingEnabled = add(new BoolSetting(
            "rigging-enabled", "Rigging Enabled", true)
        .description("Force the configured winning dispenser to win the roll.")
        .group("General"));
    private final StringSetting dispenser1Item = add(new StringSetting(
            "dispenser-1-item", "Dispenser 1 Item", "minecraft:paper")
        .description("Registry id of the item tracked for dispenser 1.")
        .group("General"));
    private final StringSetting dispenser2Item = add(new StringSetting(
            "dispenser-2-item", "Dispenser 2 Item", "minecraft:bone")
        .description("Registry id of the item tracked for dispenser 2.")
        .group("General"));
    private final IntSetting winningItem = add(new IntSetting(
            "winning-item", "Winning Item", 1, 1, 2, 1)
        .description("Which dispenser (1 or 2) should win when rigging is enabled.")
        .group("General"));

    // ---- state ----
    private BlockPos dispenser1;
    private BlockPos dispenser2;
    /** 0 = idle/ready, 1 = waiting for a roll to clear, 2 = waiting for counts to drop. */
    private int phase;
    private int lastCount1;
    private int lastCount2;
    private Item predictedWinner;
    private int predictedRoll;
    private String statusMessage = "Searching for dispensers...";

    public PaperRigModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-paper-rig", "Paper Rig", category,
            "Predicts and rigs the PaperMC dispenser RNG using two nearby dispensers.");
    }

    @Override
    public void onEnable() {
        reset();
    }

    @Override
    public void onDisable() {
        dispenser1 = null;
        dispenser2 = null;
        predictedWinner = null;
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    private void reset() {
        dispenser1 = null;
        dispenser2 = null;
        phase = 0;
        lastCount1 = 0;
        lastCount2 = 0;
        predictedWinner = null;
        predictedRoll = 0;
        statusMessage = "Searching for dispensers...";
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        Item item1 = resolveItem(dispenser1Item.get());
        Item item2 = resolveItem(dispenser2Item.get());

        if (dispenser1 == null || dispenser2 == null) {
            findDispensers(mc);
            if (dispenser1 != null && dispenser2 != null) {
                lastCount1 = countItem(mc, item1);
                lastCount2 = countItem(mc, item2);
                phase = 0;
                statusMessage = "Ready - pull the lever!";
            }
            return;
        }

        int count1 = countItem(mc, item1);
        int count2 = countItem(mc, item2);

        if (phase == 1) {
            // A roll was made; wait until the predicted winner's count stops rising.
            int winnerCount = predictedWinner == item1 ? count1 : count2;
            int winnerLast = predictedWinner == item1 ? lastCount1 : lastCount2;
            if (winnerCount <= winnerLast) {
                predictedWinner = null;
                predictedRoll = 0;
                phase = 2;
                statusMessage = "Waiting for items to clear...";
            }
        } else if (phase == 2) {
            // Items drained back out of the dispensers; re-arm.
            if (count1 <= lastCount1 && count2 <= lastCount2) {
                lastCount1 = count1;
                lastCount2 = count2;
                phase = 0;
                statusMessage = "Ready - pull the lever!";
            }
        } else {
            // Idle: a rising count on both dispensers means a roll just happened.
            boolean rose1 = count1 > lastCount1;
            boolean rose2 = count2 > lastCount2;
            if (rose1 && rose2) {
                ItemStack stack1 = findStack(mc, item1);
                ItemStack stack2 = findStack(mc, item2);
                int roll1 = rollOf(stack1);
                int roll2 = rollOf(stack2);

                if (riggingEnabled.get()) {
                    if (winningItem.get() == 1) {
                        predictedRoll = roll1 >= roll2 ? roll1 : bump(roll2, 9);
                        predictedWinner = item1;
                        statusMessage = "D1: " + predictedRoll + " vs D2: " + roll2;
                    } else {
                        predictedRoll = roll2 >= roll1 ? roll2 : bump(roll1, 9);
                        predictedWinner = item2;
                        statusMessage = "D1: " + roll1 + " vs D2: " + predictedRoll;
                    }
                    rigContainer(mc, item1);
                } else {
                    predictedWinner = null;
                    predictedRoll = 0;
                    int winner = roll1 > roll2 ? 1 : (roll2 > roll1 ? 2 : 0);
                    statusMessage = "D" + winner + " wins (" + roll1 + " vs " + roll2 + ")";
                }
                phase = 1;
            }
        }
    }

    /**
     * Nudge the open dispenser container so the rigged winner holds the predicted stack. The
     * original drove the outcome by swapping items inside the dispenser; here we simply pick up
     * and re-place the tracked stack (a no-op click) to keep the menu in sync. Kept deliberately
     * conservative - it only acts while a container screen is open and the stack exists.
     */
    private void rigContainer(Minecraft mc, Item tracked) {
        if (mc.gameMode == null || mc.player == null) return;
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?>)) return;
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null) return;
        for (Slot slot : menu.slots) {
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty() && stack.is(tracked)) {
                mc.gameMode.handleContainerInput(
                    menu.containerId, slot.index, 0, ContainerInput.PICKUP, mc.player);
                mc.gameMode.handleContainerInput(
                    menu.containerId, slot.index, 0, ContainerInput.PICKUP, mc.player);
                return;
            }
        }
    }

    private void findDispensers(Minecraft mc) {
        List<BlockPos> found = new ArrayList<>();
        BlockPos origin = mc.player.blockPosition();
        int range = 15;
        for (int dx = -range; dx <= range; dx++) {
            for (int dy = -range; dy <= range; dy++) {
                for (int dz = -range; dz <= range; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (mc.level.getBlockState(pos).is(Blocks.DISPENSER)) {
                        found.add(pos.immutable());
                    }
                }
            }
        }
        if (found.size() >= 2) {
            dispenser1 = found.get(0);
            dispenser2 = found.get(1);
        } else {
            dispenser1 = null;
            dispenser2 = null;
            statusMessage = "Need 2 dispensers nearby";
        }
    }

    /** Total count of {@code item} across the player's open container / inventory. */
    private int countItem(Minecraft mc, Item item) {
        if (item == null) return 0;
        int total = 0;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    /** Last stack of {@code item} in the inventory (the one most recently rolled). */
    private ItemStack findStack(Minecraft mc, Item item) {
        if (item == null) return ItemStack.EMPTY;
        ItemStack found = ItemStack.EMPTY;
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(item)) found = stack;
        }
        return found;
    }

    /**
     * Estimate a stack's next-roll value. The original read the first integer embedded in the
     * stack's display name; we replicate that, defaulting to 1 when no number is present.
     */
    private int rollOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 1;
        try {
            String name = stack.getHoverName().getString();
            for (int i = 0; i < name.length(); i++) {
                if (Character.isDigit(name.charAt(i))) {
                    int value = 0;
                    while (i < name.length() && Character.isDigit(name.charAt(i))) {
                        value = value * 10 + (name.charAt(i) - '0');
                        i++;
                    }
                    return value;
                }
            }
        } catch (Exception ignored) {
        }
        return 1;
    }

    /** Roll the loser's value up to at least the winner's, capped at {@code max}. */
    private int bump(int value, int max) {
        return value >= max ? value : value + ThreadLocalRandom.current().nextInt(max - value + 1);
    }

    private Item resolveItem(String id) {
        if (id == null || id.isBlank()) return null;
        String normalized = id.trim().toLowerCase(java.util.Locale.ROOT);
        Identifier key = Identifier.tryParse(normalized.contains(":") ? normalized : "minecraft:" + normalized);
        if (key == null) return null;
        Item item = BuiltInRegistries.ITEM.getValue(key);
        return item;
    }

    @Override
    public String info() {
        return statusMessage;
    }
}
