package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.IntSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Bone Dropper.
 *
 * Periodically drops a chosen item from your inventory (default bones) - e.g. to feed a
 * spawner or clear stock. Simplified port of the Zelith "BoneDropper" module: the original's
 * spawner-opening automation is reduced to a straightforward timed drop.
 */
public final class BoneDropperModule extends Module {

    private final IntSetting delayMs = add(new IntSetting("delay", "Delay (ms)", 300, 50, 5000, 50)
        .description("Milliseconds between drops.")
        .group("General"));
    private final StringSetting itemName = add(new StringSetting("item", "Item id", "minecraft:bone")
        .description("Item to drop (registry id, e.g. minecraft:bone).")
        .group("General"));
    private final IntSetting amount = add(new IntSetting("amount", "Amount", 1, 1, 64, 1)
        .description("How many to drop each time.")
        .group("General"));

    private long nextDropMs = 0L;

    public BoneDropperModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-bone-dropper", "Bone Dropper", category,
            "Periodically drops a chosen item from your inventory.");
    }

    @Override
    public void onEnable() {
        nextDropMs = 0L;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (mc.gui.screen() != null) return; // don't drop while a screen is open

        long now = System.currentTimeMillis();
        if (now < nextDropMs) return;

        Item target = resolveItem();
        if (target == null) return;

        int slot = findItem(mc, target);
        if (slot < 0) {
            nextDropMs = now + 1000L; // nothing to drop; check again soon
            return;
        }

        dropFromSlot(mc, slot, Math.max(1, amount.get()));
        nextDropMs = now + Math.max(50, delayMs.get());
    }

    private Item resolveItem() {
        String id = itemName.get().trim().toLowerCase(java.util.Locale.ROOT);
        if (id.isEmpty()) return Items.BONE;
        Item item = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getValue(net.minecraft.resources.Identifier.parse(id.contains(":") ? id : "minecraft:" + id));
        return item == null ? Items.BONE : item;
    }

    private int findItem(Minecraft mc, Item target) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && stack.is(target)) return i;
        }
        return -1;
    }

    private void dropFromSlot(Minecraft mc, int slot, int count) {
        var inv = mc.player.getInventory();
        int selected = inv.getSelectedSlot();
        if (slot <= 8) {
            // Hotbar: select then drop.
            inv.setSelectedSlot(slot);
            for (int i = 0; i < count; i++) mc.player.drop(false);
            inv.setSelectedSlot(selected);
        } else {
            // Main inventory: move to hotbar, drop, move back.
            autismclient.util.AutismInventoryHelper.swapInventorySlots(mc, slot, selected);
            for (int i = 0; i < count; i++) mc.player.drop(false);
        }
    }
}
