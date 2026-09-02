package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.DoubleSetting;
import autismclient.mixin.accessor.AutismMinecraftAccessor;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ExperienceBottleItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Fast Place.
 *
 * Lowers the vanilla right-click / block-place cooldown so you place blocks and use items
 * faster. Only kicks in while you are holding the use key with a valid item, no screen is open,
 * and the held item passes the configured filters (blocks vs. items, XP-bottle-only mode).
 * Skips food/consumables and a few items that behave badly when spam-placed.
 *
 * Clean-room port of the obfuscated Zelith "FastPlace" module.
 */
public final class FastPlaceModule extends Module {

    private final BoolSetting onlyXp = add(new BoolSetting("only-xp", "Only XP bottles", false)
        .description("Only speed up throwing XP bottles.")
        .group("General"));
    private final BoolSetting blocks = add(new BoolSetting("blocks", "Blocks", true)
        .description("Speed up placing blocks.")
        .group("General"));
    private final BoolSetting items = add(new BoolSetting("items", "Items", true)
        .description("Speed up using non-block items.")
        .group("General"));
    private final DoubleSetting delay = add(new DoubleSetting("delay", "Delay", 0.0, 0.0, 10.0, 1.0)
        .description("Right-click cooldown in ticks (lower = faster).")
        .group("General"));

    public FastPlaceModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-fast-place", "Fast Place", category,
            "Reduces the block-place / item-use delay so you place faster.");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        // Don't interfere while a screen (inventory/chat/etc.) is open.
        if (mc.gui.screen() != null) return;
        if (!mc.options.keyUse.isDown()) return;

        ItemStack main = mc.player.getMainHandItem();
        ItemStack off = mc.player.getOffhandItem();
        if (!applies(main, off)) return;

        int target = Math.max(0, delay.get().intValue());
        AutismMinecraftAccessor accessor = (AutismMinecraftAccessor) mc;
        if (accessor.autism$getRightClickDelay() != target) {
            accessor.autism$setRightClickDelay(target);
        }
    }

    /** Whether the module should speed up use given the items in both hands. */
    private boolean applies(ItemStack main, ItemStack off) {
        boolean mainXp = main.is(Items.EXPERIENCE_BOTTLE);
        boolean offXp = off.is(Items.EXPERIENCE_BOTTLE);
        if (onlyXp.get()) {
            return mainXp || offXp;
        }
        // Never speed up food/consumables.
        if (isConsumable(main) || isConsumable(off)) return false;
        // Skip wind charges / fire charges style items that misfire when spammed.
        Item mainItem = main.getItem();
        Item offItem = off.getItem();
        if (mainItem instanceof ExperienceBottleItem || offItem instanceof ExperienceBottleItem) {
            return true;
        }
        boolean isBlock = mainItem instanceof BlockItem || offItem instanceof BlockItem;
        return isBlock ? blocks.get() : items.get();
    }

    private static boolean isConsumable(ItemStack stack) {
        return !stack.isEmpty() && stack.has(DataComponents.FOOD);
    }
}
