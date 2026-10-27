package com.autism.seedcracker.modules;

import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.mechanics.DonutFallPackets;
import com.autism.seedcracker.spectre.mechanics.DonutFallState;
import com.autism.seedcracker.util.RelogPersistence;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Spectre misc/DonutNoFallModule at d5a7dd3.
 * Deliberate safety differences: no local setOnGround/setPosRaw mutation; idle packets pass
 * unchanged; original packet coordinates, rotations and collision flags are preserved.
 * The server remains authoritative. This packet technique does not guarantee avoiding damage.
 */
public final class DonutNoFallModule extends Module {
    private final DonutFallState fall = new DonutFallState();
    private ClientLevel world;
    private LocalPlayer player;
    private volatile boolean correctionPending;
    private boolean sendingReplacement;

    public DonutNoFallModule() {
        super(SeedcrackerAddon.ID + ":donut-nofall", "Donut NoFall",
            "Alters movement packets during falls. Server-dependent; fall damage may still occur.");
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
        if (!eligible(mc)) {
            clearSession();
            return;
        }
        if (world != mc.level || player != mc.player) {
            clearSession();
            world = mc.level;
            player = mc.player;
        }
        if (correctionPending || AirStuckModule.freezes(player)) {
            fall.reset();
            correctionPending = false;
            return;
        }
        fall.observeFall(player.fallDistance);
    }

    @Override public boolean onPacketSend(Packet<?> packet) {
        if (!isEnabled() || sendingReplacement || !(packet instanceof ServerboundMovePlayerPacket move)) return false;
        Minecraft mc = Minecraft.getInstance();
        // Native packet listeners can run off-thread. Never inspect/mutate live world state there.
        if (!mc.isSameThread()) return false;
        if (!eligible(mc) || world != mc.level || player != mc.player || correctionPending
            || AirStuckModule.freezes(mc.player)) {
            fall.reset();
            return false;
        }
        var adjustment = fall.prepare(move.isOnGround());
        if (adjustment == null) return false;
        var replacement = DonutFallPackets.replacement(move, player.getX(), player.getY(), player.getZ(),
            player.getYRot(), player.getXRot(), adjustment);
        if (replacement == null) {
            fall.reset();
            return false;
        }
        sendingReplacement = true;
        try {
            mc.getConnection().send(replacement);
            fall.sent(adjustment);
            return true; // Only cancel the original after submitting its replacement.
        } finally {
            sendingReplacement = false;
        }
    }

    @Override public boolean onPacketReceive(Packet<?> packet) {
        if (packet instanceof ClientboundPlayerPositionPacket) correctionPending = true;
        return false;
    }

    private static boolean eligible(Minecraft mc) {
        return mc.isSameThread() && mc.level != null && mc.player != null && mc.getConnection() != null
            && mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.getAbilities().instabuild
            && !mc.player.isPassenger() && !mc.player.isFallFlying() && !mc.player.isInWater();
    }

    private void clearSession() {
        fall.reset();
        world = null;
        player = null;
        correctionPending = false;
        sendingReplacement = false;
    }

    @Override public String info() { return fall.active() ? "fall" : "idle"; }
}
