package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket;

/**
 * Sprint Disabler.
 *
 * Forces the client to never report sprinting: cancels START_SPRINTING entity-action packets and
 * clears the local sprint state, so the server never sees you sprint (breaks sprint-dependent
 * speed checks, at the cost of slower movement).
 *
 * Port of the Rise "Sprint" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2). The 1.8.9
 * PreMotionEvent#setSprinting maps to clearing the sprint flag + cancelling the command packet.
 */
public final class SprintDisablerModule extends Module {

    public SprintDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":sprint-disabler", "Sprint Disabler [Patched]", category,
            "Never report sprinting to the server (cancels sprint packets + clears sprint).");
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.setSprinting(false);
    }

    @Override
    public boolean onPacketSend(Packet<?> packet) {
        if (packet instanceof ServerboundPlayerCommandPacket cmd) {
            return cmd.getAction() == ServerboundPlayerCommandPacket.Action.START_SPRINTING;
        }
        return false;
    }
}
