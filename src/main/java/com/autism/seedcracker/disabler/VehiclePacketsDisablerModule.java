package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * Vehicle Packets Disabler.
 *
 * Spams an empty input packet every tick, flooding the vehicle/steering check of some anti-cheats
 * so ridden-entity movement is never validated against real input.
 *
 * Port of the Rise "VehiclePackets" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2). The
 * 1.8.9 C0CPacketInput() maps to ServerboundPlayerInputPacket(Input.EMPTY).
 */
public final class VehiclePacketsDisablerModule extends Module {

    public VehiclePacketsDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":vehicle-packets-disabler", "Vehicle Packets [Patched]", category,
            "Spams empty input packets every tick. WARNING: detectable.");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        mc.getConnection().send(new ServerboundPlayerInputPacket(Input.EMPTY));
    }
}
