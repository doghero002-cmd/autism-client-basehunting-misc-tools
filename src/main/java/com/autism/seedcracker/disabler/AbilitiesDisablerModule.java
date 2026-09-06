package com.autism.seedcracker.disabler;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundPlayerAbilitiesPacket;
import net.minecraft.world.entity.player.Abilities;

/**
 * Abilities Disabler.
 *
 * Every few ticks sends a player-abilities packet claiming "flying = true", which confuses
 * anti-cheats that key their fly check off the abilities state.
 *
 * Port of the Rise "Abilities" disabler mode (1.8.9) to the AUTISM API (Mojang 26.2). The 1.8.9
 * C13PacketPlayerAbilities maps to ServerboundPlayerAbilitiesPacket.
 */
public final class AbilitiesDisablerModule extends Module {

    private final IntSetting interval = add(new IntSetting(
            "interval", "Interval (ticks)", 5, 1, 40, 1)
        .description("Ticks between ability packets.")
        .group("General"));

    public AbilitiesDisablerModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":abilities-disabler", "Abilities Disabler [Patched]", category,
            "Sends flying=true ability packets on an interval to confuse fly checks. WARNING: detectable.");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (mc.player.tickCount % Math.max(1, interval.get()) != 0) return;

        Abilities abilities = new Abilities();
        abilities.flying = true;
        mc.getConnection().send(new ServerboundPlayerAbilitiesPacket(abilities));
    }
}
