package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Grim Trident Disabler.
 *
 * Spam-uses a Riptide trident off-thread of the normal use animation by sending the raw
 * select-slot / use-item / release-use / reselect packets on a fixed tick delay. This floods
 * GrimAC's movement check with riptide state changes so the move check never settles. Needs a
 * trident (ideally Riptide III) in the hotbar.
 *
 * Port of the Selena/Acid "GrimDisabler" module (1.21.1, Yarn) to the AUTISM API (Mojang
 * mappings). The original is patched on modern Grim - use at your own risk.
 */
public final class GrimTridentDisablerModule extends Module {

    private final IntSetting tridentDelay = add(new IntSetting(
            "trident-delay", "Trident delay (ticks)", 0, 0, 20, 1)
        .description("Delay (in ticks) between trident uses.")
        .group("General"));
    private final BoolSetting pauseOnEat = add(new BoolSetting(
            "pause-on-eat", "Pause on eat", false)
        .description("Pause while eating/using an item.")
        .group("General"));

    private int currentTick = 0;

    public GrimTridentDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":grim-trident-disabler", "Grim Trident Disabler", category,
            "Disables GrimAC move checks via Riptide trident packet spam (needs a trident). WARNING: likely patched / detectable.");
    }

    @Override
    public void onEnable() {
        currentTick = tridentDelay.get();
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;

        if (currentTick < tridentDelay.get()) {
            currentTick++;
            return;
        }
        currentTick = 0;

        int tridentSlot = findTrident(mc);
        int oldSlot = mc.player.getInventory().getSelectedSlot();
        if (tridentSlot == -1) return;
        if (pauseOnEat.get() && mc.player.isUsingItem()) return;

        mc.getConnection().send(new ServerboundSetCarriedItemPacket(tridentSlot));
        mc.getConnection().send(new ServerboundUseItemPacket(
            InteractionHand.MAIN_HAND, 0, mc.player.getYRot(), mc.player.getXRot()));
        mc.getConnection().send(new ServerboundPlayerActionPacket(
            ServerboundPlayerActionPacket.Action.RELEASE_USE_ITEM, BlockPos.ZERO, Direction.DOWN));
        mc.getConnection().send(new ServerboundSetCarriedItemPacket(oldSlot));
    }

    /** First hotbar slot (0-8) holding a trident, or -1. */
    private static int findTrident(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getItem(i);
            if (stack != null && stack.is(Items.TRIDENT)) return i;
        }
        return -1;
    }
}
