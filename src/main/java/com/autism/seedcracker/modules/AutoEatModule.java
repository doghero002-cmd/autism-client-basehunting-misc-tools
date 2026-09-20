package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Auto Eat.
 *
 * Automatically eats food from your hotbar when your hunger drops to (or below) the configured
 * threshold. It picks the best hotbar food according to the chosen priority (nutrition,
 * saturation, or both), swaps to that slot, holds use until you finish eating, then swaps back to
 * your original slot.
 *
 * Clean-room port of the obfuscated Zelith "AutoEat" module.
 */
public final class AutoEatModule extends Module {

    public enum Priority { COMBINED, HUNGER, SATURATION }

    private final IntSetting hunger = add(new IntSetting("hunger", "Hunger", 16, 1, 19, 1)
        .description("Eat when your food level is at or below this.")
        .group("General"));
    private final EnumSetting<Priority> priority = add(new EnumSetting<>(
            "priority", "Priority", Priority.SATURATION, Priority.values())
        .description("How to pick the best food: combined, hunger, or saturation.")
        .group("General"));

    private boolean eating;
    private int foodSlot = -1;
    private int prevSlot = -1;

    public AutoEatModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-auto-eat", "Auto Eat", category,
            "Automatically eats hotbar food when your hunger is low.");
    }

    @Override
    public void onDisable() {
        if (eating) {
            stopEating();
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui.screen() != null) {
            // GUI opened mid-bite: release use but KEEP the eating state so we resume seamlessly
            // when it closes (aborting here left the previous slot never restored).
            if (eating) mc.options.keyUse.setDown(false);
            return;
        }

        if (eating) {
            // Finished (well fed) - only stop once the swallow completes so the last bite counts.
            if (!isHungry() && !mc.player.isUsingItem()) {
                stopEating();
                return;
            }
            // Never hot-swap mid-bite: an in-progress consume is cancelled by a slot change,
            // wasting the whole animation. Let the current item finish first.
            if (!mc.player.isUsingItem() && !isFood(mc.player.getInventory().getItem(foodSlot))) {
                int next = findFoodSlot();
                if (next == -1) {
                    stopEating();
                    return;
                }
                selectSlot(next);
            }
            keepEating(mc);
        } else if (isHungry()) {
            startEating(mc);
        }
    }

    private boolean isHungry() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        int food = mc.player.getFoodData().getFoodLevel();
        // Hysteresis: start at the threshold, but once eating keep going to (threshold+4, cap 20).
        // Stopping the instant we tick over the threshold wastes bites and re-triggers constantly.
        int stopAt = Math.min(20, hunger.get() + 4);
        return eating ? food < stopAt : (food <= hunger.get() && food < 20);
    }

    private void startEating(Minecraft mc) {
        int slot = findFoodSlot();
        if (slot == -1) return;
        prevSlot = mc.player.getInventory().getSelectedSlot();
        selectSlot(slot);
        keepEating(mc);
    }

    private void selectSlot(int slot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        com.autism.seedcracker.util.InvSync.select(mc, slot);
        foodSlot = slot;
    }

    private void keepEating(Minecraft mc) {
        if (foodSlot < 0 || foodSlot > 8 || mc.gameMode == null) return;
        // Single input path: hold the real use key and let the vanilla input loop fire the
        // use-item. Also calling gameMode.useItem() here DOUBLE-fires (key loop + manual packet
        // in the same tick), which desyncs the consume and can flag packet-order checks.
        mc.options.keyUse.setDown(true);
        eating = true;
    }

    private void stopEating() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            int selected = mc.player.getInventory().getSelectedSlot();
            if (prevSlot >= 0 && prevSlot <= 8 && prevSlot != selected) {
                com.autism.seedcracker.util.InvSync.select(mc, prevSlot);
            }
        }
        mc.options.keyUse.setDown(false);
        eating = false;
        foodSlot = -1;
        prevSlot = -1;
    }

    /** Best hotbar food slot for the configured priority, or -1 if none. */
    private int findFoodSlot() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return -1;
        int best = -1;
        float bestValue = -1.0f;
        for (int slot = 0; slot <= 8; slot++) {
            ItemStack stack = mc.player.getInventory().getItem(slot);
            if (!isFood(stack)) continue;
            FoodProperties food = stack.get(DataComponents.FOOD);
            if (food == null) continue;
            float value = switch (priority.get()) {
                case COMBINED -> (float) food.nutrition() + food.saturation();
                case HUNGER -> (float) food.nutrition();
                case SATURATION -> food.saturation();
            };
            if (value > bestValue) {
                bestValue = value;
                best = slot;
            }
        }
        return best;
    }

    private static boolean isFood(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }
}
