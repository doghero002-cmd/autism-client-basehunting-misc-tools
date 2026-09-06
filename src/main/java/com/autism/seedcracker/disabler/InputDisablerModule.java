package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.world.entity.player.Input;

/**
 * Input Disabler.
 *
 * Sends a forged input packet every tick claiming full-forward movement + jump + sneak, which
 * scrambles the input-based movement checks of some anti-cheats.
 *
 * Port of the Rise "Input" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2). The 1.8.9
 * C0CPacketInput maps to ServerboundPlayerInputPacket carrying an Input record.
 */
public final class InputDisablerModule extends Module {

    public InputDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":input-disabler", "Input Disabler [Patched]", category,
            "Sends forged input packets every tick. WARNING: detectable.");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        // forward, backward, left, right, jump, shift, sprint
        Input input = new Input(true, false, false, false, true, true, false);
        mc.getConnection().send(new ServerboundPlayerInputPacket(input));
    }
}
