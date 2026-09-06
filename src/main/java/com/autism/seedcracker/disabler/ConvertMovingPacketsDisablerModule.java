package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * Convert Moving Packets Disabler.
 *
 * Intercepts outgoing position+rotation move packets (PosRot) and re-sends them as plain position
 * packets (Pos), stripping the rotation. Some anti-cheats lose their rotation reference when the
 * client never reports its look direction with movement.
 *
 * Port of the Rise "ConvertMovingPackets" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2).
 * The 1.8.9 C06->C04 conversion maps to PosRot -> Pos. The AUTISM send hook can only cancel (not
 * replace), so this cancels the original and immediately sends the converted packet.
 */
public final class ConvertMovingPacketsDisablerModule extends Module {

    private boolean resending = false;

    public ConvertMovingPacketsDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":convert-moving-packets-disabler", "Convert Move Packets", category,
            "Strips rotation from move packets (PosRot -> Pos). WARNING: detectable / may desync rotations.");
    }

    @Override
    public boolean onPacketSend(Packet<?> packet) {
        if (resending) return false;
        if (!(packet instanceof ServerboundMovePlayerPacket.PosRot posRot)) return false;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return false;

        // Re-send as a plain position packet using the player's current position (rotation stripped).
        resending = true;
        mc.getConnection().send(new ServerboundMovePlayerPacket.Pos(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            posRot.isOnGround(), posRot.horizontalCollision()));
        resending = false;
        return true; // cancel the original PosRot
    }
}
