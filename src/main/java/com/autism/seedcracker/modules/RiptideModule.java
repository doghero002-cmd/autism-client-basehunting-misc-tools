package com.autism.seedcracker.modules;

import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.mechanics.PhysicalUseKey;
import com.autism.seedcracker.spectre.mechanics.RiptideCycle;
import com.autism.seedcracker.util.ActionPacer;
import com.autism.seedcracker.util.ContainerMutex;
import com.autism.seedcracker.util.RelogPersistence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

/**
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Spectre misc/RiptideModule and GrimRiptide at d5a7dd3 (unregistered upstream).
 * Intentional ownership change: vanilla starts use from the user's configured binding; this
 * module only releases a charged riptide while that physical binding is held. It never presses
 * a key, switches a slot, starts a charge, changes aim, or leaves an owned use behind on disable.
 * Water acceptance follows vanilla/the separate TridentUtil hook, not global rain under a roof.
 */
public final class RiptideModule extends Module {
    private final RiptideCycle cycle = new RiptideCycle();
    private final PhysicalUseKey input = new PhysicalUseKey();
    private ClientLevel world;
    private LocalPlayer player;

    public RiptideModule() {
        super(SeedcrackerAddon.ID + ":riptide", "Riptide",
            "Repeatedly releases a charged riptide trident while you physically hold the use binding.");
    }

    @Override public void onEnable() { clearSession(); }
    @Override public void onDisable() { clearSession(); }
    @Override public void onGameJoin() { clearSession(); }
    @Override public void onGameLeft() {
        clearSession();
        if (RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread()) return;
        if (mc.player == null || mc.level == null || mc.gameMode == null) {
            clearSession();
            return;
        }
        if (world != mc.level || player != mc.player) {
            clearSession();
            world = mc.level;
            player = mc.player;
        }
        if (!isEnabled() || !player.isAlive() || player.isSpectator() || player.isPassenger()
            || mc.gui.screen() != null || !mc.isWindowActive() || ContainerMutex.containerBusy(mc)
            || AirStuckModule.freezes(player) || !player.isUsingItem()) return;
        boolean manualHeld = input.held(mc);
        boolean riptide = isUsableRiptide(player.getUseItem());
        boolean waterAccepted = player.isInWaterOrRain() || TridentUtilModule.allowOutOfWater(player);
        int minimum = TridentUtilModule.minChargeTicks(player, 10);
        if (!cycle.canRelease(player.tickCount, player.getTicksUsingItem(), minimum,
            manualHeld, riptide, waterAccepted) || !ActionPacer.tryAction()) return;
        mc.gameMode.releaseUsingItem(player);
        cycle.released(player.tickCount);
    }

    private static boolean isUsableRiptide(ItemStack stack) {
        if (!stack.is(Items.TRIDENT) || stack.nextDamageWillBreak()) return false;
        for (var entry : stack.getEnchantments().entrySet()) {
            if (entry.getKey().is(Enchantments.RIPTIDE) && entry.getIntValue() > 0) return true;
        }
        return false;
    }

    private void clearSession() {
        cycle.reset();
        input.reset();
        world = null;
        player = null;
    }
}
