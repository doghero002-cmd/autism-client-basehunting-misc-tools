package com.autism.seedcracker;

import com.autism.seedcracker.commands.BaseLogCommand;
import com.autism.seedcracker.commands.BedrockFinderCommand;
import com.autism.seedcracker.commands.HeatConfirmCommand;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.hud.BaseTrackerHud;
import com.autism.seedcracker.hud.FakeScoreboardHud;
import com.autism.seedcracker.hud.SeedHud;
import com.autism.seedcracker.hud.SessionStatsHud;
import com.autism.seedcracker.hud.StashWarningHud;
import com.autism.seedcracker.krypton.AutoFireworkModule;
import com.autism.seedcracker.krypton.AutoTPAModule;
import com.autism.seedcracker.krypton.KeyPearlModule;
import com.autism.seedcracker.krypton.NameProtectModule;
import com.autism.seedcracker.krypton.QuickMacroModule;
import com.autism.seedcracker.modules.AHFlipperModule;
import com.autism.seedcracker.modules.AHSniperModule;
import com.autism.seedcracker.modules.ActivityFinderModule;
import com.autism.seedcracker.modules.AmethystEspModule;
import com.autism.seedcracker.modules.AntiAFKModule;
import com.autism.seedcracker.modules.AhSellModule;
import com.autism.seedcracker.modules.AntiTrapModule;

import com.autism.seedcracker.modules.AutoEatModule;
import com.autism.seedcracker.modules.AutoLogModule;
import com.autism.seedcracker.modules.AutoMineModule;
import com.autism.seedcracker.modules.AutoRenderModule;
import com.autism.seedcracker.modules.AutoToolModule;
import com.autism.seedcracker.modules.BaseLogBrowserModule;
import com.autism.seedcracker.modules.BedrockFinderModule;
import com.autism.seedcracker.modules.BedrockHoleEspModule;
import com.autism.seedcracker.modules.BoneDropperModule;
import com.autism.seedcracker.modules.ChatGamesModule;
import com.autism.seedcracker.modules.ChunkFinderModule;
import com.autism.seedcracker.modules.CoordSnapperModule;
import com.autism.seedcracker.modules.ElytraWarnerModule;
import com.autism.seedcracker.modules.EntityScannerModule;
import com.autism.seedcracker.modules.FakePayModule;
import com.autism.seedcracker.modules.FakePaymentsModule;
import com.autism.seedcracker.modules.FakePlayerModule;
import com.autism.seedcracker.modules.FakeRolesModule;
import com.autism.seedcracker.modules.FlagDetectorModule;
import com.autism.seedcracker.modules.FastPlaceModule;
import com.autism.seedcracker.modules.FreeLookModule;
import com.autism.seedcracker.modules.GrowthFinderModule;
import com.autism.seedcracker.modules.HoleEspModule;
import com.autism.seedcracker.modules.HoleTunnelStairsEspModule;
import com.autism.seedcracker.modules.HomeSetterModule;
import com.autism.seedcracker.modules.NetherTunnelFinderModule;
import com.autism.seedcracker.modules.PaperRigModule;
import com.autism.seedcracker.modules.PrimeChunkFinderModule;
import com.autism.seedcracker.modules.RegionMapModule;
import com.autism.seedcracker.modules.SchematicBuilderModule;
import com.autism.seedcracker.modules.ScoreboardHiderModule;
import com.autism.seedcracker.modules.SeedcrackerModule;
import com.autism.seedcracker.modules.ShopBuyerModule;
import com.autism.seedcracker.modules.SkinChangerModule;
import com.autism.seedcracker.modules.SpawnerFinderModule;
import com.autism.seedcracker.modules.SpawnerProtectModule;
import com.autism.seedcracker.modules.SprintModule;
import com.autism.seedcracker.modules.StashFinderModule;
import com.autism.seedcracker.modules.StructureDetectorModule;
import com.autism.seedcracker.modules.SusChunkFinderModule;
import com.autism.seedcracker.modules.TunnelBaseWaterModule;
import com.autism.seedcracker.modules.SwingSpeedModule;
import com.autism.seedcracker.modules.TPASpammerModule;
import com.autism.seedcracker.modules.TabDetectorModule;
import com.autism.seedcracker.modules.TunnelBaseFinderModule;
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

    /** Track a module in our own obfuscation-proof registry, then register it with the client. */
    private static void reg(autismclient.modules.Module module) {
        com.autism.seedcracker.compat.ModuleLookup.track(module);
        AutismAddons.modules().register(module);
    }

    /** Register + assign a named tab via the reflection-based CategoryAssigner (restores per-tab
     * grouping across client versions without referencing the renamed category type). */
    private static void regTab(autismclient.modules.Module module, String tab) {
        com.autism.seedcracker.compat.CategoryAssigner.assign(module, tab);
        reg(module);
    }

    @Override
    public void onInitialize() {
        this.name = "Dogs BaseHunting/QQL Tools";
        this.authors = "KaptainWutax, 19MisterX98";
        this.color = 0xFF50C878;

        // Modules register with NO explicit category TYPE: reg(...) lands each under the addon's
        // auto category, and regTab(...) assigns a named tab through CategoryAssigner's reflective
        // lookup (the category type is ModuleCategory in v5, renamed in obfuscated clients, so it's
        // resolved at runtime, never referenced at compile time). If the lookup fails on some future
        // client it degrades to one tab instead of failing to load.

        reg(new SeedcrackerModule());
        reg(new BedrockFinderModule());
        reg(new com.autism.seedcracker.modules.TextureCrackerModule());
        reg(new DonutRTPStashFinderModule());
        reg(new RelogLoaderModule());

        // Zelith chunk-scanner finder modules (ported). The shared renderer self-registers a
        // LevelRenderEvents collector that all six feed their flagged chunks into.
        ChunkFlagRenderer.init();
        regTab(new com.autism.seedcracker.modules.NetheriteFinderModule(), "Finders");
        regTab(new StashFinderModule(), "Finders");
        regTab(new ChunkFinderModule(), "Finders");
        regTab(new SpawnerFinderModule(), "Finders");
        regTab(new SusChunkFinderModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.SeedRayModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.ChunkWaypointsModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.FinderOverlayModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.PlayerChunksModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.LightSourceFinderModule(), "Finders");
        regTab(new PrimeChunkFinderModule(), "Finders");
        regTab(new ActivityFinderModule(), "Finders");
        regTab(new GrowthFinderModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.HeatMapRadarModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.RaidPlannerModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.BaseWebhookModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.ChunkKeeperModule(), "Finders");
        regTab(new BaseLogBrowserModule(), "Finders");
        regTab(new TunnelBaseFinderModule(), "Finders");
        regTab(new TunnelBaseWaterModule(), "Finders");
        regTab(new NetherTunnelFinderModule(), "Finders");
        regTab(new StructureDetectorModule(), "Finders");

        // Zelith entity / fake modules (ported), each under its own tab.
        regTab(new EntityScannerModule(), "Entity");
        regTab(new AntiTrapModule(), "Entity");
        regTab(new com.autism.seedcracker.modules.EyeFinderModule(), "Entity");
        regTab(new com.autism.seedcracker.modules.ItemFrameEspModule(), "Entity");
        regTab(new BoneDropperModule(), "Entity");
        regTab(new SpawnerProtectModule(), "Entity");
        regTab(new AutoRenderModule(), "Render");
        regTab(new PaperRigModule(), "Render");
        regTab(new ScoreboardHiderModule(), "Render");
        regTab(new com.autism.seedcracker.modules.StorageRecorderModule(), "Render");
        regTab(new FakePayModule(), "Fake");
        regTab(new FakePaymentsModule(), "Fake");
        regTab(new FakeRolesModule(), "Fake");

        // Trading.
        regTab(new AHFlipperModule(), "Trading");
        regTab(new AHSniperModule(), "Trading");
        regTab(new ShopBuyerModule(), "Trading");
        regTab(new AhSellModule(), "Trading");

        // Dogs Misc Tools (Zelith misc modules, ported).
        String dogs = "Dogs Misc Tools";
        regTab(new SprintModule(), dogs);
        regTab(new AntiAFKModule(), dogs);
        regTab(new FastPlaceModule(), dogs);
        regTab(new FreeLookModule(), dogs);
        regTab(new AutoEatModule(), dogs);
        regTab(new AutoMineModule(), dogs);
        regTab(new SwingSpeedModule(), dogs);
        regTab(new CoordSnapperModule(), dogs);
        regTab(new FakePlayerModule(), dogs);
        regTab(new AutoLogModule(), dogs);
        regTab(new com.autism.seedcracker.modules.PlayerPanicModule(), dogs);
        regTab(new FlagDetectorModule(), dogs);
        regTab(new com.autism.seedcracker.modules.MacroProtectorModule(), dogs);
        regTab(new com.autism.seedcracker.modules.SpectatorDetectorModule(), dogs);
        regTab(new com.autism.seedcracker.modules.PanicPayModule(), dogs);
        regTab(new com.autism.seedcracker.modules.AntiCheatGuesserModule(), dogs);
        regTab(new com.autism.seedcracker.modules.FakeLatencyModule(), dogs);
        regTab(new com.autism.seedcracker.modules.PositionPacketFilterModule(), dogs);
        regTab(new com.autism.seedcracker.modules.CoordinateProtectorModule(), dogs);
        regTab(new com.autism.seedcracker.modules.AutoStoreModule(), dogs);
        regTab(new com.autism.seedcracker.modules.AutoSmeltModule(), dogs);
        regTab(new com.autism.seedcracker.modules.ChestStealerModule(), dogs);
        regTab(new com.autism.seedcracker.modules.AutoReplenishModule(), dogs);
        regTab(new com.autism.seedcracker.modules.BalanceTagsModule(), dogs);
        regTab(new AutoToolModule(), dogs);
        regTab(new TPASpammerModule(), dogs);
        regTab(new TabDetectorModule(), dogs);
        regTab(new WeatherNotifierModule(), dogs);
        regTab(new HomeSetterModule(), dogs);
        regTab(new com.autism.seedcracker.modules.HomeMetaModule(), dogs);
        regTab(new SkinChangerModule(), dogs);
        regTab(new ChatGamesModule(), dogs);
        regTab(new RegionMapModule(), dogs);
        regTab(new SchematicBuilderModule(), dogs);
        regTab(new ElytraWarnerModule(), dogs);
        regTab(new com.autism.seedcracker.modules.TranslateModule(), dogs);

        // ESP (moved to the Dogs tab).
        regTab(new HoleEspModule(), dogs);
        regTab(new HoleTunnelStairsEspModule(), dogs);
        regTab(new AmethystEspModule(), dogs);
        regTab(new BedrockHoleEspModule(), dogs);

        // Krypton misc modules (ported).
        regTab(new KeyPearlModule(), dogs);
        regTab(new AutoFireworkModule(), dogs);
        regTab(new AutoTPAModule(), dogs);
        regTab(new QuickMacroModule(), dogs);
        regTab(new NameProtectModule(), dogs);

        // Combat suite (subtle mace PVP).
        regTab(new com.autism.seedcracker.modules.MacePvpModule(), dogs);

        AutismAddons.commands().register(new BedrockFinderCommand());
        AutismAddons.commands().register(new BaseLogCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.TextureCrackCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.CrossCheckCommand());
        AutismAddons.commands().register(new HeatConfirmCommand());
        AutismAddons.hud().register(new SeedHud());
        AutismAddons.hud().register(new StashWarningHud());
        AutismAddons.hud().register(new SessionStatsHud());
        AutismAddons.hud().register(new FakeScoreboardHud());
        AutismAddons.hud().register(new BaseTrackerHud());
    }

    @Override
    public String getPackage() {
        return "com.autism.seedcracker";
    }
}
