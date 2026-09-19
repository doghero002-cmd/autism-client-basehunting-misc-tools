package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Elytra Durability Warner.
 *
 * Watches your equipped elytra and warns you (toast + chat + sound) when its durability drops
 * below a threshold, so you don't get caught mid-flight with a breaking elytra. Optionally swaps
 * to a fresh elytra from your inventory automatically.
 */
public final class ElytraWarnerModule extends Module {

    private final IntSetting warnPercent = add(new IntSetting("warn-percent", "Warn at %", 15, 1, 90, 1)
        .description("Warn when elytra durability falls below this percent.").group("General"));
    private final BoolSetting autoSwap = add(new BoolSetting("auto-swap", "Auto-swap to spare", false)
        .description("Automatically swap to a higher-durability elytra from your inventory when worn one is low.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting("notify", "Notifications", true)
        .description("Toast + chat + sound warnings.").group("General"));

    private boolean warned = false;

    public ElytraWarnerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":elytra-warner", "Elytra Warner", category,
            "Warns when your elytra is about to break (and can auto-swap to a spare).");
    }

    @Override
    public void onEnable() {
        warned = false;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        ItemStack chest = mc.player.getInventory().getItem(38); // chestplate slot
        if (!chest.is(Items.ELYTRA)) { warned = false; return; }

        int max = chest.getMaxDamage();
        if (max <= 0) return;
        int remaining = max - chest.getDamageValue();
        int pct = Math.round((remaining / (float) max) * 100f);

        if (pct <= warnPercent.get()) {
            if (autoSwap.get() && trySwapToSpare(mc, chest)) {
                warned = false;
                if (notify.get()) {
                    AutismClientMessaging.sendPrefixed("§a[Elytra Warner] Swapped to a fresh elytra.");
                }
                return;
            }
            if (!warned) {
                warned = true;
                if (notify.get()) {
                    String msg = "Elytra low: " + pct + "% (" + remaining + "/" + max + ")";
                    AutismNotifications.warning(msg);
                    AutismClientMessaging.sendPrefixed("§c[Elytra Warner] §f" + msg);
                    mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.6f);
                }
            }
        } else {
            warned = false;
        }
    }

    /** Swap to the highest-durability elytra in the inventory if it's better than the worn one. */
    private boolean trySwapToSpare(Minecraft mc, ItemStack worn) {
        int wornRemaining = worn.getMaxDamage() - worn.getDamageValue();
        int bestSlot = -1;
        int bestRemaining = wornRemaining;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getItem(i);
            if (s.is(Items.ELYTRA)) {
                int r = s.getMaxDamage() - s.getDamageValue();
                if (r > bestRemaining) {
                    bestRemaining = r;
                    bestSlot = i;
                }
            }
        }
        if (bestSlot == -1) return false;
        // Swap worn chestplate with the spare via real inventory clicks (pickup chestplate,
        // pickup spare, place) so the server sees the swap (client-only setItem would desync).
        if (mc.gameMode == null) return false;
        // Inventory container slots: armor chestplate is slot 6 in the player inventory menu;
        // hotbar slots 0-8 map to menu slots 36-44, main inventory 9-35 map to 9-35.
        int menuChest = 6;
        int menuSpare = bestSlot < 9 ? 36 + bestSlot : bestSlot;
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, menuChest, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player); // hold worn elytra
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, menuSpare, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player); // swap into spare slot
        com.autism.seedcracker.util.ContainerMutex.notifyContainerAction(); mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId, menuChest, 0,
            net.minecraft.world.inventory.ContainerInput.PICKUP, mc.player); // place spare on chest
        return true;
    }
}
