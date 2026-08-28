package com.autism.seedcracker;

import com.autism.seedcracker.commands.BedrockFinderCommand;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.hud.SeedHud;
import com.autism.seedcracker.hud.StashWarningHud;
import com.autism.seedcracker.modules.ActivityFinderModule;
import com.autism.seedcracker.modules.BedrockFinderModule;
import com.autism.seedcracker.modules.ChunkFinderModule;
import com.autism.seedcracker.modules.GrowthFinderModule;
import com.autism.seedcracker.modules.SeedcrackerModule;
import com.autism.seedcracker.modules.SpawnerFinderModule;
import com.autism.seedcracker.modules.StashFinderModule;
import com.autism.seedcracker.modules.SusChunkFinderModule;
import com.autism.seedcracker.rtp.DonutRTPStashFinderModule;
import com.autism.seedcracker.rtp.RelogLoaderModule;

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
        this.name = "Seed Based Tools";
        this.authors = "KaptainWutax, 19MisterX98";
        this.color = 0xFF50C878;

        AutismAddons.modules().register(new SeedcrackerModule());
        AutismAddons.modules().register(new BedrockFinderModule());
        AutismAddons.modules().register(new DonutRTPStashFinderModule());
        AutismAddons.modules().register(new RelogLoaderModule());

        // Zelith chunk-scanner finder modules (ported). The shared renderer self-registers a
        // LevelRenderEvents collector that all six feed their flagged chunks into.
        ChunkFlagRenderer.init();
        AutismAddons.modules().register(new StashFinderModule());
        AutismAddons.modules().register(new ChunkFinderModule());
        AutismAddons.modules().register(new SpawnerFinderModule());
        AutismAddons.modules().register(new SusChunkFinderModule());
        AutismAddons.modules().register(new ActivityFinderModule());
        AutismAddons.modules().register(new GrowthFinderModule());

        AutismAddons.commands().register(new BedrockFinderCommand());
        AutismAddons.hud().register(new SeedHud());
        AutismAddons.hud().register(new StashWarningHud());
    }

    @Override
    public String getPackage() {
        return "com.autism.seedcracker";
    }
}
