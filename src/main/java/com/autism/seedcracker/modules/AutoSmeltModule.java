package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.util.ContainerMutex;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Auto Smelt (nyx AutoSmeltModule port).
 *
 * While a furnace / blast furnace / smoker screen is open: pulls finished output, refuels from
 * your inventory (coal > charcoal > coal block priority), and feeds smeltable input - one click
 * per delay tick. Slot layout for every furnace menu: 0 = input, 1 = fuel, 2 = output,
 * 3..30 = player main inv, 30..39 = hotbar.
 */
public final class AutoSmeltModule extends Module {

    private final IntSetting delayTicks = add(new IntSetting("delay", "Click delay (ticks)", 3, 1, 20, 1)
        .description("Ticks between clicks.").group("General"));
    private final BoolSetting refuel = add(new BoolSetting("refuel", "Auto refuel", true)
        .description("Move coal/charcoal into the fuel slot when it runs low.").group("General"));
    private final BoolSetting pullOutput = add(new BoolSetting("pull-output", "Pull output", true)
        .description("Shift-click finished items into your inventory.").group("General"));
    private final BoolSetting feedInput = add(new BoolSetting("feed-input", "Feed input", true)
        .description("Move smeltable items (raw ores/food) into the input slot.").group("General"));

    private int cooldown = 0;

    public AutoSmeltModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":auto-smelt", "Auto Smelt", category,
            "Runs open furnaces for you: pull output, refuel, feed input.");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.gameMode == null) return;
        if (cooldown > 0) { cooldown--; return; }

        AbstractContainerMenu raw = mc.player.containerMenu;
        if (!(raw instanceof AbstractFurnaceMenu menu) || mc.gui.screen() == null) return;

        // 1. Output ready -> pull it.
        if (pullOutput.get() && menu.slots.get(2).hasItem()) {
            click(mc, menu, 2, ContainerInput.QUICK_MOVE);
            return;
        }

        // 2. Fuel empty -> refuel from inventory.
        if (refuel.get() && !menu.slots.get(1).hasItem()) {
            int fuelSlot = findPlayerSlot(menu, stack -> stack.is(Items.COAL)
                || stack.is(Items.CHARCOAL) || stack.is(Items.COAL_BLOCK));
            if (fuelSlot >= 0) {
                moveTo(mc, menu, fuelSlot, 1);
                return;
            }
        }

        // 3. Input empty -> feed anything smeltable (QUICK_MOVE routes it to input if valid).
        if (feedInput.get() && !menu.slots.get(0).hasItem()) {
            int inSlot = findPlayerSlot(menu, this::isSmeltable);
            if (inSlot >= 0) {
                moveTo(mc, menu, inSlot, 0);
            }
        }
    }

    /** Common smeltables; the furnace itself rejects anything invalid (pickup returns to cursor). */
    private boolean isSmeltable(ItemStack stack) {
        String id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return id.startsWith("raw_") || id.endsWith("_ore") || id.equals("cobblestone")
            || id.equals("sand") || id.equals("clay_ball") || id.equals("netherrack")
            || id.startsWith("beef") || id.equals("porkchop") || id.equals("chicken")
            || id.equals("mutton") || id.equals("rabbit") || id.equals("cod") || id.equals("salmon")
            || id.equals("potato") || id.equals("kelp") || id.equals("cactus");
    }

    /** First matching player-inventory slot index within the furnace menu (3..39). */
    private int findPlayerSlot(AbstractFurnaceMenu menu, java.util.function.Predicate<ItemStack> match) {
        for (int i = 3; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.hasItem() && match.test(slot.getItem())) return i;
        }
        return -1;
    }

    /** Pickup src -> drop into dst -> return leftovers (3 clicks max, one per tick chain). */
    private void moveTo(Minecraft mc, AbstractContainerMenu menu, int src, int dst) {
        click(mc, menu, src, ContainerInput.PICKUP);
        click(mc, menu, dst, ContainerInput.PICKUP);
        // Any remainder goes back where it came from.
        if (!mc.player.containerMenu.getCarried().isEmpty()) {
            click(mc, menu, src, ContainerInput.PICKUP);
        }
    }

    private void click(Minecraft mc, AbstractContainerMenu menu, int slot, ContainerInput type) {
        ContainerMutex.notifyContainerAction();
        mc.gameMode.handleContainerInput(menu.containerId, slot, 0, type, mc.player);
        cooldown = delayTicks.get();
    }
}
