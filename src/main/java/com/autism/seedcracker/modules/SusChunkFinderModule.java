package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.EnumSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import com.autism.seedcracker.compat.ClientNotify;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sus Chunk Finder.
 *
 * Modes (mostly ports of the strongest sus-chunk detectors from other clients):
 *  - DOGS: our own score-based detector (default) - independent underground signals (deep kelp,
 *    underground vines, rotated deepslate, flat mined rooms, storage blocks) must corroborate
 *    before a chunk flags, so no single natural quirk can false-flag.
 *  - XENON: fast below-Y15 player-placement detection (non-natural block deep down = placed).
 *  - TYPES: per-block-type detector (kelp, vines, amethyst, bamboo, bee nests, rotated deepslate).
 *  - NEW_CHUNKS: Boze NewChunks - a block-update packet carrying FLOWING (non-source) fluid marks
 *    a freshly GENERATED chunk (fluid ticks only run on generation). New chunks in a straight
 *    line = someone's active highway/tunnel frontier.
 *  - OLD_CHUNKS: inverse of NEW_CHUNKS - a full chunk arriving WITH flowing fluid already in it
 *    was loaded before (someone has been here; the flow was mid-tick when they left).
 *  - TUNNEL: Boze TunnelESP corridor classifier - flags chunks containing 2-high walkable air
 *    corridors with solid walls on the perpendicular axis (the signature of player tunnels).
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 */
public final class SusChunkFinderModule extends Module {

    public enum Mode { GEODE, DOGS, BETA, WATER, XENON, TYPES, NEW_CHUNKS, OLD_CHUNKS, TUNNEL, ACTIVITY, SIGNAL, FARM }

    private final EnumSetting<Mode> mode = add(new EnumSetting<>(
            "mode", "Mode", Mode.GEODE, Mode.values())
        .description("GEODE (default) = Anubis sus-chunk logic: underground light pockets + amethyst (geodes = caves = player traffic). DOGS = our score-based detector: independent underground signals (deep kelp, underground vines, rotated deepslate, flat mined rooms, storage) must corroborate before flagging. WATER = raw Water-client detector (noisier: worldgen kelp ages + natural caves false-flag). XENON = below-Y15 placement. TYPES = block types. NEW_CHUNKS = freshly generated (packet fluid-tick). OLD_CHUNKS = visited before. TUNNEL = 2x1 corridors. ACTIVITY = load/unload cycling (another player's render bubble). SIGNAL = loaded chunks beyond the server's render radius (someone else streams them). FARM = POWERED redstone components (a clock running right now = active farm; worldgen never places powered repeaters).")
        .group("General"));
    private final EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.HIGH, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("XENON: player-only blocks (shulker/hopper/furnace/crafting...) flag at 1 (LOW: 2). Structure-prone blocks (torch/chest/rail/spawner/obsidian) need HIGH 2 / MEDIUM 4 / LOW 6. TYPES: scales the per-type min counts.")
        .group("General"));

    // DOGS-mode settings: independent signals add points; corroboration required to flag.
    private final IntSetting dogsNeedScore = add(new IntSetting("dogs-need-score", "Score to flag", 3, 1, 10, 1)
        .description("Points needed to flag. Player-only storage below Y50 = 3 (instant). Underground vines / deep kelp / rotated deepslate / flat mined room = 2 each (any two flag). Lone chest/spawner = 1 and is structure-vetoed.")
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS));
    private final BoolSetting dogsStorage = add(new BoolSetting("dogs-storage", "Storage blocks", true)
        .description("Shulker/hopper/barrel/furnace/enchanting table... below Y50 flags instantly. A plain chest/spawner only adds 1 point and is skipped inside natural structures.")
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS));
    private final BoolSetting dogsVines = add(new BoolSetting("dogs-vines", "Underground vines", true)
        .description("A vertical vine run below Y45: vines never generate down there, players drop them into shafts. (Surface jungle vines are ignored, unlike WATER mode.)")
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS));
    private final IntSetting dogsVineLen = add(new IntSetting("dogs-vine-length", "Vine run", 8, 3, 64, 1)
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS && dogsVines.get()));
    private final BoolSetting dogsKelp = add(new BoolSetting("dogs-kelp", "Deep kelp", true)
        .description("Any kelp below Y24: oceans never reach that deep, so it's a player water tunnel or farm. (Replaces WATER's kelp-age check - worldgen kelp spawns with random high ages, the #1 false flag.)")
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS));
    private final BoolSetting dogsDeepslate = add(new BoolSetting("dogs-deepslate", "Rotated deepslate", true)
        .description("Sideways-axis deepslate, skipped when the chunk matches an ancient-city/stronghold signature (structure templates place rotated blocks too - another WATER false flag).")
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS));
    private final IntSetting dogsDeepslateCount = add(new IntSetting("dogs-deepslate-count", "Rotated count", 3, 1, 50, 1)
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS && dogsDeepslate.get()));
    private final BoolSetting dogsRoom = add(new BoolSetting("dogs-room", "Flat mined rooms", true)
        .description("Many walkable cells on ONE flat Y level below Y20: players mine flat floors, natural caves don't. (Replaces WATER's big-cave flood fill, which flagged every 1.18 cave system.)")
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS));
    private final IntSetting dogsRoomSize = add(new IntSetting("dogs-room-size", "Room floor cells", 30, 10, 128, 2)
        .group("Dogs").visibleWhen(() -> mode.get() == Mode.DOGS && dogsRoom.get()));

    // WATER-mode channels (Water client SusChunkFinder port: each detector its own toggle+threshold).
    private final BoolSetting waterVines = add(new BoolSetting("water-vines", "Long vines", true)
        .description("Flag a vine column this long: natural vines rarely exceed ~25 - 49+ means grown against a player build.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterVineLength = add(new IntSetting("water-vine-length", "Vine length", 49, 10, 120, 1)
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterVines.get()));
    private final BoolSetting waterKelp = add(new BoolSetting("water-kelp", "Max-age kelp", true)
        .description("Flag kelp whose AGE blockstate is near max: naturally generated kelp almost never reaches high ages - a fully-aged plant grew in real time near a player.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterKelpAge = add(new IntSetting("water-kelp-age", "Kelp min age", 13, 1, 25, 1)
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterKelp.get()));
    private final BoolSetting waterDeepslate = add(new BoolSetting("water-deepslate", "Rotated deepslate", true)
        .description("Flag sideways-axis deepslate (players place it rotated; worldgen is always upright).")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterDeepslateCount = add(new IntSetting("water-deepslate-count", "Rotated count", 3, 1, 50, 1)
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterDeepslate.get()));
    private final BoolSetting waterCaves = add(new BoolSetting("water-caves", "Big deep caves", true)
        .description("Flood-fill air pockets between Y-60..20: an unusually big connected cavity is often a hollowed-out base.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER));
    private final IntSetting waterCavePoints = add(new IntSetting("water-cave-points", "Cave points", 27, 5, 100, 1)
        .description("1 point = 25 connected air blocks.")
        .group("Water").visibleWhen(() -> mode.get() == Mode.WATER && waterCaves.get()));

    // ACTIVITY-mode settings (Water ActivityDebug port: load/unload cycling = someone's render bubble).
    private final IntSetting activityCycles = add(new IntSetting("activity-cycles", "Min cycles", 4, 1, 30, 1)
        .description("Complete load+unload cycles inside the window needed to flag.")
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));
    private final IntSetting activityUnloads = add(new IntSetting("activity-unloads", "Min unloads", 2, 0, 10, 1)
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));
    private final IntSetting activityWindow = add(new IntSetting("activity-window", "Window (s)", 120, 10, 600, 10)
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));
    private final IntSetting activityFade = add(new IntSetting("activity-fade", "Fade after (min)", 10, 1, 60, 1)
        .description("Flagged activity chunks un-flag after this long without new cycles.")
        .group("Activity").visibleWhen(() -> mode.get() == Mode.ACTIVITY));

    // SIGNAL-mode settings (Water SignalScanner port: chunks loaded beyond the server's own radius).
    private final IntSetting signalServerRadius = add(new IntSetting("signal-server-radius", "Server render radius", 8, 1, 32, 1)
        .description("The server's chunk-send radius around YOU. Loaded chunks farther than this are being streamed for someone else.")
        .group("Signal").visibleWhen(() -> mode.get() == Mode.SIGNAL));
    private final BoolSetting signalNeedBlocks = add(new BoolSetting("signal-need-blocks", "Require signal blocks", true)
        .description("Only flag out-of-radius chunks that ALSO contain storage/utility blocks (chest/furnace/hopper/spawner/anvil...).")
        .group("Signal").visibleWhen(() -> mode.get() == Mode.SIGNAL));

    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 6, 1, 16, 1)
        .description("Chunk bubble around the player scanned.")
        .group("General"));
    private final IntSetting rescanMs = add(new IntSetting(
            "rescan-ms", "Rescan (ms)", 1000, 250, 10000, 250)
        .description("How often each chunk is re-scanned (XENON). DOGS/WATER re-scan at 10x this (their signals barely change; re-scanning every second was an FPS sink).")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0x50FF5050)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a suspicious chunk is found.")
        .group("General"));
    private final BoolSetting skipStructures = add(new BoolSetting(
            "skip-structures", "Skip natural structures", true)
        .description("XENON: don't flag spawners/blocks inside dungeons or trial chambers (they aren't player bases).")
        .group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 2, 1, 32, 1)
        .description("Chunks scanned per tick in every scan mode (DOGS/WATER/XENON/TYPES; SIGNAL gets 4x since its check is cheap). 1 = smoothest FPS, higher = faster full sweep.")
        .group("General"));

    // TYPES-mode per-type toggles + per-type min-count sliders.
    private final BoolSetting kelp = add(new BoolSetting("kelp", "Kelp", true).group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting kelpCount = add(new IntSetting("kelp-count", "Kelp min count", 3, 1, 200, 1)
        .description("Kelp blocks in a chunk needed to flag (dense kelp = a farm).").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting caveVines = add(new BoolSetting("cave-vines", "Cave Vines", true).group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting caveVinesCount = add(new IntSetting("cave-vines-count", "Cave vines min count", 3, 1, 200, 1)
        .description("Cave-vine blocks in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting vines = add(new BoolSetting("vines", "Vines", true).group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting vinesCount = add(new IntSetting("vines-count", "Vines min count", 3, 1, 200, 1)
        .description("Vine blocks in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting amethystShards = add(new BoolSetting("amethyst-shards", "Amethyst Shards", true)
        .description("Amethyst clusters/buds - harvested by players, so a strong base indicator.")
        .group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting amethystShardsCount = add(new IntSetting("amethyst-shards-count", "Amethyst shards min count", 1, 1, 200, 1)
        .description("Amethyst clusters/buds in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting amethystBlocks = add(new BoolSetting("amethyst-blocks", "Amethyst Blocks", false)
        .description("Full amethyst/budding blocks (geode structure - not player-placed).")
        .group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting amethystBlocksCount = add(new IntSetting("amethyst-blocks-count", "Amethyst blocks min count", 4, 1, 200, 1)
        .description("Full amethyst/budding blocks in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting bamboo = add(new BoolSetting("bamboo", "Bamboo", true).group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting bambooCount = add(new IntSetting("bamboo-count", "Bamboo min count", 3, 1, 200, 1)
        .description("Bamboo blocks in a chunk needed to flag (dense bamboo = a farm).").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting beeNest = add(new BoolSetting("bee-nest", "Bee Nest", true).group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting beeNestCount = add(new IntSetting("bee-nest-count", "Bee nest min count", 1, 1, 200, 1)
        .description("Bee nests/hives in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting rotatedDeepslate = add(new BoolSetting("rotated-deepslate", "Rotated Deepslate", true).group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting rotatedDeepslateCount = add(new IntSetting("rotated-deepslate-count", "Rotated deepslate min count", 3, 1, 200, 1)
        .description("Rotated (non-Y-axis) deepslate blocks in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting skullCandle = add(new BoolSetting("skull-candle", "Skulls / Candles", true)
        .description("Mob skulls + candles (strong player-build / decoration markers, nyx signal).").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting skullCandleCount = add(new IntSetting("skull-candle-count", "Skull/candle min count", 1, 1, 200, 1)
        .description("Skull or candle blocks in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting cocoa = add(new BoolSetting("cocoa", "Cocoa", false)
        .description("Cocoa pods (a farm indicator, nyx signal).").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final IntSetting cocoaCount = add(new IntSetting("cocoa-count", "Cocoa min count", 3, 1, 200, 1)
        .description("Cocoa pods in a chunk needed to flag.").group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting villagerHall = add(new BoolSetting("villager-hall", "Villager hall (entities)", true)
        .description("Villager/zombie-villager/allay/vindicator/warden entities in a chunk - an active base or villager hall (nyx signal).")
        .group("Types").visibleWhen(() -> mode.get() == Mode.TYPES));
    private final BoolSetting persistFlags = add(new BoolSetting("persist-flags", "Persist flags to disk", true)
        .description("Save flagged chunks to disk keyed by dimension and reload them on join (survives relog, nyx behaviour).")
        .group("General"));

    // GEODE-mode settings (Anubis sus-chunk port).
    private final BoolSetting glow = add(new BoolSetting("geode-glow", "Glow detection", true)
        .description("Also flag underground light pockets (amethyst glow through a cave), not just direct amethyst. Finds geodes even when the amethyst is out of detail range.")
        .group("Geode").visibleWhen(() -> mode.get() == Mode.GEODE));
    private final IntSetting geodeMinY = add(new IntSetting("geode-min-y", "Min Y", -58, -64, 64, 2)
        .description("Lowest Y scanned for the glow pocket.").group("Geode").visibleWhen(() -> mode.get() == Mode.GEODE));
    private final IntSetting geodeMaxY = add(new IntSetting("geode-max-y", "Max Y", 30, -64, 64, 2)
        .description("Highest Y scanned for the glow pocket (geodes form in the deepslate band).").group("Geode").visibleWhen(() -> mode.get() == Mode.GEODE));
    private final IntSetting geodeGlowLight = add(new IntSetting("geode-glow-light", "Glow light level", 5, 1, 15, 1)
        .description("Block-light level a cell must reach to count as a glow pocket (amethyst cluster emits 5).").group("Geode").visibleWhen(() -> mode.get() == Mode.GEODE));
    private final IntSetting geodeSpread = add(new IntSetting("geode-spread", "Spread radius", 1, 0, 3, 1)
        .description("Anubis spreadRadius: how many chunks away a hot chunk's heat corroborates its neighbours. Nearby geode chunks reinforce each other into one confirmed find.")
        .group("Geode").visibleWhen(() -> mode.get() == Mode.GEODE));
    private final IntSetting geodeHeatNeed = add(new IntSetting("geode-heat", "Heat to flag", 4, 1, 30, 1)
        .description("Anubis sensitivity: accumulated heat a chunk needs (after neighbour spread) before it flags. Higher = fewer, more-confirmed finds.")
        .group("Geode").visibleWhen(() -> mode.get() == Mode.GEODE));

    // FARM-mode settings (Water TuffChunkV2 port: POWERED redstone = a clock running right now).
    private final IntSetting farmRepeaters = add(new IntSetting("farm-repeaters", "Powered repeaters", 3, 1, 20, 1)
        .description("Powered (lit) repeaters in one chunk needed to flag. Worldgen never places powered repeaters, so 3+ means a player clock is cycling THIS moment - an active farm.")
        .group("Farm").visibleWhen(() -> mode.get() == Mode.FARM));
    private final BoolSetting farmComparators = add(new BoolSetting("farm-comparators", "Count comparators", true)
        .description("Also count powered comparators (item-counting farm clocks use them instead of repeaters).")
        .group("Farm").visibleWhen(() -> mode.get() == Mode.FARM));
    private final BoolSetting farmObservers = add(new BoolSetting("farm-observers", "Count observers", false)
        .description("Also count powered observers (noisier: flowing water/crop growth can fire them naturally).")
        .group("Farm").visibleWhen(() -> mode.get() == Mode.FARM));

    // BETA mode: fuses the strongest detector from every other mode and only flags when several
    // independent signals agree (merged in from the former "Sus Chunk Finder (Beta)" module).
    private final IntSetting betaThreshold = add(new IntSetting("beta-threshold", "Score to flag", 45, 10, 100, 5)
        .description("0-100 fused score needed. Storage 45, running redstone 35, deepslate/vines/kelp 25, room/built 20, glow/skulls 15.")
        .group("Beta").visibleWhen(() -> mode.get() == Mode.BETA));
    private final IntSetting betaMinSignals = add(new IntSetting("beta-min-signals", "Agreeing signals", 2, 1, 5, 1)
        .description("Independent signals that must agree. 2 = recommended; 1 behaves like the old single-mode finders (noisy).")
        .group("Beta").visibleWhen(() -> mode.get() == Mode.BETA));
    private final IntSetting betaRescanSeconds = add(new IntSetting("beta-rescan", "Rescan (s)", 20, 2, 300, 1)
        .description("How often a chunk is re-scored (redstone and flats change, storage rarely does).")
        .group("Beta").visibleWhen(() -> mode.get() == Mode.BETA));
    private final BoolSetting betaSpread = add(new BoolSetting("beta-spread", "Neighbour heat", true)
        .description("Chunks next to a flagged chunk get up to +15% (bases sprawl across borders). Never flags a chunk with no evidence.")
        .group("Beta").visibleWhen(() -> mode.get() == Mode.BETA));
    private final IntSetting betaMaxY = add(new IntSetting("beta-max-y", "Underground below Y", 45, -40, 120, 5)
        .description("Vines/kelp/rooms only count below this (surface jungles and oceans are natural).")
        .group("Beta").visibleWhen(() -> mode.get() == Mode.BETA));
    private final BoolSetting betaGlow = add(new BoolSetting("beta-glow", "Amethyst glow", true)
        .description("Amethyst growth in the chunk (geodes = caves = traffic). Weak on its own by design.")
        .group("Beta").visibleWhen(() -> mode.get() == Mode.BETA));
    private final BoolSetting betaRedstone = add(new BoolSetting("beta-redstone", "Running redstone", true)
        .description("Powered repeaters/comparators: a clock is cycling right now.")
        .group("Beta").visibleWhen(() -> mode.get() == Mode.BETA));

    private final Set<ChunkPos> flagged = ConcurrentHashMap.newKeySet();
    private final com.autism.seedcracker.finder.FinderReport reporter =
        new com.autism.seedcracker.finder.FinderReport("Sus", 55);
    private final Set<ChunkPos> notified = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Long> lastScan = new ConcurrentHashMap<>();
    /** Round-robin scan offset: the fixed-corner loops started at -radius every tick, so with a
     * small chunks-per-tick budget the near-corner chunks hogged every scan and far chunks never
     * got one (looked like the module "stopped registering" until toggled). Advances each call. */
    private int scanOffset = 0;

    // BETA mode state (round-robin fused scanner).
    private final Map<ChunkPos, com.autism.seedcracker.util.pure.SusScore.Result> betaResults = new java.util.HashMap<>();
    private final Map<ChunkPos, Long> betaScannedAt = new java.util.HashMap<>();
    private java.util.List<LevelChunk> betaQueue = java.util.List.of();
    private int betaQueueIndex;
    private int betaPruneTicks;

    public SusChunkFinderModule() {
        super(SeedcrackerAddon.ID + ":z-sus-chunk-finder", "Sus Chunk Finder",
            "Flags suspicious chunks. Pick a Mode: GEODE/DOGS (recommended), BETA (fuses every detector, fewest false flags), or the raw single-signal modes.");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        lastScan.clear();
        newChunks.clear();
        oldChunks.clear();
        geodeHeat.clear();
        geodeSelf.clear();
        geodeVeto.clear();
        geodeScanned.clear();
        scanCursorAge.reset();
        scanCursorTunnel.reset();
        resetBeta();
        if (persistFlags.get()) loadFlags();
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        // Mode/sensitivity swap: drop old-mode flags immediately so results don't mix. The
        // NEW/OLD chunk-age intel survives (it's packet history - a rescan can't rebuild it).
        if ("mode".equals(settingId) || "sensitivity".equals(settingId)) {
            flagged.clear();
            notified.clear();
            lastScan.clear();
            activityFlaggedAt.clear();
            geodeHeat.clear();
            geodeSelf.clear();
            geodeVeto.clear();
            geodeScanned.clear();
            scanCursorAge.reset();
            scanCursorTunnel.reset();
            resetBeta();
            ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-sus-chunk-finder");
        }
    }

    @Override
    public void onDisable() {
        if (persistFlags.get()) saveFlags();
        flagged.clear();
        notified.clear();
        lastScan.clear();
        activity.clear();
        activityFlaggedAt.clear();
        geodeHeat.clear();
        geodeSelf.clear();
        geodeVeto.clear();
        geodeScanned.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-sus-chunk-finder");
        resetBeta();
    }

    // ---- disk persistence (nyx ChunkActivityScanner behaviour) ----
    private java.nio.file.Path flagsFile() {
        Minecraft mc = Minecraft.getInstance();
        // dimension().toString() is "ResourceKey[... / ...]" - brackets, spaces, and a slash that
        // nests a junk directory. Use the identifier itself.
        String dim = mc.level != null
            ? mc.level.dimension().identifier().toString().replace(':', '_')
            : "unknown";
        return autismclient.AutismClientAddon.FOLDER.toPath()
            .resolve("sus-chunk-flags-" + dim + ".txt");
    }

    private void loadFlags() {
        try {
            java.nio.file.Path f = flagsFile();
            if (!java.nio.file.Files.exists(f)) return;
            for (String line : java.nio.file.Files.readAllLines(f)) {
                String[] p = line.trim().split(",");
                if (p.length != 2) continue;
                flagged.add(new ChunkPos(Integer.parseInt(p[0]), Integer.parseInt(p[1])));
            }
        } catch (Throwable ignored) {}
    }

    private void saveFlags() {
        try {
            java.nio.file.Path f = flagsFile();
            java.nio.file.Files.createDirectories(f.getParent());
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (ChunkPos p : flagged) lines.add(p.x() + "," + p.z());
            java.nio.file.Files.write(f, lines);
        } catch (Throwable ignored) {}
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
        // Packet-history intel is per-server: chunk coords from the last server are phantom
        // flags on the next one, and the sets otherwise grow unbounded all session.
        newChunks.clear();
        oldChunks.clear();
        activity.clear();
        activityFlaggedAt.clear();
        flagged.clear();
        notified.clear();
        resetBeta();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        switch (mode.get()) {
            case BETA -> tickBeta(mc);
            case DOGS -> tickDogs(mc);
            case WATER -> tickWater(mc);
            case XENON -> tickXenon(mc);
            case TYPES -> tickTypes(mc);
            case NEW_CHUNKS, OLD_CHUNKS -> tickChunkAge(mc);
            case TUNNEL -> tickTunnelScan(mc);
            case ACTIVITY -> tickActivity(mc);
            case SIGNAL -> tickSignal(mc);
            case GEODE -> tickGeode(mc);
            case FARM -> tickFarm(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-sus-chunk-finder", flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    // ---- BETA mode (fused multi-signal scanner, merged from the old Sus Chunk Finder (Beta)) ----

    private void tickBeta(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (betaQueueIndex >= betaQueue.size()) {
            betaQueue = com.autism.seedcracker.finder.ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
            betaQueueIndex = 0;
        }
        long rescanMs = betaRescanSeconds.get() * 1000L;
        int budget = chunksPerTick.get();
        while (budget > 0 && betaQueueIndex < betaQueue.size()) {
            LevelChunk chunk = betaQueue.get(betaQueueIndex++);
            ChunkPos pos = chunk.getPos();
            Long last = betaScannedAt.get(pos);
            if (last != null && now - last < rescanMs) continue;
            betaScannedAt.put(pos, now);
            budget--;
            evaluateBeta(chunk);
        }
        if (++betaPruneTicks >= 40) {
            betaPruneTicks = 0;
            ChunkPos c = mc.player.chunkPosition();
            int r = scanRadius.get() + 2;
            java.util.function.Predicate<ChunkPos> far = p -> Math.abs(p.x() - c.x()) > r || Math.abs(p.z() - c.z()) > r;
            flagged.removeIf(far);
            notified.removeIf(far);
            betaResults.keySet().removeIf(far);
            betaScannedAt.keySet().removeIf(far);
        }
    }

    private void evaluateBeta(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int yCut = betaMaxY.get();
        java.util.Map<com.autism.seedcracker.util.pure.SusScore.Signal, Double> s =
            new java.util.EnumMap<>(com.autism.seedcracker.util.pure.SusScore.Signal.class);

        int storage = 0;
        for (net.minecraft.world.level.block.entity.BlockEntity be : chunk.getBlockEntities().values()) {
            if (be.getBlockPos().getY() <= 50 && isBetaPlayerStorage(be.getBlockState().getBlock())) storage++;
        }
        s.put(com.autism.seedcracker.util.pure.SusScore.Signal.STORAGE, com.autism.seedcracker.util.pure.SusScore.ramp(storage, 1));
        s.put(com.autism.seedcracker.util.pure.SusScore.Signal.ROTATED_DEEPSLATE, com.autism.seedcracker.util.pure.SusScore.ramp(
            com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, SusChunkFinderModule::isRotatedDeepslate, 6), 3));
        s.put(com.autism.seedcracker.util.pure.SusScore.Signal.DEEP_VINES, vineRunHit(chunk, 8, yCut, chunk.getMinY()) ? 1.0 : 0);
        s.put(com.autism.seedcracker.util.pure.SusScore.Signal.DEEP_KELP, kelpBelowY(chunk, Math.min(yCut, 24)) ? 1.0 : 0);
        s.put(com.autism.seedcracker.util.pure.SusScore.Signal.FLAT_ROOM, flatRoomHit(chunk, 30) ? 1.0 : 0);
        s.put(com.autism.seedcracker.util.pure.SusScore.Signal.SKULL_CANDLE, com.autism.seedcracker.util.pure.SusScore.ramp(
            com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, SusChunkFinderModule::isSkullOrCandle, 6), 3));
        if (betaRedstone.get()) {
            s.put(com.autism.seedcracker.util.pure.SusScore.Signal.POWERED_REDSTONE, com.autism.seedcracker.util.pure.SusScore.ramp(
                com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, SusChunkFinderModule::isBetaPoweredClock, 6), 3));
        }
        if (betaGlow.get()) {
            s.put(com.autism.seedcracker.util.pure.SusScore.Signal.GLOW, com.autism.seedcracker.util.pure.SusScore.ramp(
                com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, SusChunkFinderModule::isAmethystGrowth, 24), 12));
        }
        // BaseConfidence walks every block; only pay for it when something else already fired.
        if (s.values().stream().anyMatch(v -> v > 0)) {
            com.autism.seedcracker.finder.BaseConfidence.Result bc = com.autism.seedcracker.finder.BaseConfidence.score(chunk);
            s.put(com.autism.seedcracker.util.pure.SusScore.Signal.BUILT, bc.score() >= 60 ? 1.0 : bc.score() >= 35 ? 0.5 : 0);
        }

        com.autism.seedcracker.util.pure.SusScore.Result r = com.autism.seedcracker.util.pure.SusScore.score(
            s, betaSpread.get() ? betaNeighbourHeat(pos) : 0, betaThreshold.get(), betaMinSignals.get());
        betaResults.put(pos, r);
        if (r.flagged()) {
            flagged.add(pos);
            if (notified.add(pos) && notify.get()) {
                com.autism.seedcracker.finder.FinderNotify.flag("§d[SusBeta]", "Sus chunk X:" + pos.getMinBlockX()
                    + " Z:" + pos.getMinBlockZ() + " (" + r.score() + ": " + r.why() + ")", true);
            }
        } else {
            flagged.remove(pos);
        }
    }

    private int betaNeighbourHeat(ChunkPos pos) {
        int best = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                com.autism.seedcracker.util.pure.SusScore.Result r = betaResults.get(new ChunkPos(pos.x() + dx, pos.z() + dz));
                if (r != null && r.flagged()) best = Math.max(best, r.score());
            }
        }
        return best;
    }

    /** Storage that natural structures never generate (plain chests/spawners are excluded on purpose). */
    private static boolean isBetaPlayerStorage(Block b) {
        return b == Blocks.BARREL || b == Blocks.HOPPER || b == Blocks.ENDER_CHEST || b == Blocks.FURNACE
            || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER || b == Blocks.ENCHANTING_TABLE || b == Blocks.BEACON
            || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock;
    }

    private static boolean isBetaPoweredClock(BlockState s) {
        return (s.is(Blocks.REPEATER) || s.is(Blocks.COMPARATOR))
            && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
            && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED);
    }

    private void resetBeta() {
        betaResults.clear();
        betaScannedAt.clear();
        betaQueue = java.util.List.of();
        betaQueueIndex = 0;
    }

    // ---- NEW_CHUNKS / OLD_CHUNKS mode (Boze NewChunks port) ----

    /** Chunks seen with flowing fluid in a BLOCK UPDATE (fluid ticking = freshly generated). */
    private final Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();
    /** Chunks whose FULL DATA arrived already containing flowing fluid (loaded before us). */
    private final Set<ChunkPos> oldChunks = ConcurrentHashMap.newKeySet();

    @Override
    public boolean onPacketReceive(net.minecraft.network.protocol.Packet<?> packet) {
        Mode m = mode.get();
        // ACTIVITY: count chunk load/unload events (another player's render bubble cycling ours).
        if (m == Mode.ACTIVITY) {
            if (packet instanceof net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket load) {
                onChunkActivity(new ChunkPos(load.getX(), load.getZ()), true);
            } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket forget) {
                onChunkActivity(forget.pos(), false);
            }
            return false;
        }
        if (m != Mode.NEW_CHUNKS && m != Mode.OLD_CHUNKS) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        // A block UPDATE carrying flowing (non-source) fluid means the server is actively fluid-
        // ticking that chunk - which only happens during generation: a NEW chunk (Boze).
        if (packet instanceof net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket bup) {
            var fs = bup.getBlockState().getFluidState();
            if (!fs.isEmpty() && !fs.isSource()) {
                ChunkPos cp = new ChunkPos(bup.getPos().getX() >> 4, bup.getPos().getZ() >> 4);
                if (!oldChunks.contains(cp)) newChunks.add(cp);
            }
        } else if (packet instanceof net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket sup) {
            sup.runUpdates((pos, state) -> {
                var fs = state.getFluidState();
                if (!fs.isEmpty() && !fs.isSource()) {
                    ChunkPos cp = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
                    if (!oldChunks.contains(cp)) newChunks.add(cp);
                }
            });
        }
        return false;
    }

    /** Full-chunk arrival scan: flowing fluid already IN the chunk data = old chunk (visited). */
    private void tickChunkAge(Minecraft mc) {
        ChunkPos center = mc.player.chunkPosition();
        int radius = scanRadius.get();
        // Classify a few chunks per tick: full data containing flowing fluid = OLD.
        for (LevelChunk chunk : scanCursorAge.nextBatch(mc, radius, 5000, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            if (newChunks.contains(pos) || oldChunks.contains(pos)) continue;
            if (chunkHasFlowingFluid(chunk)) oldChunks.add(pos);
        }
        // Render whichever age class the mode wants.
        flagged.clear();
        flagged.addAll(mode.get() == Mode.NEW_CHUNKS ? newChunks : oldChunks);
        int pr = radius * 4; // age intel is worth keeping further out than live scans
        flagged.removeIf(p -> tooFar(p, center, pr));
    }

    private final com.autism.seedcracker.finder.ScanCursor scanCursorAge = new com.autism.seedcracker.finder.ScanCursor();

    private static boolean chunkHasFlowingFluid(LevelChunk chunk) {
        int minY = chunk.getMinY();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        for (int s = 0; s < chunk.getSectionsCount(); s++) {
            var sec = chunk.getSection(s);
            if (sec.hasOnlyAir()) continue;
            int baseY = minY + (s << 4);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        var fs = sec.getBlockState(x, y, z).getFluidState();
                        if (!fs.isEmpty() && !fs.isSource()) return true;
                    }
                }
            }
        }
        return false;
    }

    // ---- TUNNEL mode (Boze TunnelESP corridor classifier) ----

    private final com.autism.seedcracker.finder.ScanCursor scanCursorTunnel = new com.autism.seedcracker.finder.ScanCursor();

    private void tickTunnelScan(Minecraft mc) {
        ChunkPos center = mc.player.chunkPosition();
        int radius = scanRadius.get();
        int need = sensitivity.get().scale(4); // corridor cells needed to flag a chunk
        for (LevelChunk chunk : scanCursorTunnel.nextBatch(mc, radius, 5000, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            if (countTunnelCells(mc, chunk, need) >= need) {
                if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
            } else {
                flagged.remove(pos);
            }
        }
        int pr = radius + 2;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
    }

    /**
     * Boze method2074 corridor test: a tunnel cell is 2-high walkable air standing on solid
     * ground where exactly one horizontal axis is open (both directions air) and the other is
     * walled (both directions solid) - natural caves almost never form that pattern repeatedly.
     * Only scans below Y50 (surface trenches are farms/creeper holes, not tunnels).
     */
    private int countTunnelCells(Minecraft mc, LevelChunk chunk, int enough) {
        int count = 0;
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY = Math.max(chunk.getMinY() + 1, -60);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= 50; y++) {
                    m.set(startX + x, y, startZ + z);
                    if (!mc.level.getBlockState(m).isAir()) continue;
                    if (!mc.level.getBlockState(m.above()).isAir()) continue;          // 2-high air
                    if (mc.level.getBlockState(m.below()).isAir()) continue;           // solid floor
                    boolean eastOpen = mc.level.getBlockState(m.east()).isAir();
                    boolean westOpen = mc.level.getBlockState(m.west()).isAir();
                    boolean northOpen = mc.level.getBlockState(m.north()).isAir();
                    boolean southOpen = mc.level.getBlockState(m.south()).isAir();
                    boolean xCorridor = eastOpen && westOpen && !northOpen && !southOpen;
                    boolean zCorridor = northOpen && southOpen && !eastOpen && !westOpen;
                    if ((xCorridor || zCorridor) && ++count >= enough) return count;
                }
            }
        }
        return count;
    }

    // ---- XENON mode: fast below-Y15 player-placement detection ----

    private void tickXenon(Minecraft mc) {
        long now = System.currentTimeMillis();
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();
        int minY = mc.level.getMinY();

        // Budgeted + round-robin: only chunksPerTick chunks per tick, and the start offset
        // advances each tick so every chunk in the bubble gets a turn (see scanOffset above).
        int budget = chunksPerTick.get();
        int side = radius * 2 + 1;
        int total = side * side;
        for (int i = 0; i < total && budget > 0; i++) {
            int idx = (scanOffset + i) % total;
            int dx = idx % side - radius;
            int dz = idx / side - radius;
            {
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                ChunkPos pos = new ChunkPos(cx, cz);

                Long last = lastScan.get(pos);
                if (last != null && now - last < rescanMs.get()) continue;
                lastScan.put(pos, now);

                LevelChunk chunk = mc.level.getChunk(cx, cz);
                int code = susCodeBelowY15(mc, chunk, minY);
                boolean sus = code > 0;
                // Structure veto only applies to weak (structure-prone) evidence: a shulker or
                // furnace inside a mineshaft is still a player stash.
                if (code == 1 && skipStructures.get() && isNaturalStructure(mc, pos)) sus = false;
                if (sus) {
                    if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
                } else {
                    flagged.remove(pos);
                }
                if (--budget <= 0) break;
            }
        }
        scanOffset = (scanOffset + chunksPerTick.get()) % total;

        // Prune out-of-range.
        int pr = radius + 1;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
        lastScan.keySet().removeIf(p -> tooFar(p, center, pr));
    }

    // ---- WATER mode: Water-client SusChunkFinder port (vines/kelp-age/deepslate/caves) ----

    private void tickWater(Minecraft mc) {
        long now = System.currentTimeMillis();
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();

        int budget = chunksPerTick.get();
        int side = radius * 2 + 1;
        int total = side * side;
        for (int i = 0; i < total && budget > 0; i++) {
            int idx = (scanOffset + i) % total;
            int dx = idx % side - radius;
            int dz = idx / side - radius;
            {
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                ChunkPos pos = new ChunkPos(cx, cz);

                Long last = lastScan.get(pos);
                if (last != null && now - last < rescanMs.get() * 10L) continue;
                lastScan.put(pos, now);

                LevelChunk chunk = mc.level.getChunk(cx, cz);
                if (scanWaterChunk(mc, chunk)) {
                    if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
                } else {
                    flagged.remove(pos);
                }
                if (--budget <= 0) break;
            }
        }
        scanOffset = (scanOffset + chunksPerTick.get()) % total;

        int pr = radius + 1;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
        lastScan.keySet().removeIf(p -> tooFar(p, center, pr));
    }

    private boolean scanWaterChunk(Minecraft mc, LevelChunk chunk) {
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY() - 1;
        var sens = sensitivity.get();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        // Long vine columns (top-down run count; section palette walk with maybeHas fast-skip).
        if (waterVines.get()
            && vineRunHit(chunk, sens.scale(waterVineLength.get()), maxY, Math.max(minY, 30))) {
            return true;
        }

        // Max-age kelp (AGE blockstate property; NOTE worldgen kelp also rolls high ages - noisy).
        if (waterKelp.get()) {
            int req = Math.min(25, waterKelpAge.get());
            if (com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk,
                    s -> s.is(Blocks.KELP)
                        && s.hasProperty(net.minecraft.world.level.block.KelpBlock.AGE)
                        && s.getValue(net.minecraft.world.level.block.KelpBlock.AGE) >= req, 1) >= 1) {
                return true;
            }
        }

        // Rotated (sideways-axis) deepslate: players place it rotated, plain worldgen never does.
        if (waterDeepslate.get()) {
            int req = sens.scale(waterDeepslateCount.get());
            if (com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk,
                    SusChunkFinderModule::isRotatedDeepslate, req) >= req) {
                return true;
            }
        }

        // Big deep cave: largest connected air pocket between Y-60..20 (flood fill inside the chunk).
        if (waterCaves.get()) {
            int reqPts = sens.scale(waterCavePoints.get());
            int caveMinY = Math.max(minY, -60);
            int caveMaxY = Math.min(20, maxY);
            int cap = reqPts * 25 * 4;
            java.util.Set<Long> visited = new java.util.HashSet<>();
            int bestSize = 0;
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = caveMinY + 1; y < caveMaxY; y++) {
                        long pk = ((long) x << 16) | ((long) z << 8) | (long) (y - caveMinY);
                        if (visited.contains(pk)) continue;
                        m.set(startX + x, y, startZ + z);
                        if (!chunk.getBlockState(m).isAir()) continue;

                        java.util.ArrayDeque<int[]> q = new java.util.ArrayDeque<>();
                        q.add(new int[]{x, y, z});
                        int size = 0;
                        while (!q.isEmpty() && size < cap) {
                            int[] cur = q.poll();
                            int lx = cur[0], ly = cur[1], lz = cur[2];
                            if (lx < 0 || lx >= 16 || lz < 0 || lz >= 16) continue;
                            if (ly < caveMinY + 1 || ly >= caveMaxY) continue;
                            long p2 = ((long) lx << 16) | ((long) lz << 8) | (long) (ly - caveMinY);
                            if (!visited.add(p2)) continue;
                            m.set(startX + lx, ly, startZ + lz);
                            if (!chunk.getBlockState(m).isAir()) continue;
                            size++;
                            q.add(new int[]{lx + 1, ly, lz}); q.add(new int[]{lx - 1, ly, lz});
                            q.add(new int[]{lx, ly + 1, lz}); q.add(new int[]{lx, ly - 1, lz});
                            q.add(new int[]{lx, ly, lz + 1}); q.add(new int[]{lx, ly, lz - 1});
                        }
                        if (size > bestSize) bestSize = size;
                    }
                }
            }
            if (bestSize / 25 >= reqPts) return true;
        }

        return false;
    }

    // ---- DOGS mode: our own score-based detector (WATER's ideas, corroboration required) ----

    private void tickDogs(Minecraft mc) {
        long now = System.currentTimeMillis();
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();

        int budget = chunksPerTick.get();
        int side = radius * 2 + 1;
        int total = side * side;
        for (int i = 0; i < total && budget > 0; i++) {
            int idx = (scanOffset + i) % total;
            int dx = idx % side - radius;
            int dz = idx / side - radius;
            {
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                ChunkPos pos = new ChunkPos(cx, cz);

                Long last = lastScan.get(pos);
                if (last != null && now - last < rescanMs.get() * 10L) continue;
                lastScan.put(pos, now);

                LevelChunk chunk = mc.level.getChunk(cx, cz);
                if (scanDogsChunk(mc, chunk)) {
                    if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
                } else {
                    flagged.remove(pos);
                }
                if (--budget <= 0) break;
            }
        }
        scanOffset = (scanOffset + chunksPerTick.get()) % total;

        int pr = radius + 1;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
        lastScan.keySet().removeIf(p -> tooFar(p, center, pr));
    }

    /** Score-based: each independent signal adds points, {@code dogsNeedScore} points flag. */
    private boolean scanDogsChunk(Minecraft mc, LevelChunk chunk) {
        var sens = sensitivity.get();
        int need = dogsNeedScore.get();
        int score = 0;
        Boolean natural = null; // lazy: the structure scan is the expensive part

        // Player storage below Y50 (block-entity map lookup: near-free).
        if (dogsStorage.get()) {
            boolean strong = false, weak = false;
            for (var e : chunk.getBlockEntities().entrySet()) {
                if (e.getKey().getY() > 50) continue;
                Block b = e.getValue().getBlockState().getBlock();
                if (placementWeight(b) >= 2) { strong = true; break; }
                if (b == Blocks.CHEST || b == Blocks.SPAWNER) weak = true;
            }
            if (strong) {
                score += 3;
            } else if (weak) {
                if (natural == null) natural = isNaturalStructure(mc, chunk.getPos());
                if (!natural) score += 1;
            }
            if (score >= need) return true;
        }

        // Vertical vine run below Y45: vines never generate that deep, players drop them into shafts.
        if (dogsVines.get() && vineRunHit(chunk, dogsVineLen.get(), 45, chunk.getMinY())) {
            score += 2;
            if (score >= need) return true;
        }

        // Kelp below Y24: oceans never reach that deep - player water tunnel or farm.
        if (dogsKelp.get() && kelpBelowY(chunk, 24)) {
            score += 2;
            if (score >= need) return true;
        }

        // Rotated deepslate, vetoed near ancient-city/stronghold signatures (templates rotate blocks).
        if (dogsDeepslate.get()) {
            int req = sens.scale(dogsDeepslateCount.get());
            if (com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk,
                    SusChunkFinderModule::isRotatedDeepslate, req) >= req) {
                if (natural == null) natural = isNaturalStructure(mc, chunk.getPos());
                if (!natural) {
                    score += 2;
                    if (score >= need) return true;
                }
            }
        }

        // Flat mined-out floor below Y20: players mine flat floors, natural caves don't.
        if (dogsRoom.get() && flatRoomHit(chunk, sens.scale(dogsRoomSize.get()))) {
            score += 2;
        }

        return score >= need;
    }

    static boolean isRotatedDeepslate(BlockState s) {
        return s.is(Blocks.DEEPSLATE)
            && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
            && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS) != net.minecraft.core.Direction.Axis.Y;
    }

    /**
     * True when some column has a vertical VINE run of at least {@code need} between yLo..yHi.
     * Section palette walk, top to bottom: skipped sections (all-air / provably vineless via
     * maybeHas) reset every column's run - the column is broken there anyway.
     */
    static boolean vineRunHit(LevelChunk chunk, int need, int yHi, int yLo) {
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinY();
        int[] runs = new int[256];
        for (int s = sections.length - 1; s >= 0; s--) {
            int base = minY + (s << 4);
            if (base > yHi) continue;
            if (base + 15 < yLo) break;
            var sec = sections[s];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(st -> st.is(Blocks.VINE))) {
                java.util.Arrays.fill(runs, 0);
                continue;
            }
            int top = Math.min(15, yHi - base);
            int bot = Math.max(0, yLo - base);
            for (int y = top; y >= bot; y--) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        int ci = (x << 4) | z;
                        if (sec.getBlockState(x, y, z).is(Blocks.VINE)) {
                            if (++runs[ci] >= need) return true;
                        } else {
                            runs[ci] = 0;
                        }
                    }
                }
            }
        }
        return false;
    }

    /** Any kelp at or below {@code yCutoff} (section palette walk with maybeHas fast-skip). */
    static boolean kelpBelowY(LevelChunk chunk, int yCutoff) {
        net.minecraft.world.level.chunk.LevelChunkSection[] sections = chunk.getSections();
        int minY = chunk.getMinY();
        java.util.function.Predicate<BlockState> p = st -> st.is(Blocks.KELP) || st.is(Blocks.KELP_PLANT);
        for (int s = 0; s < sections.length; s++) {
            int base = minY + (s << 4);
            if (base > yCutoff) break;
            var sec = sections[s];
            if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(p)) continue;
            int top = Math.min(15, yCutoff - base);
            for (int y = 0; y <= top; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (p.test(sec.getBlockState(x, y, z))) return true;
                    }
                }
            }
        }
        return false;
    }

    /** >= need walkable cells (air + air above + solid floor) on ONE Y level below Y20. */
    static boolean flatRoomHit(LevelChunk chunk, int need) {
        int minY = chunk.getMinY();
        int lo = Math.max(minY + 1, -60);
        int hi = 20;
        if (hi <= lo) return false;
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        int[] perY = new int[hi - lo + 1];
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                // Rolling window: one read per Y instead of three.
                boolean below = !chunk.getBlockState(m.set(startX + x, lo - 1, startZ + z)).isAir();
                boolean cur = chunk.getBlockState(m.set(startX + x, lo, startZ + z)).isAir();
                for (int y = lo; y <= hi; y++) {
                    boolean above = chunk.getBlockState(m.set(startX + x, y + 1, startZ + z)).isAir();
                    if (below && cur && above && ++perY[y - lo] >= need) return true;
                    below = !cur;
                    cur = above;
                }
            }
        }
        return false;
    }

    // ---- ACTIVITY mode: chunk load/unload cycle counting (Water ActivityDebug port) ----

    private static final class ChunkActivity {
        final java.util.ArrayDeque<Long> loads = new java.util.ArrayDeque<>();
        final java.util.ArrayDeque<Long> unloads = new java.util.ArrayDeque<>();

        synchronized void add(java.util.ArrayDeque<Long> list) {
            long now = System.currentTimeMillis();
            list.addLast(now);
            while (!list.isEmpty() && now - list.peekFirst() > 600_000L) list.pollFirst();
        }

        synchronized int recent(java.util.ArrayDeque<Long> list, long windowMs) {
            long now = System.currentTimeMillis();
            int n = 0;
            for (long t : list) if (now - t < windowMs) n++;
            return n;
        }
    }

    private final Map<ChunkPos, ChunkActivity> activity = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Long> activityFlaggedAt = new ConcurrentHashMap<>();

    private void onChunkActivity(ChunkPos pos, boolean load) {
        ChunkActivity act = activity.computeIfAbsent(pos, k -> new ChunkActivity());
        act.add(load ? act.loads : act.unloads);
        long windowMs = activityWindow.get() * 1000L;
        int loads = act.recent(act.loads, windowMs);
        int unloads = act.recent(act.unloads, windowMs);
        if (Math.min(loads, unloads) >= activityCycles.get() && unloads >= activityUnloads.get()) {
            activityFlaggedAt.put(pos, System.currentTimeMillis());
        }
    }

    private void tickActivity(Minecraft mc) {
        long fadeMs = activityFade.get() * 60_000L;
        long now = System.currentTimeMillis();
        activityFlaggedAt.entrySet().removeIf(e -> now - e.getValue() > fadeMs);
        flagged.clear();
        flagged.addAll(activityFlaggedAt.keySet());
        for (ChunkPos p : activityFlaggedAt.keySet()) {
            if (notified.add(p)) onNewFlag(p);
        }
        // Bound the tracking map (chunks far outside interest fade naturally).
        if (activity.size() > 4096) activity.clear();
    }

    // ---- SIGNAL mode: loaded chunks beyond the server's render radius (Water SignalScanner port) ----

    private void tickSignal(Minecraft mc) {
        long now = System.currentTimeMillis();
        ChunkPos center = mc.player.chunkPosition();
        int serverR = signalServerRadius.get();
        int scanR = Math.max(scanRadius.get() * 4, serverR + 2); // look well past the bubble

        int budget = chunksPerTick.get() * 4; // cheap per-chunk check, allow more
        outer:
        for (int dx = -scanR; dx <= scanR && budget > 0; dx++) {
            for (int dz = -scanR; dz <= scanR; dz++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dz));
                if (dist <= serverR) continue; // inside our own bubble - expected to be loaded
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                ChunkPos pos = new ChunkPos(cx, cz);

                Long last = lastScan.get(pos);
                if (last != null && now - last < rescanMs.get()) continue;
                lastScan.put(pos, now);
                budget--;

                boolean sus = true;
                if (signalNeedBlocks.get()) {
                    sus = chunkHasSignalBlocks(mc.level.getChunk(cx, cz));
                }
                if (sus) {
                    if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
                } else {
                    flagged.remove(pos);
                }
                if (budget <= 0) break outer;
            }
        }

        int pr = scanR + 1;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
        lastScan.keySet().removeIf(p -> tooFar(p, center, pr));
    }

    /** Storage/utility block entities = player worked here (SignalScanner's signal-block list). */
    private static boolean chunkHasSignalBlocks(LevelChunk chunk) {
        for (var e : chunk.getBlockEntities().entrySet()) {
            Block b = e.getValue().getBlockState().getBlock();
            if (b == Blocks.CHEST || b == Blocks.TRAPPED_CHEST || b == Blocks.ENDER_CHEST
                || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
                || b == Blocks.HOPPER || b == Blocks.DROPPER || b == Blocks.DISPENSER
                || b == Blocks.SPAWNER || b == Blocks.BARREL || b == Blocks.ENCHANTING_TABLE
                || b == Blocks.BEACON || b == Blocks.CONDUIT
                || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sus code for the chunk below Y15: 0 = clean, 1 = weak evidence (structure-prone blocks -
     * the natural-structure veto applies), 2 = strong evidence (player-only blocks - no veto).
     * Splitting the evidence kills the old false flags: one mineshaft torch or natural obsidian
     * used to flag instantly on HIGH/MEDIUM.
     */
    private int susCodeBelowY15(Minecraft mc, LevelChunk chunk, int minY) {
        var sens = sensitivity.get();
        int weakNeed = switch (sens) {
            case HIGH -> 2;
            case MEDIUM -> 4;
            default -> 6;
        };
        int strongNeed = sens == com.autism.seedcracker.finder.FinderSensitivity.LOW ? 2 : 1;
        int weak = 0, strong = 0;
        int startX = chunk.getPos().getMinBlockX();
        int startZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        // Scan section-by-section, skipping all-air sections (hasOnlyAir is a cached palette
        // check) - cuts most of the 20k block reads in cavey chunks.
        for (int sy = minY; sy <= 15; sy += 16) {
            int sectionIdx = chunk.getSectionIndex(sy);
            if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) continue;
            if (chunk.getSection(sectionIdx).hasOnlyAir()) continue;
            int yLo = Math.max(sy, minY);
            int yHi = Math.min(sy + 15, 15);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = yHi; y >= yLo; y--) {
                        m.set(startX + x, y, startZ + z);
                        Block b = chunk.getBlockState(m).getBlock();
                        int w = placementWeight(b);
                        if (w == 0) continue;
                        // Obsidian touching lava/water formed naturally at a fluid contact.
                        if (b == Blocks.OBSIDIAN && touchesFluid(mc, m)) continue;
                        if (w >= 2) {
                            if (++strong >= strongNeed) return 2;
                        } else if (++weak >= weakNeed) {
                            return 1;
                        }
                    }
                }
            }
        }
        // Near-threshold mix (e.g. 1 strong on LOW + several weak) still deserves a vetoable flag.
        if (strong > 0 && weak >= 2) return 1;
        return 0;
    }

    /** 2 = never generates below Y15 (player-only), 1 = also generates inside natural structures. */
    private static int placementWeight(Block b) {
        if (b == Blocks.ENDER_CHEST || b instanceof net.minecraft.world.level.block.ShulkerBoxBlock
            || b == Blocks.HOPPER || b == Blocks.BARREL || b == Blocks.TRAPPED_CHEST
            || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE || b == Blocks.SMOKER
            || b == Blocks.CRAFTING_TABLE || b == Blocks.ENCHANTING_TABLE
            || b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL || b == Blocks.DAMAGED_ANVIL
            || b == Blocks.BREWING_STAND || b == Blocks.BEE_NEST || b == Blocks.BEEHIVE
            || b == Blocks.CAULDRON || b == Blocks.WATER_CAULDRON || b == Blocks.LAVA_CAULDRON
            || b == Blocks.NETHER_PORTAL) {
            return 2;
        }
        if (b == Blocks.CHEST || b == Blocks.SPAWNER || b == Blocks.OBSIDIAN
            || b == Blocks.END_PORTAL_FRAME
            || b == Blocks.TORCH || b == Blocks.WALL_TORCH
            || b == Blocks.LANTERN || b == Blocks.SOUL_LANTERN
            || b == Blocks.RAIL || b == Blocks.POWERED_RAIL
            || b == Blocks.DETECTOR_RAIL || b == Blocks.ACTIVATOR_RAIL) {
            return 1;
        }
        return 0;
    }

    /** Any of the 6 neighbours is lava/water (flowing or source). */
    private static boolean touchesFluid(Minecraft mc, BlockPos pos) {
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            if (!mc.level.getBlockState(pos.relative(d)).getFluidState().isEmpty()) return true;
        }
        return false;
    }

    /**
     * True if the chunk is part of a natural structure, not a base. Recognizes dungeons + trial
     * chambers (as before) plus the generators of most false flags: mineshafts (planks/fences/
     * cobwebs alongside their torches, rails and chests), ancient cities (deepslate tiles, sculk,
     * soul lanterns - all below Y15 by definition) and strongholds (stone bricks + iron bars,
     * around END_PORTAL_FRAME rooms). Plain TUFF was removed from the dungeon signature: it
     * generates as natural ore-like blobs everywhere below Y0, so it both vetoed real bases and
     * said nothing about structures.
     */
    private boolean isNaturalStructure(Minecraft mc, ChunkPos pos) {
        int dungeonTrial = 0, mineshaft = 0, ancientCity = 0, stronghold = 0;
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int x = 0; x < 16; x += 2) {
            for (int z = 0; z < 16; z += 2) {
                for (int y = 15; y >= mc.level.getMinY(); y -= 2) {
                    m.set(baseX + x, y, baseZ + z);
                    Block b = mc.level.getBlockState(m).getBlock();
                    if (b == Blocks.MOSSY_COBBLESTONE
                        || b == Blocks.POLISHED_TUFF || b == Blocks.TUFF_BRICKS
                        || b == Blocks.CHISELED_TUFF || b == Blocks.CHISELED_TUFF_BRICKS
                        || b == Blocks.TRIAL_SPAWNER || b == Blocks.VAULT) {
                        dungeonTrial++;
                    } else if (b == Blocks.OAK_PLANKS || b == Blocks.DARK_OAK_PLANKS
                        || b == Blocks.OAK_FENCE || b == Blocks.DARK_OAK_FENCE
                        || b == Blocks.COBWEB) {
                        mineshaft++;
                    } else if (b == Blocks.DEEPSLATE_TILES || b == Blocks.CRACKED_DEEPSLATE_TILES
                        || b == Blocks.DEEPSLATE_BRICKS || b == Blocks.CRACKED_DEEPSLATE_BRICKS
                        || b == Blocks.SCULK || b == Blocks.SCULK_SENSOR || b == Blocks.SCULK_SHRIEKER
                        || b == Blocks.SCULK_CATALYST || b == Blocks.SOUL_FIRE) {
                        ancientCity++;
                    } else if (b == Blocks.STONE_BRICKS || b == Blocks.MOSSY_STONE_BRICKS
                        || b == Blocks.CRACKED_STONE_BRICKS || b == Blocks.IRON_BARS) {
                        stronghold++;
                    }
                }
            }
        }
        return dungeonTrial >= 3 || mineshaft >= 4 || ancientCity >= 4 || stronghold >= 4;
    }

    // ---- TYPES mode: incremental per-block-type scanner (spread across ticks to avoid lag) ----

    private java.util.List<LevelChunk> scanQueue = java.util.Collections.emptyList();
    private int scanIndex = 0;
    private long lastQueueRefreshMs = 0;

    private void tickTypes(Minecraft mc) {
        // Refresh the chunk queue periodically (or when exhausted), then scan only chunksPerTick
        // chunks this tick. A full pass therefore spreads over several seconds, so there's no
        // single-tick FPS spike.
        long now = System.currentTimeMillis();
        if (scanIndex >= scanQueue.size() && now - lastQueueRefreshMs >= rescanMs.get()) {
            scanQueue = com.autism.seedcracker.finder.ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
            scanIndex = 0;
            lastQueueRefreshMs = now;
        }

        int budget = chunksPerTick.get();
        ChunkPos center = mc.player.chunkPosition();
        while (budget-- > 0 && scanIndex < scanQueue.size()) {
            LevelChunk chunk = scanQueue.get(scanIndex++);
            scanChunkTypes(mc, chunk);
        }

        int pr = scanRadius.get() + 2;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
    }

    // ---- GEODE mode (Anubis sus-chunk port) ----
    // Two detectors combined:
    //   CLASSIC  - scan sections for amethyst clusters + buds (a geode = a cave = player traffic).
    //   GLOW     - scan for underground light pockets: amethyst clusters EMIT light, so a lit
    //              pocket deep underground with open air around it is almost always a geode, which
    //              means a cave system - exactly where players tunnel and base.
    // Both flag the chunk; the glow test is what makes this different from TYPES'amethyst check
    // (it finds geodes even when the amethyst itself is out of render/simulation detail range).

    // Anubis SusChunkFinderModule heat-map state. Chunks accumulate a heat score; a hot chunk
    // spreads heat to its neighbours (corroboration); natural/unfinished chunks veto; only chunks
    // with enough scanned neighbours + accumulated heat flag; hottestPerPatch dedupes the render.
    private java.util.List<LevelChunk> geodeQueue = java.util.Collections.emptyList();
    private int geodeIndex = 0;
    private long lastGeodeRefreshMs = 0;

    /** chunkKey -> accumulated heat (after spread). */
    private final Map<Long, Integer> geodeHeat = new ConcurrentHashMap<>();
    /** chunkKey -> this chunk's own (pre-spread) heat, so re-scans replace rather than stack. */
    private final Map<Long, Integer> geodeSelf = new ConcurrentHashMap<>();
    /** chunkKey -> vetoed (natural/unfinished area - Anubis hasUngrown suppresses it). */
    private final Set<Long> geodeVeto = ConcurrentHashMap.newKeySet();
    /** chunkKey -> already scanned (for the scanned-neighbours edge-artifact gate). */
    private final Set<Long> geodeScanned = ConcurrentHashMap.newKeySet();

    private static long ckey(int x, int z) { return (((long) x) << 32) | (z & 0xffffffffL); }
    private static int ckx(long k) { return (int) (k >> 32); }
    private static int ckz(long k) { return (int) k; }

    private int geodeMaintTicks = 0;
    private int geodeVisibleTicks = 0;
    private boolean geodeDirty = false;

    private void tickGeode(Minecraft mc) {
        long now = System.currentTimeMillis();
        // Geodes don't change: rescan at 10x the base interval like DOGS/WATER.
        if (geodeIndex >= geodeQueue.size() && now - lastGeodeRefreshMs >= rescanMs.get() * 10L) {
            geodeQueue = com.autism.seedcracker.finder.ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
            geodeIndex = 0;
            lastGeodeRefreshMs = now;
        }

        int budget = chunksPerTick.get();
        while (budget-- > 0 && geodeIndex < geodeQueue.size()) {
            LevelChunk chunk = geodeQueue.get(geodeIndex++);
            scanChunkGeode(mc, chunk);
            geodeDirty = true;
        }

        ChunkPos center = mc.player.chunkPosition();
        // Housekeeping once a second; four removeIf sweeps every tick were pure overhead.
        if (++geodeMaintTicks >= 20) {
            geodeMaintTicks = 0;
            int pr = scanRadius.get() + 4;
            geodeHeat.keySet().removeIf(k -> Math.max(Math.abs(ckx(k) - center.x()), Math.abs(ckz(k) - center.z())) > pr);
            geodeSelf.keySet().removeIf(k -> Math.max(Math.abs(ckx(k) - center.x()), Math.abs(ckz(k) - center.z())) > pr);
            geodeVeto.removeIf(k -> Math.max(Math.abs(ckx(k) - center.x()), Math.abs(ckz(k) - center.z())) > pr);
            geodeScanned.removeIf(k -> Math.max(Math.abs(ckx(k) - center.x()), Math.abs(ckz(k) - center.z())) > pr);
            int pr2 = scanRadius.get() + 2;
            notified.removeIf(p -> tooFar(p, center, pr2));
            geodeDirty = true;
        }

        // Cluster flood-fill over every heat entry is the main frame cost: only redo it twice a
        // second, and only when the heat map actually changed.
        if (!geodeDirty || ++geodeVisibleTicks < 10) return;
        geodeVisibleTicks = 0;
        geodeDirty = false;

        // Anubis computeVisible: a chunk flags if it has enough accumulated heat AND isn't vetoed
        // AND enough of its neighbours were actually scanned (avoids map-edge false positives).
        // hottestPerPatch then keeps only the single hottest chunk per connected cluster, so a big
        // geode field renders ONE clean box instead of a wall of red.
        flagged.clear();
        Map<Long, Integer> visible = hottestPerPatch(geodeHeat);
        int needHeat = geodeHeatNeed.get();
        for (Map.Entry<Long, Integer> e : visible.entrySet()) {
            long k = e.getKey();
            if (e.getValue() < needHeat) continue;
            if (geodeVeto.contains(k)) continue;
            if (scannedNeighbours(ckx(k), ckz(k)) < 3) continue; // Anubis MIN_SCANNED_NEIGHBOURS
            ChunkPos pos = new ChunkPos(ckx(k), ckz(k));
            if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
        }
    }

    /** Score one chunk into the heat-map (Anubis updateChunk): compute self-heat, replace the old
     * self contribution, spread the delta to neighbours, and record natural vetoes. */
    private void scanChunkGeode(Minecraft mc, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        long key = ckey(pos.x(), pos.z());

        // Self-heat: amethyst growth (classic) = a strong base heat, plus glow-cell count / 12
        // (Anubis GeodeGlowScan returns the glow-edge cell count and divides by 12 for the score).
        int self = 0;
        int amethyst = com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(
            chunk, SusChunkFinderModule::isAmethystGrowth, 64);
        if (amethyst > 0) self += 6 + amethyst; // a confirmed geode in this chunk
        if (glow.get()) self += countGlowCells(mc, chunk) / 12;

        boolean hasUngrown = amethyst == 0 && self == 0; // nothing here -> natural, veto it
        geodeScanned.add(key);

        int spread = geodeSpread.get();
        int prev = geodeSelf.getOrDefault(key, 0);
        int delta = self - prev;
        geodeSelf.put(key, self);

        // Replace the heat contribution across the spread area (Anubis forArea + heat.addTo).
        for (int dx = -spread; dx <= spread; dx++) {
            for (int dz = -spread; dz <= spread; dz++) {
                long nk = ckey(pos.x() + dx, pos.z() + dz);
                if (delta != 0) {
                    int nv = geodeHeat.getOrDefault(nk, 0) + delta;
                    if (nv <= 0) geodeHeat.remove(nk); else geodeHeat.put(nk, nv);
                }
            }
        }

        // Anubis hasUngrown veto: a fully-natural chunk (no amethyst, no glow) vetoes itself and its
        // spread area so surrounding natural caves don't accumulate phantom heat.
        if (hasUngrown) {
            for (int dx = -spread; dx <= spread; dx++) {
                for (int dz = -spread; dz <= spread; dz++) {
                    geodeVeto.add(ckey(pos.x() + dx, pos.z() + dz));
                }
            }
        } else {
            geodeVeto.remove(key); // a real signal clears this chunk's veto
        }
    }

    /** Count 8-neighbours that have been scanned (Anubis scannedNeighbours). */
    private int scannedNeighbours(int x, int z) {
        int n = 0;
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                if ((dx != 0 || dz != 0) && geodeScanned.contains(ckey(x + dx, z + dz))) n++;
        return n;
    }

    /** Keep only the single hottest chunk per connected cluster (Anubis hottestPerPatch): flood-fill
     * clusters of hot chunks and emit each cluster's max, so one geode = one rendered box. */
    private static Map<Long, Integer> hottestPerPatch(Map<Long, Integer> heat) {
        Map<Long, Integer> out = new java.util.HashMap<>();
        Set<Long> done = new java.util.HashSet<>();
        for (Long seed : heat.keySet()) {
            if (!done.add(seed)) continue;
            // Flood-fill this cluster.
            long best = seed;
            int bestVal = heat.getOrDefault(seed, 0);
            java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
            queue.add(seed);
            while (!queue.isEmpty()) {
                long cur = queue.poll();
                int v = heat.getOrDefault(cur, 0);
                if (v > bestVal) { bestVal = v; best = cur; }
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dz == 0) continue;
                        long nk = ckey(ckx(cur) + dx, ckz(cur) + dz);
                        if (heat.containsKey(nk) && done.add(nk)) queue.add(nk);
                    }
                }
            }
            out.put(best, bestVal);
        }
        return out;
    }

    /** Amethyst cluster or any bud stage (matches Anubis isAmethystGrowth). */
    static boolean isAmethystGrowth(net.minecraft.world.level.block.state.BlockState s) {
        return s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.LARGE_AMETHYST_BUD)
            || s.is(Blocks.MEDIUM_AMETHYST_BUD) || s.is(Blocks.SMALL_AMETHYST_BUD);
    }

    /** Open air or an amethyst cluster (matches Anubis open(): a cave cell a glow can reach). */
    static boolean isOpenForGlow(net.minecraft.world.level.block.state.BlockState s) {
        return s.isAir() || s.is(Blocks.AMETHYST_CLUSTER);
    }

    /**
     * Count underground glow-edge cells in the chunk (Anubis GeodeGlowScan.scan returns this count;
     * SusChunkFinder divides it by 12 for the heat). A glow-edge cell is an open-air cell whose own
     * block-light is dark but sits next to a lit cell at the amethyst-glow level - the rim of an
     * amethyst pocket. Reads block light from the lighting engine (no world mutation, no fragile
     * internal DataLayer access).
     */
    /** Stop counting here: heat is count/12 and the heat-to-flag slider tops out at 30. */
    private static final int GLOW_COUNT_CAP = 12 * 40;

    private int countGlowCells(Minecraft mc, LevelChunk chunk) {
        int baseX = chunk.getPos().getMinBlockX();
        int baseZ = chunk.getPos().getMinBlockZ();
        int minY = Math.max(mc.level.getMinY() + 4, geodeMinY.get());
        int maxY = Math.min(geodeMaxY.get(), mc.player.getBlockY());
        int needLight = geodeGlowLight.get();
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int secBase = Math.floorDiv(minY, 16) * 16; secBase <= maxY; secBase += 16) {
            int idx = chunk.getSectionIndex(secBase);
            if (idx < 0 || idx >= chunk.getSectionsCount()) continue;
            net.minecraft.world.level.chunk.LevelChunkSection sec = chunk.getSection(idx);
            // Palette skip: solid stone sections have no cell a glow can reach.
            if (!sec.maybeHas(SusChunkFinderModule::isOpenForGlow)) continue;
            int yLo = Math.max(secBase, minY);
            int yHi = Math.min(secBase + 15, maxY);
            for (int y = yLo; y <= yHi; y++) {
                int ly = y & 15;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        if (!isOpenForGlow(sec.getBlockState(x, ly, z))) continue;
                        p.set(baseX + x, y, baseZ + z);
                        if (mc.level.getMaxLocalRawBrightness(p) < needLight) continue;
                        if (++count >= GLOW_COUNT_CAP) return count;
                    }
                }
            }
        }
        return count;
    }

    /** Count-vs-threshold with the sensitivity scale applied (TYPES mode). */
    private boolean typeHit(LevelChunk chunk, java.util.function.Predicate<net.minecraft.world.level.block.state.BlockState> pred, int baseCount) {
        int need = sensitivity.get().scale(baseCount);
        return com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, pred, need) >= need;
    }

    // ---- FARM mode (Water TuffChunkV2 port) ----
    // A POWERED repeater/comparator only exists while a redstone clock is actually cycling, and
    // worldgen never places one - so a cluster of lit repeaters in a chunk is an ACTIVE player
    // farm running right now (not an abandoned build). Scans the same budgeted round-robin way
    // as TYPES; the chunk is re-scanned every rescan interval so flags drop when the clock stops.

    private java.util.List<LevelChunk> farmQueue = java.util.Collections.emptyList();
    private int farmIndex = 0;
    private long lastFarmRefreshMs = 0;

    private void tickFarm(Minecraft mc) {
        long now = System.currentTimeMillis();
        if (farmIndex >= farmQueue.size() && now - lastFarmRefreshMs >= rescanMs.get()) {
            farmQueue = com.autism.seedcracker.finder.ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
            farmIndex = 0;
            lastFarmRefreshMs = now;
        }

        int budget = chunksPerTick.get();
        ChunkPos center = mc.player.chunkPosition();
        while (budget-- > 0 && farmIndex < farmQueue.size()) {
            LevelChunk chunk = farmQueue.get(farmIndex++);
            scanChunkFarm(chunk);
        }

        int pr = scanRadius.get() + 2;
        flagged.removeIf(p -> tooFar(p, center, pr));
        notified.removeIf(p -> tooFar(p, center, pr));
    }

    private void scanChunkFarm(LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        boolean comparators = farmComparators.get();
        boolean observers = farmObservers.get();
        int need = farmRepeaters.get();
        int hits = com.autism.seedcracker.finder.ChunkScanHelper.countBlocksInChunk(chunk, s -> {
            if (!s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)
                || !s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.POWERED)) return false;
            if (s.is(Blocks.REPEATER)) return true;
            if (comparators && s.is(Blocks.COMPARATOR)) return true;
            return observers && s.is(Blocks.OBSERVER);
        }, need);
        if (hits >= need) {
            if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
        } else {
            flagged.remove(pos);
        }
    }

    /** Run every enabled block-type check on one chunk and flag/unflag it. */
    private void scanChunkTypes(Minecraft mc, LevelChunk chunk) {
        ChunkPos pos = chunk.getPos();
        boolean sus = false;
        if (kelp.get() && typeHit(chunk, s -> s.is(Blocks.KELP) || s.is(Blocks.KELP_PLANT), kelpCount.get())) sus = true;
        if (!sus && bamboo.get() && typeHit(chunk, s -> s.is(Blocks.BAMBOO) || s.is(Blocks.BAMBOO_SAPLING), bambooCount.get())) sus = true;
        if (!sus && caveVines.get() && typeHit(chunk, s -> s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT), caveVinesCount.get())) sus = true;
        if (!sus && vines.get() && typeHit(chunk, s -> s.is(Blocks.VINE), vinesCount.get())) sus = true;
        if (!sus && amethystShards.get() && typeHit(chunk,
                s -> s.is(Blocks.AMETHYST_CLUSTER) || s.is(Blocks.LARGE_AMETHYST_BUD)
                    || s.is(Blocks.MEDIUM_AMETHYST_BUD) || s.is(Blocks.SMALL_AMETHYST_BUD),
                amethystShardsCount.get())) sus = true;
        if (!sus && amethystBlocks.get() && typeHit(chunk,
                s -> s.is(Blocks.AMETHYST_BLOCK) || s.is(Blocks.BUDDING_AMETHYST),
                amethystBlocksCount.get())) sus = true;
        if (!sus && beeNest.get() && typeHit(chunk, s -> s.is(Blocks.BEE_NEST) || s.is(Blocks.BEEHIVE), beeNestCount.get())) sus = true;
        if (!sus && rotatedDeepslate.get() && typeHit(chunk,
                s -> s.is(Blocks.DEEPSLATE)
                    && s.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)
                    && s.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS) != net.minecraft.core.Direction.Axis.Y,
                rotatedDeepslateCount.get())) sus = true;
        if (!sus && skullCandle.get() && typeHit(chunk, SusChunkFinderModule::isSkullOrCandle, skullCandleCount.get())) sus = true;
        if (!sus && cocoa.get() && typeHit(chunk, s -> s.is(Blocks.COCOA), cocoaCount.get())) sus = true;
        if (!sus && villagerHall.get() && chunkHasVillagerHall(mc, pos)) sus = true;
        if (sus) {
            if (flagged.add(pos) && notified.add(pos)) onNewFlag(pos);
        } else {
            flagged.remove(pos);
        }
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    /** Mob skulls + candles (nyx player-build / decoration signal). Dyed candles are typed
     *  collections in 26.2 (no plain Blocks constant), so candles are matched by registry id. */
    static boolean isSkullOrCandle(net.minecraft.world.level.block.state.BlockState s) {
        Block b = s.getBlock();
        if (b == Blocks.SKELETON_SKULL || b == Blocks.WITHER_SKELETON_SKULL
            || b == Blocks.ZOMBIE_HEAD || b == Blocks.CREEPER_HEAD || b == Blocks.PLAYER_HEAD
            || b == Blocks.PIGLIN_HEAD || b == Blocks.DRAGON_HEAD
            || b == Blocks.SKELETON_WALL_SKULL || b == Blocks.WITHER_SKELETON_WALL_SKULL
            || b == Blocks.ZOMBIE_WALL_HEAD || b == Blocks.CREEPER_WALL_HEAD || b == Blocks.PLAYER_WALL_HEAD
            || b == Blocks.PIGLIN_WALL_HEAD || b == Blocks.DRAGON_WALL_HEAD) return true;
        if (b == Blocks.CANDLE) return true;
        net.minecraft.resources.Identifier id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
        return id != null && id.toString().endsWith("_candle");
    }

    /** True if the chunk has a villager/zombie-villager/allay/vindicator/warden (nyx base signal). */
    private static boolean chunkHasVillagerHall(Minecraft mc, ChunkPos pos) {
        if (mc.level == null) return false;
        net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
            pos.getMinBlockX(), mc.level.getMinY(), pos.getMinBlockZ(),
            pos.getMaxBlockX() + 1, mc.level.getMaxY() + 1, pos.getMaxBlockZ() + 1);
        for (net.minecraft.world.entity.Entity e : mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.Entity.class, box,
                x -> x instanceof net.minecraft.world.entity.npc.villager.Villager
                    || x instanceof net.minecraft.world.entity.monster.zombie.ZombieVillager
                    || x instanceof net.minecraft.world.entity.monster.illager.Vindicator
                    || x instanceof net.minecraft.world.entity.animal.allay.Allay
                    || x instanceof net.minecraft.world.entity.monster.warden.Warden)) {
            return true;
        }
        return false;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        com.autism.seedcracker.finder.FinderNotify.flag("§d[SusChunkFinder]",
            "Sus chunk at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), true);
    }

    @Override
    public String info() {
        return flagged.isEmpty() ? "" : flagged.size() + " flagged";
    }
}
