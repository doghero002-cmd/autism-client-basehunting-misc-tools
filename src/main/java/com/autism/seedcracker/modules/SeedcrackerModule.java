package com.autism.seedcracker.modules;

import autismclient.api.module.BoolSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import com.autism.seedcracker.SeedcrackerAddon;
import kaptainwutax.seedcrackerX.SeedCracker;
import kaptainwutax.seedcrackerX.config.Config;

/**
 * Toggleable AUTISM module wrapping the SeedCrackerX cracker.
 *
 * Enabling/disabling the module flips SeedCrackerX's own {@code active} config flag, so the
 * finder/cracker pipeline only runs while the module is on. A "Reset data" option clears all
 * collected structure/biome data and finders. Use /seedcracker for the full command tree and GUI.
 */
public final class SeedcrackerModule extends Module {

    private final BoolSetting resetOnDisable = add(new BoolSetting(
            "resetOnDisable", "Reset data on disable", false)
        .description("Clear all collected seed data and finders when the module is turned off.")
        .group("General"));

    public SeedcrackerModule() {
        super(SeedcrackerAddon.ID + ":seedcracker", "SeedCracker",
            "Finds the world seed from structures, biomes and decorators. Use /seedcracker for settings and the GUI.");
    }

    @Override
    public void onEnable() {
        Config.get().active = true;
        Config.save();
        AutismClientMessaging.sendPrefixed("§aSeedCracker active. Use /seedcracker gui for the config screen.");
    }

    @Override
    public void onDisable() {
        Config.get().active = false;
        Config.save();
        if (resetOnDisable.get() && SeedCracker.get() != null) {
            SeedCracker.get().reset();
        }
    }
}
