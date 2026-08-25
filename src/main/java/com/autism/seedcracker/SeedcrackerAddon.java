package com.autism.seedcracker;

import com.autism.seedcracker.commands.BedrockFinderCommand;
import com.autism.seedcracker.hud.SeedHud;
import com.autism.seedcracker.modules.BedrockFinderModule;
import com.autism.seedcracker.modules.SeedcrackerModule;

import autismclient.api.ApiVersion;
import autismclient.api.AutismAddon;
import autismclient.api.AutismAddons;

/**
 * AUTISM Client addon entrypoint (the "autism" entrypoint in fabric.mod.json).
 *
 * Registers the SeedCracker module so it shows up in the AUTISM module menu and can be
 * toggled on/off. The SeedCrackerX engine itself is started by {@link SeedcrackerInit}.
 */
public final class SeedcrackerAddon extends AutismAddon {
    public static final String ID = "autism-seedcracker";

    @Override
    public int apiVersion() {
        return ApiVersion.CURRENT;
    }

    @Override
    public void onInitialize() {
        this.name = "SeedCracker";
        this.authors = "KaptainWutax, 19MisterX98";
        this.color = 0xFF50C878;

        AutismAddons.modules().register(new SeedcrackerModule());
        AutismAddons.modules().register(new BedrockFinderModule());
        AutismAddons.commands().register(new BedrockFinderCommand());
        AutismAddons.hud().register(new SeedHud());
    }

    @Override
    public String getPackage() {
        return "com.autism.seedcracker";
    }
}
