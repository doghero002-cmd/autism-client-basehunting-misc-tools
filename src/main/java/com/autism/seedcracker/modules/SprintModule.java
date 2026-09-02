package com.autism.seedcracker.modules;

import com.autism.seedcracker.SeedcrackerAddon;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;

/**
 * Sprint.
 *
 * Forces sprinting to stay on while enabled. When the module is turned on it remembers whether
 * you were already sprinting and restores that state when turned off. Optionally also holds the
 * vanilla sprint key down so the FOV / sprint particles kick in.
 *
 * Clean-room port of the obfuscated Zelith "Sprint" module.
 */
public final class SprintModule extends Module {

    private final BoolSetting holdKey = add(new BoolSetting("hold-key", "Hold sprint key", false)
        .description("Also hold the vanilla sprint key down (shows sprint FOV/particles).")
        .group("General"));

    private boolean wasSprinting;

    public SprintModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-sprint", "Sprint", category,
            "Keeps you sprinting while enabled, restoring your previous sprint state on disable.");
    }

    @Override
    public void onEnable() {
        Minecraft mc = Minecraft.getInstance();
        wasSprinting = mc.player != null && mc.player.isSprinting();
    }

    @Override
    public void onDisable() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.setSprinting(wasSprinting);
        if (holdKey.get()) {
            mc.options.keySprint.setDown(false);
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        mc.player.setSprinting(true);
        if (holdKey.get()) {
            mc.options.keySprint.setDown(true);
        }
    }
}
