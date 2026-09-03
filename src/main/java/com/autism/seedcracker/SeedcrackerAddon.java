package com.autism.seedcracker;

import com.autism.seedcracker.commands.BaseLogCommand;
import com.autism.seedcracker.commands.BedrockFinderCommand;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.hud.FakeScoreboardHud;
import com.autism.seedcracker.hud.RegionMapHud;
import com.autism.seedcracker.hud.SeedHud;
import com.autism.seedcracker.hud.StashWarningHud;
import com.autism.seedcracker.modules.ActivityFinderModule;
import com.autism.seedcracker.modules.AHFlipperModule;
import com.autism.seedcracker.modules.AntiAFKModule;
import com.autism.seedcracker.modules.AntiTrapModule;
import com.autism.seedcracker.modules.AutoEatModule;
import com.autism.seedcracker.modules.AutoLogModule;
import com.autism.seedcracker.modules.AutoRenderModule;
import com.autism.seedcracker.modules.AutoToolModule;
import com.autism.seedcracker.modules.BaseLogBrowserModule;
import com.autism.seedcracker.modules.BedrockFinderModule;
import com.autism.seedcracker.modules.BoneDropperModule;
import com.autism.seedcracker.modules.ChunkFinderModule;
import com.autism.seedcracker.modules.CoordSnapperModule;
import com.autism.seedcracker.modules.EntityScannerModule;
import com.autism.seedcracker.modules.FakePayModule;
import com.autism.seedcracker.modules.FakePaymentsModule;
import com.autism.seedcracker.modules.FakePlayerModule;
import com.autism.seedcracker.modules.FakeRolesModule;
import com.autism.seedcracker.modules.FastPlaceModule;
import com.autism.seedcracker.modules.FlightPlusModule;
import com.autism.seedcracker.modules.FreeLookModule;
import com.autism.seedcracker.modules.GrowthFinderModule;
import com.autism.seedcracker.modules.HomeSetterModule;
import com.autism.seedcracker.modules.PaperRigModule;
import com.autism.seedcracker.modules.SeedcrackerModule;
import com.autism.seedcracker.modules.SkinChangerModule;
import com.autism.seedcracker.modules.SpawnerFinderModule;
import com.autism.seedcracker.modules.SpawnerProtectModule;
import com.autism.seedcracker.modules.SprintModule;
import com.autism.seedcracker.modules.StashFinderModule;
import com.autism.seedcracker.modules.SusChunkFinderModule;
import com.autism.seedcracker.modules.SwingSpeedModule;
import com.autism.seedcracker.modules.TabDetectorModule;
import com.autism.seedcracker.modules.TPASpammerModule;
import com.autism.seedcracker.modules.WeatherNotifierModule;
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
        this.name = "Dogs BaseHunting/QQL Tools";
        this.authors = "KaptainWutax, 19MisterX98";
        this.color = 0xFF50C878;

        // Category tabs for the Zelith modules (each type gets its own tab in the module menu).
        // NOTE: AutismAddons.modules().registerCategory(label) collapses every call for the same
        // addon into ONE shared addon category (the API keys addon categories by addon id only and
        // ignores the label). To get genuinely separate tabs we register plain (non-addon) categories
        // keyed by their label, the same way the client's builtin modules do.
        autismclient.modules.ModuleCategory catFinders = autismclient.modules.ModuleCategory.register("Finders");
        autismclient.modules.ModuleCategory catEntity = autismclient.modules.ModuleCategory.register("Entity");
        autismclient.modules.ModuleCategory catFake = autismclient.modules.ModuleCategory.register("Fake");
        autismclient.modules.ModuleCategory catRender = autismclient.modules.ModuleCategory.register("Render");
        autismclient.modules.ModuleCategory catMovement = autismclient.modules.ModuleCategory.register("Movement");
        autismclient.modules.ModuleCategory catTrading = autismclient.modules.ModuleCategory.register("Trading");
        autismclient.modules.ModuleCategory catDogsMisc = autismclient.modules.ModuleCategory.register("Dogs Misc Tools");

        AutismAddons.modules().register(new SeedcrackerModule());
        AutismAddons.modules().register(new BedrockFinderModule());
        AutismAddons.modules().register(new DonutRTPStashFinderModule());
        AutismAddons.modules().register(new RelogLoaderModule());

        // Zelith chunk-scanner finder modules (ported). The shared renderer self-registers a
        // LevelRenderEvents collector that all six feed their flagged chunks into.
        ChunkFlagRenderer.init();
        AutismAddons.modules().register(new StashFinderModule(catFinders));
        AutismAddons.modules().register(new ChunkFinderModule(catFinders));
        AutismAddons.modules().register(new SpawnerFinderModule(catFinders));
        AutismAddons.modules().register(new SusChunkFinderModule(catFinders));
        AutismAddons.modules().register(new ActivityFinderModule(catFinders));
        AutismAddons.modules().register(new GrowthFinderModule(catFinders));
        AutismAddons.modules().register(new BaseLogBrowserModule(catFinders));

        // Zelith entity / fake modules (ported), each under its own tab.
        AutismAddons.modules().register(new EntityScannerModule(catEntity));
        AutismAddons.modules().register(new AntiTrapModule(catEntity));
        AutismAddons.modules().register(new BoneDropperModule(catEntity));
        AutismAddons.modules().register(new SpawnerProtectModule(catEntity));
        AutismAddons.modules().register(new AutoRenderModule(catRender));
        AutismAddons.modules().register(new PaperRigModule(catRender));
        AutismAddons.modules().register(new FakePayModule(catFake));
        AutismAddons.modules().register(new FakePaymentsModule(catFake));
        AutismAddons.modules().register(new FakeRolesModule(catFake));

        // Movement.
        AutismAddons.modules().register(new FlightPlusModule(catMovement));

        // Trading.
        AutismAddons.modules().register(new AHFlipperModule(catTrading));

        // Dogs Misc Tools (Zelith misc modules, ported).
        AutismAddons.modules().register(new SprintModule(catDogsMisc));
        AutismAddons.modules().register(new AntiAFKModule(catDogsMisc));
        AutismAddons.modules().register(new FastPlaceModule(catDogsMisc));
        AutismAddons.modules().register(new FreeLookModule(catDogsMisc));
        AutismAddons.modules().register(new AutoEatModule(catDogsMisc));
        AutismAddons.modules().register(new SwingSpeedModule(catDogsMisc));
        AutismAddons.modules().register(new CoordSnapperModule(catDogsMisc));
        AutismAddons.modules().register(new FakePlayerModule(catDogsMisc));
        AutismAddons.modules().register(new AutoLogModule(catDogsMisc));
        AutismAddons.modules().register(new AutoToolModule(catDogsMisc));
        AutismAddons.modules().register(new TPASpammerModule(catDogsMisc));
        AutismAddons.modules().register(new TabDetectorModule(catDogsMisc));
        AutismAddons.modules().register(new WeatherNotifierModule(catDogsMisc));
        AutismAddons.modules().register(new HomeSetterModule(catDogsMisc));
        AutismAddons.modules().register(new SkinChangerModule(catDogsMisc));

        AutismAddons.commands().register(new BedrockFinderCommand());
        AutismAddons.commands().register(new BaseLogCommand());
        AutismAddons.hud().register(new SeedHud());
        AutismAddons.hud().register(new StashWarningHud());
        AutismAddons.hud().register(new RegionMapHud());
        AutismAddons.hud().register(new FakeScoreboardHud());
    }

    @Override
    public String getPackage() {
        return "com.autism.seedcracker";
    }
}
