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
        if (mc.gui.screen() == null) return; // menu desync guard: only act while the screen is up

        // Count container slots by slot-container IDENTITY (same fix ChestStealer has) - custom
        // menus break the "last 36 = player inv" assumption.
        int containerSlots = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (menu.slots.get(i).container == mc.player.getInventory()) break;
            containerSlots++;
        }
        if (containerSlots <= 0) return;
        if (requireChest.get() && !hasStorageRows(menu, containerSlots)) return;

        Set<Item> filter = cachedFilter();

        // Player inventory slots inside this menu: [containerSlots, containerSlots+27) = main inv,
        // [containerSlots+27, containerSlots+36) = hotbar.
        int mainStart = containerSlots;
        int hotbarStart = containerSlots + 27;
        int end = keepHotbar.get() ? hotbarStart : containerSlots + 36;

        for (int i = mainStart; i < end; i++) {
            if (i >= menu.slots.size()) break;
            Slot slot = menu.slots.get(i);
            if (!slot.hasItem()) continue;
            ItemStack stack = slot.getItem();
            if (!matches(stack, filter)) continue;
            // Container-full failsafe: QUICK_MOVE no-ops into a full container -> infinite clicks.
            if (!containerHasRoomFor(menu, containerSlots, stack)) {
                com.autism.seedcracker.compat.ClientNotify.warning("[Auto Store] Container full.");
                cooldown = com.autism.seedcracker.util.Humanizer.delay(delayTicks.get() * 8);
                return;
            }

            if (!com.autism.seedcracker.util.ActionPacer.tryAction()) { cooldown = 2; return; } // global budget
            ContainerMutex.notifyContainerAction();
            mc.gameMode.handleContainerInput(menu.containerId, i, 0, ContainerInput.QUICK_MOVE, mc.player);
            cooldown = com.autism.seedcracker.util.Humanizer.delay(delayTicks.get());
            return; // one click per cycle
        }
    }

    /** True if the open container can accept at least part of this stack. */
    private static boolean containerHasRoomFor(AbstractContainerMenu menu, int containerSlots, ItemStack stack) {
        for (int i = 0; i < containerSlots; i++) {
            ItemStack cur = menu.slots.get(i).getItem();
            if (cur.isEmpty()) return true;
            if (ItemStack.isSameItemSameComponents(cur, stack) && cur.getCount() < cur.getMaxStackSize()) return true;
        }
        return false;
    }

    /** True when the menu has generic storage rows (chest/barrel/shulker/hopper GUIs). */
    private boolean hasStorageRows(AbstractContainerMenu menu, int containerSlots) {
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
