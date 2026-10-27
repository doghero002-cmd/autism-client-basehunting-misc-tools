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
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.Container;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Auto Store (nyx AutoStoreModule port).
 *
 * While any container screen is open, shift-clicks (QUICK_MOVE) matching items from your
 * inventory into the container - one click per delay tick so it looks like fast-but-human
 * deposit spam rather than a 0-tick dump. Filter modes:
 *  - ALL: everything (except kept rows below)
 *  - WHITELIST: only listed items
 *  - BLACKLIST: everything except listed items
 * Keep-hotbar / keep-armor guards stop it stripping your gear.
 */
public final class AutoStoreModule extends Module {

    public enum FilterMode { ALL, WHITELIST, BLACKLIST }

    private final EnumSetting<FilterMode> mode = add(new EnumSetting<>("mode", "Filter mode",
            FilterMode.ALL, FilterMode.values())
        .description("ALL = deposit everything, WHITELIST = only listed, BLACKLIST = all except listed.")
        .group("Filter"));
    private final StringListSetting filterItems = add(new StringListSetting("items", "Filter items",
            "minecraft:cobblestone|minecraft:dirt|minecraft:netherrack")
        .description("Item ids (| separated) used by WHITELIST/BLACKLIST modes.").group("Filter"));
    private final BoolSetting keepHotbar = add(new BoolSetting("keep-hotbar", "Keep hotbar", true)
        .description("Never deposit hotbar slots.").group("Keep"));
    private final IntSetting delayTicks = add(new IntSetting("delay", "Click delay (ticks)", 2, 1, 20, 1)
        .description("Ticks between deposit clicks (2 = 10 items/s, human-fast).").group("General"));
    private final BoolSetting requireChest = add(new BoolSetting("require-container", "Only real containers", true)
        .description("Only deposit into container menus (not crafting/furnace UIs).").group("General"));

    private int cooldown = 0;

    public AutoStoreModule() {
        super(SeedcrackerAddon.ID + ":auto-store", "Auto Store",
            "Shift-deposits filtered inventory items into any open container.");
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
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen) || screen.getMenu() != menu) return;

        Container inventory = mc.player.getInventory();
        int containerSlots = 0;
        for (Slot slot : menu.slots) if (slot.container != inventory) containerSlots++;
        if (containerSlots <= 0) return;
        if (requireChest.get() && !hasStorageRows(containerSlots)) return;

        Set<Item> filter = cachedFilter();
        boolean blocked = false;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (!isDepositSource(slot, inventory, keepHotbar.get()) || !slot.hasItem() || !slot.mayPickup(mc.player)) continue;
            ItemStack stack = slot.getItem();
            if (!matches(stack, filter)) continue;
            if (!containerHasRoomFor(menu, inventory, stack)) {
                blocked = true;
                continue; // Another matching stack may fit even when this one does not.
            }

            if (!com.autism.seedcracker.util.ActionPacer.tryAction()) { cooldown = 2; return; }
            ContainerMutex.notifyContainerAction();
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            cooldown = com.autism.seedcracker.util.Humanizer.delay(delayTicks.get());
            return;
        }
        if (blocked) {
            com.autism.seedcracker.compat.ClientNotify.warning("[Auto Store] No room for matching items.");
            cooldown = com.autism.seedcracker.util.Humanizer.delay(delayTicks.get() * 8);
        }
    }

    static boolean isDepositSource(Slot slot, Container inventory, boolean keepHotbar) {
        int index = slot.getContainerSlot();
        return slot.container == inventory && index >= (keepHotbar ? 9 : 0) && index < 36;
    }

    /** Custom menus may interleave player and storage slots, restrict inputs or lower stack limits. */
    static boolean containerHasRoomFor(AbstractContainerMenu menu, Container inventory, ItemStack stack) {
        for (Slot slot : menu.slots) {
            if (slot.container == inventory || !slot.mayPlace(stack)) continue;
            int limit = Math.min(slot.getMaxStackSize(stack), stack.getMaxStackSize());
            ItemStack current = slot.getItem();
            if (limit > 0 && (current.isEmpty()
                || ItemStack.isSameItemSameComponents(current, stack) && current.getCount() < limit)) return true;
        }
        return false;
    }

    /** True when the menu has generic storage rows (chest/barrel/shulker/hopper GUIs). */
    private static boolean hasStorageRows(int containerSlots) {
        // Generic containers come in multiples of 9 (9/18/27/54) or 5 (hopper). Furnaces (3),
        // enchanting (2) etc. don't.
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
                Identifier ident = Identifier.tryParse(id.trim());
                if (ident != null) BuiltInRegistries.ITEM.getOptional(ident).ifPresent(out::add);
            }
        }
        cachedFilterSet = out;
        return out;
    }

    private Set<Item> cachedFilterSet = new HashSet<>();
    private int filterReparseTicks = 0;
}
