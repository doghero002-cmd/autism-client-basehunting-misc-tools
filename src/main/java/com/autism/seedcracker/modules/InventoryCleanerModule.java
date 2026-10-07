package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.ContainerMutex;
import com.autism.seedcracker.util.Humanizer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringListSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Inventory Cleaner.
 *
 * Throws configured junk items out of your inventory when it's nearly full, keeping room for
 * valuables while mining/looting. One throw per humanized delay (never a one-tick dump), only
 * when no screen is open, respecting the global action budget. Hotbar slots are left alone by
 * default so it can't throw your tools.
 */
public final class InventoryCleanerModule extends Module {

    private final StringListSetting junk = add(new StringListSetting("junk", "Junk items",
            "minecraft:cobblestone|minecraft:cobbled_deepslate|minecraft:dirt|minecraft:gravel|"
            + "minecraft:netherrack|minecraft:tuff|minecraft:diorite|minecraft:andesite|minecraft:granite")
        .description("Item ids (| separated) treated as junk.").group("Filter"))
        ;
    private final IntSetting minFreeSlots = add(new IntSetting("min-free", "Min free slots", 3, 0, 18, 1)
        .description("Start cleaning when fewer than this many inventory slots are free (0 = always clean).")
        .group("General"));
    private final IntSetting throwDelay = add(new IntSetting("delay", "Throw delay (ticks)", 8, 2, 40, 1)
        .description("Base ticks between throws (jittered).").group("General"));
    private final BoolSetting keepHotbar = add(new BoolSetting("keep-hotbar", "Keep hotbar", true)
        .description("Never throw from hotbar slots (protects tools/blocks you use).").group("General"));

    private int cooldown = 0;
    private java.util.Set<Item> cachedJunk = java.util.Set.of();
    private int reparseTicks = 0;

    public InventoryCleanerModule() {
        super(SeedcrackerAddon.ID + ":inventory-cleaner", "Inventory Cleaner",
            "Throws junk items when your inventory fills up (paced, hotbar-safe).");
    }

    @Override
    public void onEnable() {
        cooldown = 0;
        reparseTicks = 0;
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        // Never throw with a screen open (dropping from an open chest desyncs its menu).
        if (mc.gui.screen() != null || mc.player.containerMenu != mc.player.inventoryMenu) return;
        if (cooldown > 0) { cooldown--; return; }

        if (--reparseTicks <= 0) {
            reparseTicks = 20;
            cachedJunk = parseJunk();
        }
        if (cachedJunk.isEmpty()) return;

        // Count free slots (main inventory 0..35).
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) free++;
        }
        if (free >= minFreeSlots.get() && minFreeSlots.get() > 0) return;

        // One junk stack per pass: inventory slots 9..35 (menu ids match), hotbar 0..8 -> 36..44.
        int start = keepHotbar.get() ? 9 : 0;
        for (int inv = start; inv < 36; inv++) {
            ItemStack s = mc.player.getInventory().getItem(inv);
            if (s.isEmpty() || !cachedJunk.contains(s.getItem())) continue;
            if (!ActionPacer.tryAction()) { cooldown = 2; return; }
            int menuSlot = inv < 9 ? 36 + inv : inv;
            ContainerMutex.notifyContainerAction();
            // THROW with button 1 = whole stack.
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,
                menuSlot, 1, ContainerInput.THROW, mc.player);
            cooldown = Humanizer.delay(throwDelay.get());
            return;
        }
    }

    private java.util.Set<Item> parseJunk() {
        java.util.Set<Item> out = new java.util.HashSet<>();
        java.util.List<String> raw = junk.get();
        if (raw == null) return out;
        for (String id : raw) {
            String trimmed = id == null ? "" : id.trim();
            if (trimmed.isEmpty()) continue;
            Identifier ident = Identifier.tryParse(trimmed.contains(":") ? trimmed : "minecraft:" + trimmed);
            if (ident == null) continue;
            BuiltInRegistries.ITEM.getOptional(ident).ifPresent(out::add);
        }
        return out;
    }

    @Override
    public String info() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return "";
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getItem(i).isEmpty()) free++;
        }
        return free + " free";
    }
}
