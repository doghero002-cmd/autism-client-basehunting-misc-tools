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
import com.autism.seedcracker.modules.FakePlayerModule;
import com.autism.seedcracker.modules.FlagDetectorModule;
import com.autism.seedcracker.modules.FastPlaceModule;
import com.autism.seedcracker.modules.GrowthFinderModule;
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

    /**
     * {@code ApiVersion.CURRENT} is inlined at compile time (= the client jar in libs/); {@code AutismAddons.apiVersion()}
     * runs in the host, so it's the installed client's version. Declare the lower of the two: loads on the public
     * 5.0 client (API v3) and on V4 / 5.1 (API v4) without the "needs newer API" skip or the "built against" warning.
     */
    @Override
    public int apiVersion() {
        int host;
        try {
            host = AutismAddons.apiVersion();
        } catch (Throwable t) {
            host = -1;
        }
        return com.autism.seedcracker.compat.ApiCompat.declared(ApiVersion.CURRENT, host);
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

        // "Start here" setup panel first, so new users meet the one-click loadouts before the
        // long module list.
        reg(new com.autism.seedcracker.modules.QqlSetupModule());

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
        regTab(new com.autism.seedcracker.modules.SeedMapModule(), "Finders");
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
        regTab(new NetherTunnelFinderModule(), "Finders");
        regTab(new StructureDetectorModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.PortalFinderModule(), "Finders");
        regTab(new com.autism.seedcracker.modules.SignFinderModule(), "Finders");

        // The Finders hub bundles the simple set-and-forget finders (Sign/Portal/PlayerChunks/
        // Structure/BaseWebhook/FinderOverlay) into one menu entry. It must be registered AFTER the
        // finders it drives, since its constructor looks them up in ModuleLookup.
        regTab(new com.autism.seedcracker.modules.FindersModule(), "Finders");

        // Zelith entity / fake modules (ported), each under its own tab.
        regTab(new EntityScannerModule(), "Entity");
        regTab(new com.autism.seedcracker.modules.VisualRangeModule(), "Entity");
        regTab(new AntiTrapModule(), "Entity");
        regTab(new com.autism.seedcracker.modules.EyeFinderModule(), "Entity");
        regTab(new com.autism.seedcracker.modules.ItemFrameEspModule(), "Entity");
        regTab(new BoneDropperModule(), "Entity");
        regTab(new SpawnerProtectModule(), "Entity");
        regTab(new AutoRenderModule(), "Render");
        regTab(new PaperRigModule(), "Render");
        regTab(new ScoreboardHiderModule(), "Render");
        regTab(new com.autism.seedcracker.modules.StorageRecorderModule(), "Render");
        regTab(new com.autism.seedcracker.modules.FakeIdentityModule(), "Fake");

        // Trading.
        regTab(new AHFlipperModule(), "Trading");
        regTab(new AHSniperModule(), "Trading");
        regTab(new ShopBuyerModule(), "Trading");
        regTab(new AhSellModule(), "Trading");
        regTab(new com.autism.seedcracker.modules.PriceCheckModule(), "Trading");

        // FreeLook and Hole ESP are not registered: the base client ships identical modules.

        // Hands-off routines that act on the world or inventory for you.
        String automation = "Automation";
        regTab(new com.autism.seedcracker.motion.GoToModule(), automation);
        regTab(new AntiAFKModule(), automation);
        regTab(new AutoEatModule(), automation);
        regTab(new AutoMineModule(), automation);
        regTab(new AutoToolModule(), automation);
        regTab(new com.autism.seedcracker.modules.AutoFishModule(), automation);
        regTab(new com.autism.seedcracker.modules.InventoryCleanerModule(), automation);
        regTab(new com.autism.seedcracker.modules.AutoStoreModule(), automation);
        regTab(new com.autism.seedcracker.modules.AutoSmeltModule(), automation);
        regTab(new com.autism.seedcracker.modules.ChestStealerModule(), automation);
        regTab(new com.autism.seedcracker.modules.AutoReplenishModule(), automation);
        regTab(new SchematicBuilderModule(), automation);
        regTab(new com.autism.seedcracker.modules.GatherModule(), automation);
        regTab(new com.autism.seedcracker.modules.ElytraTravelModule(), automation);

        // Fighting and escaping fights.
        String combat = "Combat";
        regTab(new com.autism.seedcracker.modules.MacePvpModule(), combat);
        regTab(new KeyPearlModule(), combat);
        regTab(new AutoFireworkModule(), combat);
        regTab(new ElytraWarnerModule(), combat);

        // Staying unbanned and un-raided: staff/AC detection, panic exits, coord hygiene.
        String safety = "Safety";
        regTab(new AutoLogModule(), safety);
        regTab(new com.autism.seedcracker.modules.PlayerPanicModule(), safety);
        regTab(new com.autism.seedcracker.modules.PanicPayModule(), safety);
        regTab(new FlagDetectorModule(), safety);
        regTab(new com.autism.seedcracker.modules.AntiCheatGuesserModule(), safety);
        regTab(new com.autism.seedcracker.modules.MacroProtectorModule(), safety);
        regTab(new com.autism.seedcracker.modules.SpectatorDetectorModule(), safety);
        regTab(new TabDetectorModule(), safety);
        regTab(new com.autism.seedcracker.modules.SessionGuardModule(), safety);
        regTab(new com.autism.seedcracker.modules.CoordinateProtectorModule(), safety);
        regTab(new com.autism.seedcracker.modules.PositionPacketFilterModule(), safety);
        regTab(new com.autism.seedcracker.modules.FakeLatencyModule(), safety);
        regTab(new NameProtectModule(), safety);

        // Block highlighters.
        String esp = "ESP";
        regTab(new HoleTunnelStairsEspModule(), esp);
        regTab(new AmethystEspModule(), esp);
        regTab(new BedrockHoleEspModule(), esp);

        // Small quality-of-life helpers.
        String utility = "Utility";
        regTab(new SprintModule(), utility);
        regTab(new FastPlaceModule(), utility);
        regTab(new SwingSpeedModule(), utility);
        regTab(new CoordSnapperModule(), utility);
        regTab(new com.autism.seedcracker.modules.WaypointsModule(), utility);
        regTab(new com.autism.seedcracker.modules.BreadcrumbModule(), utility);
        regTab(new RegionMapModule(), utility);
        regTab(new WeatherNotifierModule(), utility);
        regTab(new SkinChangerModule(), utility);
        regTab(new com.autism.seedcracker.modules.TranslateModule(), utility);
        regTab(new com.autism.seedcracker.modules.BalanceTagsModule(), utility);

        // Server chat/command helpers (homes, TPA, chat games, macros).
        String server = "Server Tools";
        regTab(new HomeSetterModule(), server);
        regTab(new TPASpammerModule(), server);
        regTab(new AutoTPAModule(), server);
        regTab(new ChatGamesModule(), server);
        regTab(new com.autism.seedcracker.modules.ChatBotModule(), server);
        regTab(new QuickMacroModule(), server);
        regTab(new FakePlayerModule(), "Fake");

        AutismAddons.commands().register(new com.autism.seedcracker.commands.QqlCommand());
        AutismAddons.commands().register(new BedrockFinderCommand());
        AutismAddons.commands().register(new BaseLogCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.TextureCrackCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.CrossCheckCommand());
        AutismAddons.commands().register(new HeatConfirmCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.PriceCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.FlipCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.GotoCommand());
        AutismAddons.commands().register(new com.autism.seedcracker.commands.FlagLogCommand());
        AutismAddons.hud().register(new SeedHud());
        AutismAddons.hud().register(new StashWarningHud());
        AutismAddons.hud().register(new SessionStatsHud());
        AutismAddons.hud().register(new FakeScoreboardHud());
        AutismAddons.hud().register(new BaseTrackerHud());

        // Local QA bridge (127.0.0.1 only): lets an external driver run commands and read state without
        // window focus. No-op if the port is taken.
        com.autism.seedcracker.bridge.GameBridge.start();
    }

    @Override
    public String getPackage() {
        return "com.autism.seedcracker";
    }
}
