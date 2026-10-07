package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.ContainerMutex;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringListSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Chest Stealer (Water client ChestStealer port, AutoStore's mirror image).
 *
 * While a container screen is open, shift-clicks (QUICK_MOVE) items OUT of the container into
 * your inventory - one click per delay tick so it looks like fast-but-human looting rather than
 * a 0-tick dump. Filters let you take everything, only listed items, or skip junk. Optionally
 * closes the screen when the container is empty (or nothing matches).
 */
public final class ChestStealerModule extends Module {

    public enum FilterMode { ALL, WHITELIST, BLACKLIST }

    private final EnumSetting<FilterMode> mode = add(new EnumSetting<>("mode", "Filter mode",
            FilterMode.ALL, FilterMode.values())
        .description("ALL = take everything, WHITELIST = only listed, BLACKLIST = all except listed.")
        .group("Filter"));
    private final StringListSetting filterItems = add(new StringListSetting("items", "Filter items",
            "minecraft:cobblestone|minecraft:dirt|minecraft:netherrack|minecraft:gravel")
        .description("Item ids (| separated) used by WHITELIST/BLACKLIST modes.").group("Filter"));
    private final IntSetting delayTicks = add(new IntSetting("delay", "Click delay (ticks)", 2, 1, 20, 1)
        .description("Ticks between loot clicks (2 = 10 stacks/s, human-fast).").group("General"));
    private final BoolSetting closeWhenDone = add(new BoolSetting("close-when-done", "Close when done", true)
        .description("Close the container once nothing lootable is left.").group("General"));
    private final BoolSetting requireChest = add(new BoolSetting("require-container", "Only real containers", true)
        .description("Only loot from container menus (not crafting/furnace UIs).").group("General"));

    private int cooldown = 0;

    public ChestStealerModule() {
        super(SeedcrackerAddon.ID + ":chest-stealer", "Chest Stealer",
            "Shift-loots items out of any open container (filtered, human-paced).");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (cooldown > 0) { cooldown--; return; }

        AbstractContainerMenu menu = mc.player.containerMenu;
        if (menu == null || menu instanceof InventoryMenu) return; // no container open
        if (mc.gui.screen() == null) return; // menu desync guard: only act while the screen is up

        // Don't assume "last 36 slots = player inv": custom menus break that. Count from the
        // front while slots still point at the CONTAINER, stop at the first player-inventory slot.
        int containerSlots = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container == mc.player.getInventory()) break;
            containerSlots++;
        }
        if (containerSlots <= 0) return;
        if (requireChest.get() && !hasStorageRows(containerSlots)) return;

        Set<Item> filter = cachedFilter();

        for (int i = 0; i < containerSlots; i++) {
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            if (!matches(stack, filter)) continue;
            // Inventory-full failsafe: QUICK_MOVE silently no-ops with a full inventory, which
            // would loop clicking the same slot forever (a packet-spam flag).
            if (!hasRoomFor(mc, stack)) {
                com.autism.seedcracker.compat.ClientNotify.warning("[Chest Stealer] Inventory full.");
                if (closeWhenDone.get()) mc.player.closeContainer();
                cooldown = com.autism.seedcracker.util.Humanizer.delay(delayTicks.get() * 4);
                return;
            }

            if (!com.autism.seedcracker.util.ActionPacer.tryAction()) { cooldown = 2; return; } // global budget
            ContainerMutex.notifyContainerAction();
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            cooldown = com.autism.seedcracker.util.Humanizer.delay(delayTicks.get());
            return; // one click per cycle
        }

        // Nothing lootable left (empty or filtered out): optionally close the screen.
        if (closeWhenDone.get()) {
            mc.player.closeContainer();
            cooldown = com.autism.seedcracker.util.Humanizer.delay(delayTicks.get());
        }
    }

    /** True if the player inventory can accept at least part of this stack. */
    private static boolean hasRoomFor(Minecraft mc, ItemStack stack) {
        var inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack cur = inv.getItem(i);
            if (cur.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) return true;
        }
        return false;
    }

    /** True when the menu has generic storage rows (chest/barrel/shulker/hopper GUIs). */
    private boolean hasStorageRows(int containerSlots) {
        return containerSlots % 9 == 0 || containerSlots == 5;
    }

    private boolean matches(ItemStack stack, Set<Item> filter) {
        return switch (mode.get()) {
            case ALL -> true;
            case WHITELIST -> filter.contains(stack.getItem());
            case BLACKLIST -> !filter.contains(stack.getItem());
        };
    }

    private Set<Item> cachedFilter() {
        if (--filterReparseTicks > 0) return cachedFilterSet;
        filterReparseTicks = 20;
        Set<Item> out = new HashSet<>();
        java.util.List<String> raw = filterItems.get();
        if (raw != null) {
            for (String id : raw) {
                // tryParse+getOptional: parse() THROWS on bad input and getValue() returns AIR
                // (never null), so a typo used to whitelist air.
                Identifier ident = Identifier.tryParse(id.trim());
                if (ident != null) BuiltInRegistries.ITEM.getOptional(ident).ifPresent(out::add);
            }
        }
        cachedFilterSet = out;
        return out;
    }

    private Set<Item> cachedFilterSet = new HashSet<>();
    private int filterReparseTicks = 0;

    @Override
    public String info() {
        return mode.get().name().toLowerCase(java.util.Locale.ROOT);
    }
}
