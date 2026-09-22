package com.autism.seedcracker.rtp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.donutsmp.rtpmapper.automation.PositionObservation;
import com.donutsmp.rtpmapper.automation.RtpAttemptSettings;
import com.donutsmp.rtpmapper.automation.RtpClock;
import com.donutsmp.rtpmapper.automation.RtpController;
import com.donutsmp.rtpmapper.automation.RtpEnvironmentSnapshot;
import com.donutsmp.rtpmapper.automation.RtpSampleResult;
import com.donutsmp.rtpmapper.region.RtpRegion;
import com.donutsmp.rtpmapper.region.RtpRegionCycle;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.api.module.StringListSetting;
import autismclient.api.module.StringSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismCompatManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Donut RTP Stash Finder.
 *
 * Repeatedly RTPs around DonutSMP. When you land closer to 0,0 than the configured threshold,
 * it (optionally) runs a Baritone base-search that digs down and looks for stash blocks
 * (chests, hoppers, ...) for a while. In "Save & RTP" mode it logs any base it finds to
 * bases.txt and immediately RTPs away instead of searching.
 *
 * Requires Baritone (baritone-meteor or upstream) for the base-search part. The optional
 * relog chunk-loading is provided by the separate "Relog Loader" module.
 */
public final class DonutRTPStashFinderModule extends Module {

    public enum Mode { SEARCH, SAVE_AND_RTP }
    public enum ScanMode { DETECT_ONLY, BARITONE_MINE, WATER_MINE }
    public enum RegionMode { ROTATE_ALL, SPECIFIC }

    /** Maps a friendly region choice to the engine's RtpRegion. */
    public enum RegionChoice {
        NA_EAST(RtpRegion.NA_EAST),
        NA_WEST(RtpRegion.NA_WEST),
        EU_CENTRAL(RtpRegion.EU_CENTRAL),
        EU_WEST(RtpRegion.EU_WEST),
        ASIA(RtpRegion.ASIA),
        OCEANIA(RtpRegion.OCEANIA);

        private final RtpRegion region;
        RegionChoice(RtpRegion region) { this.region = region; }
        public RtpRegion region() { return region; }
    }

    // Post-teleport search phase, driven from the engine's sample hook.
    private enum Phase { RTP, SEARCHING }

    private Phase phase = Phase.RTP;
    private long searchEndMs = 0L;
    private boolean loggedThisLanding = false;
    private Path logFile;

    // Wander state while searching: Baritone descends to the dig depth, then roams underground
    // within wanderRadius of the landing point.
    private double wanderCenterX = 0.0;
    private double wanderCenterZ = 0.0;
    private boolean wandering = false;
    private boolean reachedDepth = false;
    private static final java.util.Random WANDER_RNG = new java.util.Random();

    // The imported DonutSMP-Bot RTP engine.
    private RtpController controller;
    private RtpRegionCycle regionCycle;

    // ---- RTP ----
    private final StringSetting rtpCommand = add(new StringSetting("rtp-command", "RTP command", "/rtp")
        .description("Base RTP command. The region argument is appended based on the Region settings.")
        .group("RTP"));
    private final IntSetting rtpCooldown = add(new IntSetting("rtp-cooldown", "RTP cooldown (s)", 5, 1, 600, 1)
        .description("Seconds to wait after an RTP before checking position / re-RTPing.")
        .group("RTP"));
    private final IntSetting minCoord = add(new IntSetting("min-coord", "Min coord (abs, k)", 0, 0, 2000, 10)
        .description("Skip landings closer than this many THOUSAND blocks (|x| or |z|): far coords = older, un-picked regions. 0 = off (Water RTPMiner coord filter).")
        .group("RTP"));
    private final IntSetting threshold = add(new IntSetting("threshold", "Distance threshold", 50000, 0, 300000, 1000)
        .description("Run the base search when closer than this to 0,0 (uses max of |x|,|z|). DonutSMP world border is 300000.")
        .group("RTP"));
    private final IntSetting stuckTimeout = add(new IntSetting("stuck-timeout", "Stuck RTP timeout (s)", 10, 1, 60, 1)
        .description("If your coords don't change this long after an RTP, relog and try another region.")
        .group("RTP"));

    // ---- Region selection ----
    private final EnumSetting<RegionMode> regionMode = add(new EnumSetting<>("region-mode", "Region selection", RegionMode.ROTATE_ALL, RegionMode.values())
        .description("ROTATE_ALL cycles through every region (never repeating the last). SPECIFIC only RTPs to one region.")
        .group("Region"));
    private final EnumSetting<RegionChoice> specificRegion = add(new EnumSetting<>("specific-region", "Specific region", RegionChoice.NA_WEST, RegionChoice.values())
        .description("The only region to RTP to when Region selection is SPECIFIC.")
        .group("Region")
        .visibleWhen(() -> regionMode.get() == RegionMode.SPECIFIC));
    private final BoolSetting allowEast = add(new BoolSetting("allow-east", "Allow east in rotation", true)
        .description("Include east when rotating. NOTE: east is often full and can break RTPing - the stuck-RTP recovery handles that.")
        .group("Region")
        .visibleWhen(() -> regionMode.get() == RegionMode.ROTATE_ALL));

    // ---- Mode / behaviour ----
    private final EnumSetting<Mode> mode = add(new EnumSetting<>("mode", "Mode", Mode.SEARCH, Mode.values())
        .description("SEARCH digs/searches for stash blocks. SAVE_AND_RTP logs a found base and RTPs away.")
        .group("Behaviour"));
    private final EnumSetting<ScanMode> scanMode = add(new EnumSetting<>("scan-mode", "Search method", ScanMode.DETECT_ONLY, ScanMode.values())
        .description("DETECT_ONLY scans loaded chunks. BARITONE_MINE digs down and mines toward the target blocks. WATER_MINE = raw Water-client RTPMiner movement: look-down dig to the target Y, then straight-line key-input mining (no Baritone). WARNING: raw bot movement - may flag anti-cheats.")
        .group("Behaviour"));
    private final IntSetting waterTargetY = add(new IntSetting("water-target-y", "Water target Y", -55, -62, -30, 1)
        .description("WATER_MINE: Y level to dig down to before tunneling (Water default -55).")
        .group("Behaviour")
        .visibleWhen(() -> scanMode.get() == ScanMode.WATER_MINE));
    private final IntSetting waterObsidianSlot = add(new IntSetting("water-obsidian-slot", "Obsidian slot", 2, 1, 9, 1)
        .description("WATER_MINE: hotbar slot with obsidian for Y-recovery pillaring.")
        .group("Behaviour")
        .visibleWhen(() -> scanMode.get() == ScanMode.WATER_MINE));
    private final StringListSetting targetBlocks = add(new StringListSetting("target-blocks", "Target blocks",
            "minecraft:chest|minecraft:barrel|minecraft:shulker_box|minecraft:hopper|minecraft:trapped_chest|minecraft:ender_chest")
        .description("Block ids (| separated) that count as a stash/base.")
        .group("Behaviour"));
    private final IntSetting scanRange = add(new IntSetting("scan-range", "Scan range (blocks)", 80, 8, 512, 8)
        .description("Small bubble around the player scanned for stash blocks (default ~5 chunks at deepslate level). Kept small so it never lags; Baritone moves you to cover ground.")
        .group("Behaviour"));
    private final IntSetting wanderRadius = add(new IntSetting("wander-radius", "Wander radius", 5000, 16, 300000, 16)
        .description("How far Baritone roams from the landing point while searching (not straight - it wanders inside this bubble).")
        .group("Behaviour"));
    private final IntSetting minCluster = add(new IntSetting("min-cluster", "Min cluster size", 3, 1, 64, 1)
        .description("How many stash blocks must be near each other to count as a base (filters lone blocks).")
        .group("Behaviour"));
    private final IntSetting minY = add(new IntSetting("min-y", "Min Y (detection)", -64, -64, 320, 1)
        .description("Only count stash blocks at or above this Y.")
        .group("Behaviour"));
    private final IntSetting maxY = add(new IntSetting("max-y", "Max Y (detection)", 16, -64, 320, 1)
        .description("Only count stash blocks at or below this Y (default 16 = deepslate levels). Blocks above are ignored and not pathed to.")
        .group("Behaviour"));
    private final BoolSetting skipStructures = add(new BoolSetting("skip-structures", "Skip structures", true)
        .description("Ignore stash blocks that lie inside SeedCracker-detected structures (dungeons, trial chambers, etc.) so they aren't flagged as bases.")
        .group("Behaviour"));
    private final IntSetting structureExclusionRadius = add(new IntSetting("structure-exclusion-radius", "Structure exclusion radius", 24, 4, 128, 4)
        .description("Blocks within this distance of a detected structure are ignored.")
        .group("Behaviour")
        .visibleWhen(() -> skipStructures.get()));
    private final IntSetting scanInterval = add(new IntSetting("scan-interval", "Scan interval (ticks)", 20, 1, 200, 1)
        .description("Ticks between detection scans (higher = less lag).")
        .group("Behaviour"));
    private final IntSetting searchDuration = add(new IntSetting("search-duration", "Search duration (s)", 900, 10, 7200, 10)
        .description("How long to search before RTPing again (default 15 min).")
        .group("Behaviour"));
    private final IntSetting digDepth = add(new IntSetting("dig-depth", "Dig depth (Y)", -1, -60, 320, 1)
        .description("Y level Baritone digs down to before searching (Baritone mode).")
        .group("Behaviour"));
    private final BoolSetting autoDisableOnFind = add(new BoolSetting("auto-disable-on-find", "Stop after find", false)
        .description("Disable the module once a base is logged (Save & RTP mode).")
        .group("Behaviour"));

    // ---- Baritone: core toggles ----
    private final BoolSetting allowBreak = add(new BoolSetting("b-allow-break", "Allow break", true)
        .description("Let Baritone break blocks.").group("Baritone"));
    private final BoolSetting allowPlace = add(new BoolSetting("b-allow-place", "Allow place", true)
        .description("Let Baritone place blocks.").group("Baritone"));
    private final BoolSetting allowSprint = add(new BoolSetting("b-allow-sprint", "Allow sprint", true)
        .description("Let Baritone sprint.").group("Baritone"));
    private final BoolSetting autoEat = add(new BoolSetting("b-auto-eat", "Auto eat", true)
        .description("Let Baritone eat automatically.").group("Baritone"));
    private final BoolSetting autoTool = add(new BoolSetting("b-auto-tool", "Auto tool", true)
        .description("Automatically select the best tool.").group("Baritone"));
    private final BoolSetting allowInventory = add(new BoolSetting("b-allow-inventory", "Allow inventory moves", false)
        .description("Let Baritone move items to the hotbar.").group("Baritone"));

    // ---- Baritone: movement ----
    private final BoolSetting allowParkour = add(new BoolSetting("b-allow-parkour", "Allow parkour", false)
        .description("Allow parkour jumps (can be unreliable).").group("Baritone Movement"));
    private final BoolSetting allowParkourPlace = add(new BoolSetting("b-allow-parkour-place", "Allow parkour place", false)
        .description("Allow placing blocks mid-parkour.").group("Baritone Movement"));
    private final BoolSetting allowDiagonalAscend = add(new BoolSetting("b-allow-diagonal-ascend", "Allow diagonal ascend", false)
        .description("Allow ascending diagonally.").group("Baritone Movement"));
    private final BoolSetting allowDiagonalDescend = add(new BoolSetting("b-allow-diagonal-descend", "Allow diagonal descend", false)
        .description("Allow descending diagonally (unsafe in the nether).").group("Baritone Movement"));
    private final BoolSetting allowDownward = add(new BoolSetting("b-allow-downward", "Allow downward mining", true)
        .description("Allow mining the block directly beneath its feet.").group("Baritone Movement"));
    private final BoolSetting allowVines = add(new BoolSetting("b-allow-vines", "Allow vines", false)
        .description("Enable vine pathing (gimmicky, can trap Baritone).").group("Baritone Movement"));
    private final BoolSetting assumeStep = add(new BoolSetting("b-assume-step", "Assume step", false)
        .description("Assume step functionality (don't jump on ascend).").group("Baritone Movement"));
    private final BoolSetting sprintAscends = add(new BoolSetting("b-sprint-ascends", "Sprint ascends", true)
        .description("Sprint and jump a block early on ascends.").group("Baritone Movement"));
    private final BoolSetting freeLook = add(new BoolSetting("b-free-look", "Free look", true)
        .description("Move without forcing client-sided rotations.").group("Baritone Movement"));
    private final BoolSetting antiCheat = add(new BoolSetting("b-anti-cheat", "Anti-cheat compatibility", true)
        .description("Adjust behavior to work better on anti-cheats.").group("Baritone Movement"));
    private final BoolSetting legitMode = add(new BoolSetting("b-legit-mode", "Legit / smooth movement", true)
        .description("Force a human-looking Baritone profile (free look, clamped reach, slowed/randomized rotations, no render) so it doesn't head-flick. Overrides the render settings above.")
        .group("Baritone Movement"));

    // ---- Baritone: blocks / avoidance ----
    private final BoolSetting avoidUpdatingFalling = add(new BoolSetting("b-avoid-updating-falling", "Avoid updating falling blocks", true)
        .description("Never trigger cascading sand/gravel falls (helps avoid lava too).").group("Baritone Avoidance"));
    private final BoolSetting pauseMiningForFalling = add(new BoolSetting("b-pause-mining-falling", "Pause mining for falling blocks", true)
        .description("Wait until falling blocks settle before continuing.").group("Baritone Avoidance"));
    private final BoolSetting avoidance = add(new BoolSetting("b-avoidance", "Mob avoidance", false)
        .description("Avoid mobs and spawners (small performance cost).").group("Baritone Avoidance"));
    private final IntSetting mobAvoidanceRadius = add(new IntSetting("b-mob-avoid-radius", "Mob avoid radius", 8, 0, 32, 1)
        .description("Distance to avoid mobs.").group("Baritone Avoidance").visibleWhen(() -> avoidance.get()));
    private final IntSetting spawnerAvoidanceRadius = add(new IntSetting("b-spawner-avoid-radius", "Spawner avoid radius", 16, 0, 48, 1)
        .description("Distance to avoid mob spawners.").group("Baritone Avoidance").visibleWhen(() -> avoidance.get()));

    // ---- Baritone: falling ----
    private final IntSetting maxFallNoWater = add(new IntSetting("b-max-fall-no-water", "Max fall (no water)", 3, 0, 20, 1)
        .description("How far Baritone may fall onto solid ground without a water bucket.").group("Baritone Falling"));
    private final BoolSetting allowWaterBucketFall = add(new BoolSetting("b-allow-water-bucket-fall", "Allow water bucket fall", true)
        .description("Allow falling arbitrary distances with a water bucket (unreliable).").group("Baritone Falling"));
    private final IntSetting maxFallBucket = add(new IntSetting("b-max-fall-bucket", "Max fall (bucket)", 20, 0, 60, 1)
        .description("How far Baritone may fall with a water bucket.").group("Baritone Falling").visibleWhen(() -> allowWaterBucketFall.get()));

    // ---- Baritone: render ----
    private final BoolSetting renderPath = add(new BoolSetting("b-render-path", "Render path", true)
        .description("Render the current path.").group("Baritone Render"));
    private final BoolSetting renderGoal = add(new BoolSetting("b-render-goal", "Render goal", true)
        .description("Render the current goal.").group("Baritone Render"));
    private final BoolSetting renderCachedChunks = add(new BoolSetting("b-render-cached-chunks", "Render cached chunks", false)
        .description("Render cached chunks semi-transparently (can hurt FPS).").group("Baritone Render"));

    // ---- Baritone: elytra ----
    private final BoolSetting elytraAutoJump = add(new BoolSetting("b-elytra-auto-jump", "Elytra auto jump", false)
        .description("Automatically path to and jump off ledges to start flying.").group("Baritone Elytra"));
    private final BoolSetting elytraAutoSwap = add(new BoolSetting("b-elytra-auto-swap", "Elytra auto swap", true)
        .description("Swap to a fresh elytra when durability is low.").group("Baritone Elytra"));
    private final BoolSetting elytraConserveFireworks = add(new BoolSetting("b-elytra-conserve-fireworks", "Conserve fireworks", false)
        .description("Avoid using fireworks while descending.").group("Baritone Elytra"));
    private final IntSetting elytraMinDurability = add(new IntSetting("b-elytra-min-durability", "Elytra min durability", 5, 1, 100, 1)
        .description("Minimum elytra durability before swapping/landing.").group("Baritone Elytra"));

    public DonutRTPStashFinderModule() {
        super(SeedcrackerAddon.ID + ":donut-rtp", "Donut RTP Stash Finder",
            "RTPs around DonutSMP and searches for stashes near 0,0. WARNING: automated movement may flag anti-cheats.");
    }

    @Override
    public void onEnable() {
        logFile = autismclient.AutismClientAddon.FOLDER.toPath().resolve("bases.txt");
        regionCycle = new RtpRegionCycle();
        controller = new RtpController(
            RtpClock.system(),
            this::attemptSettings,          // RtpSettingsProvider
            this::sendRtpCommand,           // RtpCommandSender
            this::onTeleportConfirmed       // RtpSampleSink
        );
        phase = Phase.RTP;
        loggedThisLanding = false;
        ACTIVE = true;
        AutismClientMessaging.sendPrefixed("§c§l[Warning] §cDonut RTP Stash Finder uses automated RTP/Baritone movement that anti-cheats may flag. Use at your own risk.");
        autismclient.util.AutismNotifications.warning("RTP Stash Finder: may flag anti-cheat");
        AutismClientMessaging.sendPrefixed("§aDonut RTP Stash Finder enabled. Mode: " + mode.get());
        if (!AutismCompatManager.isBaritoneAvailable()) {
            AutismClientMessaging.sendPrefixed("§eBaritone not detected - base-search (dig/mine) disabled; detection still works.");
        }
        Minecraft mc = Minecraft.getInstance();
        RtpEnvironmentSnapshot env = buildSnapshot(mc);
        if (env != null) controller.start(env);
    }

    /** True while the module is enabled (drives the on-screen warning HUD). */
    public static volatile boolean ACTIVE = false;

    @Override
    public void onDisable() {
        if (controller != null) controller.stop();
        stopBaritone();
        Minecraft mc = Minecraft.getInstance();
        if (mc.options != null) waterReleaseKeys(mc);
        wandering = false;
        phase = Phase.RTP;
        ACTIVE = false;
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    // ------------------------------------------------------------------
    // Engine wiring
    // ------------------------------------------------------------------

    /** Builds the per-attempt settings the engine asks for: rotating region + center-stop gate + timeouts. */
    private RtpAttemptSettings attemptSettings() {
        RtpAttemptSettings d = RtpAttemptSettings.defaults();
        return new RtpAttemptSettings(
            java.time.Duration.ofSeconds(Math.max(1, rtpCooldown.get())).toNanos(), // cooldownNanos
            d.teleportThresholdBlocks(),
            java.time.Duration.ofSeconds(Math.max(1, stuckTimeout.get())).toNanos(), // teleportTimeoutNanos (replaces checkStuckRtp)
            d.minimumStabilizationTicks(),
            d.requiredStableTicks(),
            d.maximumStabilizationNanos(),
            d.stabilityToleranceBlocks(),
            d.storeYCoordinate(),
            nextRegion(),                                                            // requestedRegion
            mode.get() == Mode.SEARCH,                                               // stopNearCenter
            threshold.get(),                                                         // centerStopRadiusBlocks
            false,                                                                   // stopNearWorldBorder (world border is 300000)
            d.worldBorderMarginBlocks()
        );
    }

    /** The region to RTP to next: either the chosen specific region, or round-robin across the allowed set. */
    private RtpRegion nextRegion() {
        if (regionMode.get() == RegionMode.SPECIFIC) {
            return specificRegion.get().region();
        }
        List<RtpRegion> selected = new ArrayList<>();
        for (RtpRegion r : RtpRegion.selectableValues()) {
            if (!allowEast.get() && r == RtpRegion.NA_EAST) continue;
            selected.add(r);
        }
        if (selected.isEmpty()) selected.add(RtpRegion.NA_WEST);
        return regionCycle.next(selected);
    }

    /** RtpCommandSender: fire the region-scoped /rtp command at the server. */
    private void sendRtpCommand(long requestNumber, RtpRegion region) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return;
        // Stop any in-progress wander so pathing never overrides the RTP.
        wandering = false;
        stopBaritone();
        String base = rtpCommand.get().trim();
        if (base.isEmpty()) base = "/rtp";
        // Always append the resolved region's argument (rotation or specific).
        String cmd = base + " " + region.commandArgument();
        if (cmd.startsWith("/")) mc.getConnection().sendCommand(cmd.substring(1));
        else mc.getConnection().sendChat(cmd);
    }

    /**
     * RtpSampleSink: a teleport was confirmed and stabilized. This replaces the old
     * RTP_WAIT -> DIGGING/SEARCHING transition. Run base detection / Baritone search here.
     */
    private void onTeleportConfirmed(RtpSampleResult result) {
        Minecraft mc = Minecraft.getInstance();
        loggedThisLanding = false;
        if (mc.player == null || mc.level == null) return;

        // Coord filter (Water RTPMiner): skip landings too close to spawn axes - far coords are
        // older, less-picked regions. The engine just RTPs again.
        if (minCoord.get() > 0) {
            double absCoord = Math.max(Math.abs(mc.player.getX()), Math.abs(mc.player.getZ()));
            if (absCoord < minCoord.get() * 1000.0) return;
        }

        // SAVE_AND_RTP never lingers: log if a base is here, then let the engine RTP again.
        if (mode.get() == Mode.SAVE_AND_RTP) {
            if (distFromSpawn(mc) < threshold.get()) {
                BlockPos found = scanForBase(mc);
                if (found != null) {
                    logBase(mc, found);
                    if (autoDisableOnFind.get()) { setEnabled(false); return; }
                }
            }
            return;
        }

        // SEARCH mode: only search when we landed within the center-stop radius.
        if (distFromSpawn(mc) >= threshold.get()) return;

        // Begin the search window: descend to the dig depth, then wander underground scanning.
        phase = Phase.SEARCHING;
        searchEndMs = System.currentTimeMillis() + searchDuration.get() * 1000L;
        wanderCenterX = mc.player.getX();
        wanderCenterZ = mc.player.getZ();
        wandering = false;
        reachedDepth = false;
        if (scanMode.get() == ScanMode.WATER_MINE) {
            waterState = WaterMineState.DESCEND;
            net.minecraft.core.Direction[] horizontals = {
                net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                net.minecraft.core.Direction.WEST, net.minecraft.core.Direction.EAST };
            waterMineDir = horizontals[WANDER_RNG.nextInt(horizontals.length)];
            waterPlaceAttempts = 0;
            AutismClientMessaging.sendPrefixed("§7Water-mining down to Y=" + waterTargetY.get() + ", then tunneling " + waterMineDir.getName() + "... (raw movement - watch for flags)");
        } else if (scanMode.get() == ScanMode.BARITONE_MINE && AutismCompatManager.isBaritoneAvailable()) {
            applyBaritoneSettings();
            // Dig down to the deepslate level first; tickWander handles the descent until reachedDepth.
            AutismCompatManager.startBaritoneGoTo(mc, (int) wanderCenterX, digDepth.get(), (int) wanderCenterZ);
            AutismClientMessaging.sendPrefixed("§7Digging down to Y=" + digDepth.get() + ", then wandering underground (r=" + wanderRadius.get() + ")...");
        } else {
            AutismClientMessaging.sendPrefixed("§7Searching around " + (int) wanderCenterX + ", " + (int) wanderCenterZ + "...");
        }
    }

    // ---- WATER_MINE: raw Water-client RTPMiner movement (look-down dig + straight key-input tunnel) ----

    private enum WaterMineState { DESCEND, MINE, Y_RECOVERY }

    private WaterMineState waterState = WaterMineState.DESCEND;
    private net.minecraft.core.Direction waterMineDir = net.minecraft.core.Direction.NORTH;
    private int waterPlaceAttempts = 0;

    private void tickWaterMine(Minecraft mc) {
        if (mc.player == null || mc.level == null || mc.options == null) return;

        // Fell below the floor: recover before anything else (Water Y_RECOVERY).
        if (mc.player.getY() < -62 && waterState != WaterMineState.Y_RECOVERY) {
            waterState = WaterMineState.Y_RECOVERY;
            waterPlaceAttempts = 0;
        }

        switch (waterState) {
            case DESCEND -> waterDescend(mc);
            case MINE -> waterMine(mc);
            case Y_RECOVERY -> waterRecover(mc);
        }
    }

    /** Look 85° down + hold attack; gravity does the descending (Water handleDescend). */
    private void waterDescend(Minecraft mc) {
        double target = waterTargetY.get();
        if (mc.player.getY() <= target + 1.5) {
            waterReleaseKeys(mc);
            waterState = WaterMineState.MINE;
            return;
        }
        mc.player.setXRot(85f);
        mc.options.keyAttack.setDown(true);
        mc.options.keyUp.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    /** Straight-line key-input tunnel at the target Y (Water handleAmethystMine). */
    private void waterMine(Minecraft mc) {
        double y = mc.player.getY();
        double target = waterTargetY.get();
        if (y < target - 2) { waterState = WaterMineState.Y_RECOVERY; return; }
        if (y > target + 2.5) { waterState = WaterMineState.DESCEND; return; }

        // Gravel overhead: stop and chew it before it suffocates us (Water gravel check).
        BlockPos above = mc.player.blockPosition().above();
        if (mc.level.getBlockState(above).getBlock() instanceof net.minecraft.world.level.block.FallingBlock
            || mc.level.getBlockState(above.above()).getBlock() instanceof net.minecraft.world.level.block.FallingBlock) {
            mc.player.setXRot(-80f);
            mc.options.keyAttack.setDown(true);
            mc.options.keyUp.setDown(false);
            return;
        }

        // Face the tunnel direction (raw snap within 5° like Water), then mine + walk.
        float targetYaw = switch (waterMineDir) {
            case NORTH -> 180f; case SOUTH -> 0f; case WEST -> 90f; case EAST -> 270f; default -> 0f;
        };
        if (Math.abs(net.minecraft.util.Mth.wrapDegrees(mc.player.getYRot() - targetYaw)) > 5f) {
            mc.player.setYRot(targetYaw);
            mc.player.setXRot(0f);
            return;
        }
        mc.player.setXRot(0f);
        mc.options.keyAttack.setDown(true);
        mc.options.keyUp.setDown(true);
        mc.options.keyShift.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    /** Place obsidian below + jump until back at the target Y (Water handleYRecovery). */
    private void waterRecover(Minecraft mc) {
        double target = waterTargetY.get();
        if (mc.player.getY() >= target - 1) {
            waterState = WaterMineState.MINE;
            waterPlaceAttempts = 0;
            waterReleaseKeys(mc);
            return;
        }
        waterReleaseKeys(mc);

        int oSlot = waterObsidianSlot.get() - 1;
        var obs = mc.player.getInventory().getItem(oSlot);
        if (obs.is(net.minecraft.world.item.Items.OBSIDIAN) && mc.gameMode != null) {
            com.autism.seedcracker.util.InvSync.select(mc, oSlot);
            mc.player.setXRot(89f);
            BlockPos below = mc.player.blockPosition().below();
            BlockState bs = mc.level.getBlockState(below);
            if (bs.isAir() || !bs.getFluidState().isEmpty()) {
                mc.gameMode.useItemOn(mc.player, net.minecraft.world.InteractionHand.MAIN_HAND,
                    new net.minecraft.world.phys.BlockHitResult(
                        net.minecraft.world.phys.Vec3.atCenterOf(below),
                        net.minecraft.core.Direction.UP, below, false));
            }
            mc.options.keyJump.setDown(true);
        } else {
            mc.options.keyJump.setDown(true);
        }

        // Gave up recovering: back to the engine for a fresh RTP (Water resetSession).
        if (++waterPlaceAttempts > 80) {
            waterPlaceAttempts = 0;
            waterReleaseKeys(mc);
            phase = Phase.RTP;
        }
    }

    private void waterReleaseKeys(Minecraft mc) {
        mc.options.keyAttack.setDown(false);
        mc.options.keyUp.setDown(false);
        mc.options.keyShift.setDown(false);
        mc.options.keyJump.setDown(false);
    }

    /** Builds the engine's per-tick environment observation from Minecraft, or null when not ready. */
    private RtpEnvironmentSnapshot buildSnapshot(Minecraft mc) {
        if (mc.getConnection() == null || mc.player == null || mc.level == null) {
            return RtpEnvironmentSnapshot.disconnected();
        }
        PositionObservation pos = new PositionObservation(
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            mc.level.dimension().identifier().toString()
        );
        return RtpEnvironmentSnapshot.ready(mc.getConnection(), pos);
    }

    private static int distFromSpawn(Minecraft mc) {
        return Math.max(Math.abs((int) mc.player.getX()), Math.abs((int) mc.player.getZ()));
    }

    private void stopBaritone() {
        try {
            if (AutismCompatManager.isBaritoneAvailable()) AutismCompatManager.stopBaritone(Minecraft.getInstance());
        } catch (Throwable ignored) {}
    }

    /** Descend to the dig depth, then roam random points underground so Baritone mines/covers ground. */
    private void tickWander(Minecraft mc) {
        if (!AutismCompatManager.isBaritoneAvailable()) return;

        // Phase 1: get underground first (auto-mine down to the dig depth).
        if (!reachedDepth) {
            if ((int) mc.player.getY() <= digDepth.get()) {
                reachedDepth = true;
                applyBaritoneSettings(); // re-enable sprint now that we're at depth
            } else if (!AutismCompatManager.isBaritoneBusy()) {
                AutismCompatManager.startBaritoneGoTo(mc, (int) wanderCenterX, digDepth.get(), (int) wanderCenterZ);
            }
            return;
        }

        // Phase 2: wander underground at the dig depth within the bubble.
        double dx = mc.player.getX() - wanderCenterX;
        double dz = mc.player.getZ() - wanderCenterZ;
        double maxR = wanderRadius.get();
        if (!AutismCompatManager.isBaritoneBusy()) {
            double tx, tz;
            if (dx * dx + dz * dz > maxR * maxR) {
                tx = wanderCenterX;
                tz = wanderCenterZ;
            } else {
                // Random target within the bubble (not straight - uniform in a disc).
                double ang = WANDER_RNG.nextDouble() * Math.PI * 2.0;
                double rad = Math.sqrt(WANDER_RNG.nextDouble()) * maxR;
                tx = wanderCenterX + Math.cos(ang) * rad;
                tz = wanderCenterZ + Math.sin(ang) * rad;
            }
            // Stay underground at the dig depth (clamped to the detection Y range).
            int ty = Math.max(minY.get(), Math.min(maxY.get(), digDepth.get()));
            wandering = AutismCompatManager.startBaritoneGoTo(mc, (int) tx, ty, (int) tz);
        }
    }

    private List<String> targetBlockIds() {
        List<String> out = new ArrayList<>();
        for (String s : list("target-blocks")) {
            String id = s.trim().toLowerCase(Locale.ROOT);
            if (!id.isEmpty()) out.add(id);
        }
        return out;
    }

    /** Scans loaded chunks around the player for any target block; returns its position or null. */
    // Dedupe: bases already logged this session (block position keys), so we don't re-log every tick.
    private final Set<Long> loggedBases = new HashSet<>();
    private int ticksUntilScan = 0;

    /** True if pos lies within the structure exclusion radius of a SeedCracker-detected structure. */
    private boolean insideStructure(BlockPos pos) {
        if (!skipStructures.get()) return false;
        try {
            kaptainwutax.seedcrackerX.SeedCracker sc = kaptainwutax.seedcrackerX.SeedCracker.get();
            if (sc == null) return false;
            kaptainwutax.seedcrackerX.finder.FinderQueue fq = kaptainwutax.seedcrackerX.finder.FinderQueue.get();
            if (fq == null) return false;
            double rSq = (double) structureExclusionRadius.get() * structureExclusionRadius.get();
            for (kaptainwutax.seedcrackerX.finder.Finder finder : fq.finderControl.getActiveFinders()) {
                for (BlockPos sp : safeFindPositions(finder)) {
                    if (sp.distSqr(pos) <= rSq) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static List<BlockPos> safeFindPositions(kaptainwutax.seedcrackerX.finder.Finder finder) {
        try {
            List<BlockPos> out = finder.findInChunk();
            return out == null ? List.of() : out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Collects target-block positions near the player, height-gated and structure-filtered. */
    private List<BlockPos> collectStashBlocks(Minecraft mc) {
        List<BlockPos> found = new ArrayList<>();
        if (mc.level == null || mc.player == null) return found;
        List<String> targets = targetBlockIds();
        if (targets.isEmpty()) return found;
        int r = scanRange.get();
        int loY = minY.get();
        int hiY = maxY.get();
        int px = (int) mc.player.getX();
        int pz = (int) mc.player.getZ();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int x = px - r; x <= px + r; x++) {
            for (int z = pz - r; z <= pz + r; z++) {
                if (!mc.level.hasChunk(x >> 4, z >> 4)) continue;
                for (int y = loY; y <= hiY; y++) {
                    pos.set(x, y, z);
                    BlockState st = mc.level.getBlockState(pos);
                    if (st.isAir()) continue;
                    String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
                    if (!targets.contains(id)) continue;
                    if (insideStructure(pos)) continue;
                    found.add(pos.immutable());
                }
            }
        }
        return found;
    }

    /** Returns the centre of a cluster of >= minCluster stash blocks, or null. A base needs a cluster, not a lone block. */
    private BlockPos scanForBase(Minecraft mc) {
        List<BlockPos> blocks = collectStashBlocks(mc);
        if (blocks.isEmpty()) return null;
        int need = Math.max(1, minCluster.get());
        double clusterRangeSq = 8.0 * 8.0; // blocks within 8 blocks of each other count as one cluster
        for (BlockPos candidate : blocks) {
            if (loggedBases.contains(candidate.asLong())) continue;
            int near = 0;
            for (BlockPos other : blocks) {
                if (candidate.distSqr(other) <= clusterRangeSq) near++;
            }
            if (near >= need) return candidate;
        }
        return null;
    }

    /** Throttles detection scans to every N ticks (keeps the render thread responsive). */
    private boolean shouldScanNow() {
        if (ticksUntilScan > 0) { ticksUntilScan--; return false; }
        ticksUntilScan = Math.max(1, scanInterval.get());
        return true;
    }

    /** Pushes all configured Baritone settings via #set commands. */
    private void applyBaritoneSettings() {
        if (!AutismCompatManager.isBaritoneAvailable()) return;
        Minecraft mc = Minecraft.getInstance();
        // Core
        set(mc, "allowBreak", allowBreak.get());
        set(mc, "allowPlace", allowPlace.get());
        // Grim legitimacy: no sprint during the dig-down phase (sprinting while digging looks
        // bot-like); only allow sprint once we've reached the wander depth.
        set(mc, "allowSprint", allowSprint.get() && reachedDepth);
        set(mc, "allowEat", autoEat.get());
        set(mc, "autoTool", autoTool.get());
        set(mc, "allowInventory", allowInventory.get());
        // Movement
        set(mc, "allowParkour", allowParkour.get());
        set(mc, "allowParkourPlace", allowParkourPlace.get());
        set(mc, "allowDiagonalAscend", allowDiagonalAscend.get());
        set(mc, "allowDiagonalDescend", allowDiagonalDescend.get());
        set(mc, "allowDownward", allowDownward.get());
        set(mc, "allowVines", allowVines.get());
        set(mc, "assumeStep", assumeStep.get());
        set(mc, "sprintAscends", sprintAscends.get());
        set(mc, "freeLook", freeLook.get());
        set(mc, "antiCheatCompatibility", antiCheat.get());
        // Avoidance
        set(mc, "avoidance", avoidance.get());
        set(mc, "avoidUpdatingFallingBlocks", avoidUpdatingFalling.get());
        set(mc, "pauseMiningForFallingBlocks", pauseMiningForFalling.get());
        set(mc, "mobAvoidanceRadius", mobAvoidanceRadius.get());
        set(mc, "mobSpawnerAvoidanceRadius", spawnerAvoidanceRadius.get());
        // Falling
        set(mc, "maxFallHeightNoWater", maxFallNoWater.get());
        set(mc, "allowWaterBucketFall", allowWaterBucketFall.get());
        set(mc, "maxFallHeightBucket", maxFallBucket.get());
        // Render
        set(mc, "renderPath", renderPath.get());
        set(mc, "renderGoal", renderGoal.get());
        set(mc, "renderCachedChunks", renderCachedChunks.get());
        // Elytra
        set(mc, "elytraAutoJump", elytraAutoJump.get());
        set(mc, "elytraAutoSwap", elytraAutoSwap.get());
        set(mc, "elytraConserveFireworks", elytraConserveFireworks.get());
        set(mc, "elytraMinimumDurability", elytraMinDurability.get());
        // Legit mode: apply last so its human-looking values override the render/rotation settings above.
        if (legitMode.get()) {
            com.autism.seedcracker.util.LegitBaritone.apply(mc);
        }
    }

    private static void set(Minecraft mc, String name, boolean value) {
        AutismCompatManager.sendBaritoneCommand(mc, "#set " + name + " " + value);
    }

    private static void set(Minecraft mc, String name, int value) {
        AutismCompatManager.sendBaritoneCommand(mc, "#set " + name + " " + value);
    }

    private void logBase(Minecraft mc, BlockPos found) {
        loggedBases.add(found.asLong());
        String dim = mc.level != null ? mc.level.dimension().identifier().toString() : "unknown";
        String line = String.format(Locale.ROOT, "%d %d %d  %s  %s%n",
            found.getX(), found.getY(), found.getZ(), dim,
            new java.sql.Timestamp(System.currentTimeMillis()));
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, line, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            AutismClientMessaging.sendPrefixed("§aLogged base to bases.txt: " + found.getX() + " " + found.getY() + " " + found.getZ());
        } catch (IOException e) {
            AutismClientMessaging.sendPrefixed("§cFailed to write bases.txt: " + e.getMessage());
        }
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        if (controller == null) return;

        // Keep scanning + wandering while searching after a confirmed teleport (throttled).
        if (phase == Phase.SEARCHING) {
            if (shouldScanNow()) {
                BlockPos found = scanForBase(mc);
                if (found != null) {
                    logBase(mc, found);
                    if (autoDisableOnFind.get()) { setEnabled(false); return; }
                }
            }
            if (System.currentTimeMillis() >= searchEndMs) {
                stopBaritone();
                if (scanMode.get() == ScanMode.WATER_MINE) waterReleaseKeys(mc);
                wandering = false;
                phase = Phase.RTP;
                AutismClientMessaging.sendPrefixed("§7Search time up, RTPing again.");
            } else {
                // Wander within the bubble while searching (Baritone moves us to cover ground).
                if (scanMode.get() == ScanMode.BARITONE_MINE) tickWander(mc);
                else if (scanMode.get() == ScanMode.WATER_MINE) tickWaterMine(mc);
                // Still searching: park the engine so it doesn't RTP away mid-search.
                return;
            }
        }

        // While RTPing (not in a post-teleport search), keep detection running too
        // so SAVE_AND_RTP can log a base the moment we land within range.
        if (phase == Phase.RTP && mode.get() == Mode.SAVE_AND_RTP && !loggedThisLanding
                && distFromSpawn(mc) < threshold.get() && shouldScanNow()) {
            BlockPos found = scanForBase(mc);
            if (found != null) {
                loggedThisLanding = true;
                logBase(mc, found);
                if (autoDisableOnFind.get()) { setEnabled(false); return; }
            }
        }

        // Drive the engine. The controller handles send/cooldown/teleport-timeout/stabilization.
        RtpEnvironmentSnapshot env = buildSnapshot(mc);
        if (env != null) controller.tick(env);
    }

    @Override
    public String info() {
        if (phase == Phase.SEARCHING) return "searching";
        if (controller == null || !controller.isRunning()) return "idle";
        return switch (controller.state()) {
            case IDLE -> "idle";
            case WAITING_TO_SEND, COOLDOWN -> "cooldown";
            case WAITING_FOR_TELEPORT -> "rtp";
            case WAITING_FOR_STABILIZATION -> "stabilizing";
            case RECORDING -> "recording";
        };
    }
}
