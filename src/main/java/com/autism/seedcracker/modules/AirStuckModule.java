package com.autism.seedcracker.modules;

import autismclient.modules.Module;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.spectre.mechanics.AirStuckState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;

/**
 * SPDX-License-Identifier: GPL-3.0-only
 * Adapted from Spectre AirStuckModule / MixinAirStuckLocalPlayer, d5a7dd3.
 * The aiStep hook freezes physics, not key bindings. No velocity, key, slot or rotation is owned.
 * Unlike persistent background utilities this manual latch never survives a join or respawn
 * (Spectre also opts out of enabled-state persistence). Server corrections always win.
 */
public final class AirStuckModule extends Module {
    private static volatile AirStuckModule active;
    private final AirStuckState state = new AirStuckState();
    private volatile boolean corrected;

    public AirStuckModule() {
        super(SeedcrackerAddon.ID + ":air-stuck", "Air Stuck",
            "Freezes local movement until disabled. Server corrections end the freeze.");
    }

    @Override public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        corrected = false;
        if (!eligible(mc) || !state.arm(mc.level, mc.player)) {
            disableWithToggleMessage("Join a world before enabling Air Stuck.");
            return;
        }
        active = this;
    }

    @Override public void onDisable() {
        if (active == this) active = null;
        state.clear();
        corrected = false;
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (!eligible(mc) || corrected || !state.activeFor(mc.level, mc.player)) {
            setEnabledSilently(false);
        }
    }

    @Override public void onGameLeft() { endSession(); }
    @Override public void onGameJoin() { endSession(); }

    private void endSession() {
        onDisable();
        if (isEnabled()) setEnabledSilently(false);
    }

    @Override public boolean onPacketSend(Packet<?> packet) {
        Minecraft mc = Minecraft.getInstance();
        return isEnabled() && !corrected && eligible(mc)
            && state.cancels(mc.level, mc.player,
                packet instanceof ServerboundMovePlayerPacket,
                packet instanceof ServerboundClientTickEndPacket);
    }

    @Override public boolean onPacketReceive(Packet<?> packet) {
        // Receive callbacks may be on Netty. Only publish a flag; never touch the live player here.
        if (packet instanceof ClientboundPlayerPositionPacket) corrected = true;
        return false;
    }

    public static boolean freezes(LocalPlayer player) {
        AirStuckModule module = active;
        Minecraft mc = Minecraft.getInstance();
        return module != null && module.isEnabled() && !module.corrected && eligible(mc)
            && player == mc.player && module.state.activeFor(mc.level, player);
    }

    private static boolean eligible(Minecraft mc) {
        return mc.isSameThread() && mc.level != null && mc.player != null
            && mc.getConnection() != null && mc.player.isAlive() && !mc.player.isPassenger()
            && !mc.player.isSpectator();
    }
}
