package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.ChunkScanHelper;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Stash Finder.
 *
 * Scans the chunks around the player and counts storage-type blocks (chests, barrels, shulker
 * boxes, hoppers, ender chests, trapped chests). A chunk whose storage-block count reaches the
 * configured threshold is flagged (a hidden player stash) and announced with a toast + chat ping.
 * Flagged chunks are rendered as a box by the shared {@link ChunkFlagRenderer}.
 *
 * This is a clean-room port of the obfuscated Zelith "StashFinder" module: same detection idea
 * (per-chunk storage-block density), rewritten against the AUTISM module API.
 */
public final class StashFinderModule extends Module {

    /** Storage blocks that indicate a stash. */
    private static final Set<Block> STORAGE_BLOCKS = buildStorageBlocks();

    private static Set<Block> buildStorageBlocks() {
        Set<Block> set = new HashSet<>();
        set.add(Blocks.CHEST);
        set.add(Blocks.BARREL);
        set.add(Blocks.SHULKER_BOX);
        set.addAll(Blocks.DYED_SHULKER_BOX.asList());
        set.add(Blocks.HOPPER);
        set.add(Blocks.ENDER_CHEST);
        set.add(Blocks.TRAPPED_CHEST);
        return set;
    }

    // ---- settings ----
    public enum Mode { THRESHOLD, SCORING, DONUT }
    private final autismclient.api.module.EnumSetting<Mode> mode = add(new autismclient.api.module.EnumSetting<>(
            "mode", "Mode", Mode.THRESHOLD, Mode.values())
        .description("THRESHOLD = storage count per chunk. SCORING = REAL-vs-FAKE cluster classifier. DONUT = DonutSMP base profile: spawners x storage x low-Y combo (Radium/4E thresholds).")
        .group("General"));
    private final autismclient.api.module.EnumSetting<com.autism.seedcracker.finder.FinderSensitivity> sensitivity = add(
        new autismclient.api.module.EnumSetting<>("sensitivity", "Sensitivity",
            com.autism.seedcracker.finder.FinderSensitivity.MEDIUM, com.autism.seedcracker.finder.FinderSensitivity.values())
        .description("THRESHOLD: HIGH = half the storage count flags, LOW = double. SCORING: shifts the real/ambiguous score gates.")
        .group("General"));
    private final IntSetting threshold = add(new IntSetting(
            "threshold", "Threshold", 10, 1, 100, 1)
        .description("Storage blocks in a chunk needed to flag it as a stash.")
        .group("General")
        .visibleWhen(() -> mode.get() == Mode.THRESHOLD));
    private final IntSetting realThreshold = add(new IntSetting(
            "real-threshold", "Real threshold", 40, -100, 200, 1)
        .description("SCORING: cluster score >= this is a REAL base.")
        .group("Scoring")
        .visibleWhen(() -> mode.get() == Mode.SCORING));
    private final IntSetting minConfidence = add(new IntSetting(
            "min-confidence", "Min base confidence", 20, 0, 100, 5)
        .description("THRESHOLD: base-confidence score (0-100) needed to flag a low-count chunk (filters lone chests in natural structures).")
        .group("General")
        .visibleWhen(() -> mode.get() == Mode.THRESHOLD));
    private final IntSetting ambiguousThreshold = add(new IntSetting(
            "ambiguous-threshold", "Ambiguous threshold", -10, -100, 100, 1)
        .description("SCORING: cluster score >= this (but < real) is AMBIGUOUS.")
        .group("Scoring")
        .visibleWhen(() -> mode.get() == Mode.SCORING));
    private final BoolSetting realOnly = add(new BoolSetting(
            "real-only", "Flag real only", false)
        .description("SCORING: only flag REAL clusters (hide ambiguous).")
        .group("Scoring")
        .visibleWhen(() -> mode.get() == Mode.SCORING));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for stashes.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0xFFFFB020)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a stash chunk is found.")
        .group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 2, 1, 32, 1)
        .description("THRESHOLD mode: how many chunks to scan per tick (lower = less lag, spread over more seconds).")
        .group("Performance"));
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();

    /** Chunks currently over the threshold. */
    private final Set<ChunkPos> flagged = new HashSet<>();
    /** Chunks already announced (so we only notify once per chunk). */
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public StashFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-stash-finder", "Stash Finder", category,
            "Flags chunks dense with chests/hoppers/shulkers - likely hidden player stashes.");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        tickCounter = 0;
    }

    @Override
    protected void onOptionValueChanged(String settingId) {
        // Mode swap: THRESHOLD/SCORING/DONUT flag different things - drop stale flags at once.
        if ("mode".equals(settingId) || "sensitivity".equals(settingId)) {
            flagged.clear();
            notified.clear();
        }
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-stash-finder");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // THRESHOLD scans incrementally every tick (budgeted); SCORING runs on its rescan timer.
        if (mode.get() == Mode.THRESHOLD) {
            scan(mc);
        } else {
            tickCounter++;
            if (tickCounter % 8 == 0) scan(mc);
        }

        // Feed the renderer every tick so markers stay alive.
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-stash-finder", flagged, color.get(), tracer.get());

        // Report flagged chunks to the Base Tracker HUD (with confidence) for the merged view.
        for (ChunkPos pos : flagged) {
            int conf = 50;
            LevelChunk chunk = mc.level.hasChunk(pos.x(), pos.z()) ? mc.level.getChunk(pos.x(), pos.z()) : null;
            if (chunk != null) {
                conf = com.autism.seedcracker.finder.BaseConfidence.score(chunk).score();
            }
            com.autism.seedcracker.finder.BaseTracker.report(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, conf, "Stash");
        }
    }

    private void scan(Minecraft mc) {
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();

        if (mode.get() == Mode.SCORING) {
            // SCORING needs the whole chunk set at once for clustering: keep it on the rescan timer.
            List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, radius);
            scanScoring(mc, chunks);
        } else if (mode.get() == Mode.DONUT) {
            scanDonut(mc, radius);
        } else {
            // THRESHOLD: incremental scan, a few chunks per tick (no full-volume spike).
            for (LevelChunk chunk : scanCursor.nextBatch(mc, radius, 400, chunksPerTick.get())) {
                ChunkPos pos = chunk.getPos();
                int count = ChunkScanHelper.countBlocksInChunk(chunk, StashFinderModule::isStorage);
                int need = sensitivity.get().scale(threshold.get());
                boolean sus = count >= need;
                // Confidence gate: a lone chest in a natural chunk (mineshaft/ruin) shouldn't flag.
                if (sus && count < need * 2) {
                    com.autism.seedcracker.finder.BaseConfidence.Result conf =
                        com.autism.seedcracker.finder.BaseConfidence.score(chunk);
                    if (conf.score() < minConfidence.get()) sus = false;
                }
                if (sus) {
                    flagged.add(pos);
                    if (notified.add(pos)) {
                        onNewFlag(pos, count);
                    }
                } else {
                    flagged.remove(pos);
                }
            }
        }
        // Drop flags that scrolled out of range.
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    // ========================================================================
    // DONUT mode: the DonutSMP base profile every donut client converged on
    // (Radium/4E RTPBaseFinder + TunnelBaseWater thresholds): spawners and
    // bulk storage BELOW Y0 together. A spawner + a few chests deep down is a
    // grinder base; 20+ chests alone is a stash room; both = jackpot.
    // ========================================================================
    private void scanDonut(Minecraft mc, int radius) {
        for (LevelChunk chunk : scanCursor.nextBatch(mc, radius, 400, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            int storageDeep = 0;
            boolean spawner = false;
            for (var be : chunk.getBlockEntities().values()) {
                if (be == null) continue;
                if (be instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity) {
                    spawner = true;
                    continue;
                }
                if (be.getBlockPos().getY() > 0) continue; // donut bases live below Y0
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE
                    .getKey(be.getType()).getPath();
                if (id.equals("chest") || id.equals("trapped_chest") || id.equals("barrel")
                    || id.equals("shulker_box") || id.equals("hopper")) storageDeep++;
            }
            // Radium/4E: spawner alone = flag; 20+ deep storage = flag; spawner + a few = flag.
            int bulk = sensitivity.get().scale(20);
            boolean sus = (spawner && storageDeep >= 3) || storageDeep >= bulk
                || (spawner && sensitivity.get() == com.autism.seedcracker.finder.FinderSensitivity.HIGH);
            if (sus) {
                flagged.add(pos);
                if (notified.add(pos)) {
                    onNewFlag(pos, storageDeep + (spawner ? 100 : 0));
                }
            } else {
                flagged.remove(pos);
            }
        }
    }

    // ========================================================================
    // SCORING mode (port of the CodeEngine "StashFinder" classifier).
    // Clusters storage blocks that are within 8 blocks of each other, then scores
    // each cluster from its composition + the surrounding environment to tell a
    // REAL player base apart from a FAKE/decoy stash.
    // ========================================================================
    private void scanScoring(Minecraft mc, List<LevelChunk> chunks) {
        // Gather every storage block in range.
        List<net.minecraft.core.BlockPos> storage = new java.util.ArrayList<>();
        for (LevelChunk chunk : chunks) {
            ChunkScanHelper.forEachBlockInChunk(chunk, s -> isStorage(s), p -> storage.add(p.immutable()));
        }
        if (storage.size() < 2) {
            flagged.clear();
            return;
        }

        // Union-find cluster by 8-block Manhattan distance.
        int n = storage.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;
        for (int i = 0; i < n; i++) {
            net.minecraft.core.BlockPos a = storage.get(i);
            for (int j = i + 1; j < n; j++) {
                net.minecraft.core.BlockPos b = storage.get(j);
                int man = Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
                if (man <= 8) union(parent, i, j);
            }
        }
        java.util.Map<Integer, List<net.minecraft.core.BlockPos>> clusters = new java.util.HashMap<>();
        for (int i = 0; i < n; i++) {
            clusters.computeIfAbsent(find(parent, i), k -> new java.util.ArrayList<>()).add(storage.get(i));
        }

        flagged.clear();
        for (List<net.minecraft.core.BlockPos> cluster : clusters.values()) {
            if (cluster.size() < 2) continue;
            ScoreResult res = scoreCluster(mc, cluster);
            // Sensitivity shifts the score gates: HIGH lowers them 15 (flags weaker clusters),
            // LOW raises them 15 (only clear-cut bases).
            int shift = switch (sensitivity.get()) { case HIGH -> -15; case MEDIUM -> 0; case LOW -> 15; };
            boolean isReal = res.score >= realThreshold.get() + shift;
            boolean isAmb = !isReal && res.score >= ambiguousThreshold.get() + shift;
            if (isReal || (isAmb && !realOnly.get())) {
                net.minecraft.core.BlockPos c = res.centre;
                ChunkPos cp = new ChunkPos(c.getX() >> 4, c.getZ() >> 4);
                flagged.add(cp);
                if (notified.add(cp)) {
                    onNewScoreFlag(c, res.score, cluster.size(), isReal);
                }
            }
        }
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) { parent[i] = parent[parent[i]]; i = parent[i]; }
        return i;
    }

    private static void union(int[] parent, int i, int j) {
        parent[find(parent, i)] = find(parent, j);
    }

    private record ScoreResult(int score, net.minecraft.core.BlockPos centre) {}

    /** CodeEngine scoring rules, faithful to StashFinder.stashFinderaOf. */
    private ScoreResult scoreCluster(Minecraft mc, List<net.minecraft.core.BlockPos> cluster) {
        int size = cluster.size();
        long sx = 0, sy = 0, sz = 0;
        Set<Integer> ys = new HashSet<>();
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (net.minecraft.core.BlockPos p : cluster) {
            sx += p.getX(); sy += p.getY(); sz += p.getZ();
            ys.add(p.getY());
            if (p.getY() < minY) minY = p.getY();
            if (p.getY() > maxY) maxY = p.getY();
        }
        // Multi-kind: distinct storage block types in the cluster.
        Set<Block> blockKinds = new HashSet<>();
        for (net.minecraft.core.BlockPos p : cluster) blockKinds.add(mc.level.getBlockState(p).getBlock());
        net.minecraft.core.BlockPos centre = new net.minecraft.core.BlockPos(
            (int) Math.floorDiv(sx, size), (int) Math.floorDiv(sy, size), (int) Math.floorDiv(sz, size));

        EnvScan env = scanEnv(mc, centre);
        int score = 0;
        if (blockKinds.size() >= 2) score += 30;                       // multi-kind storage
        score += Math.min(40, env.beds * 20);                          // beds nearby
        score += Math.min(45, env.workstations * 15);                  // workstations
        if (env.redstone) score += 25;                                 // redstone
        if (env.railPortal) score += 20;                               // rails / portal
        if (env.fluid) score += 10;                                    // fluid
        if (ys.size() >= 2 && maxY - minY >= 3) score += 10;           // multi-level
        if (env.artificialWall) score += 15;                           // artificial walls
        if (blockKinds.size() == 1 && size >= 6 && size <= 64) score -= 15; // mono-kind pile
        if (gridSpaced(cluster)) score -= 20;                          // evenly grid-spaced (decoy)
        int y0 = cluster.get(0).getY();
        if (y0 % 8 == 0 || y0 == 0 || y0 == 64 || y0 == -59) score -= 25;   // round-Y (generated)
        if (env.workstations == 0) score -= 20;                        // no workstations
        if (env.beds == 0) score -= 15;                                // no beds
        if (env.natural) score -= 20;                                  // mostly natural blocks
        if (env.shallow) score -= 15;                                  // close under surface
        return new ScoreResult(score, centre);
    }

    /** True if 4+ blocks share one Y and are evenly spaced on both X and Z (decoy grid). */
    private static boolean gridSpaced(List<net.minecraft.core.BlockPos> cluster) {
        if (cluster.size() < 4) return false;
        int y = cluster.get(0).getY();
        for (net.minecraft.core.BlockPos p : cluster) if (p.getY() != y) return false;
        List<Integer> xs = new java.util.ArrayList<>();
        List<Integer> zs = new java.util.ArrayList<>();
        for (net.minecraft.core.BlockPos p : cluster) { xs.add(p.getX()); zs.add(p.getZ()); }
        return evenlySpaced(xs) && evenlySpaced(zs);
    }

    private static boolean evenlySpaced(List<Integer> vals) {
        Set<Integer> uniq = new HashSet<>(vals);
        if (uniq.size() < 2) return false;
        List<Integer> sorted = new java.util.ArrayList<>(uniq);
        java.util.Collections.sort(sorted);
        int step = sorted.get(1) - sorted.get(0);
        if (step <= 0) return false;
        for (int i = 2; i < sorted.size(); i++) {
            if (sorted.get(i) - sorted.get(i - 1) != step) return false;
        }
        return true;
    }

    private static final class EnvScan {
        int beds, workstations;
        boolean redstone, railPortal, fluid, natural, artificialWall, shallow;
    }

    /** Scan a 33x33x33 box around the cluster centre for environment signals. */
    private EnvScan scanEnv(Minecraft mc, net.minecraft.core.BlockPos centre) {
        EnvScan e = new EnvScan();
        int natural = 0, wall = 0, total = 0;
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        int minYe = Math.max(mc.level.getMinY(), centre.getY() - 16);
        int maxYe = Math.min(mc.level.getMaxY(), centre.getY() + 16);
        for (int x = centre.getX() - 16; x <= centre.getX() + 16; x++) {
            for (int z = centre.getZ() - 16; z <= centre.getZ() + 16; z++) {
                for (int y = minYe; y <= maxYe; y++) {
                    mp.set(x, y, z);
                    BlockState st;
                    try { st = mc.level.getBlockState(mp); } catch (Throwable t) { continue; }
                    Block b = st.getBlock();
                    if (b instanceof net.minecraft.world.level.block.BedBlock) {
                        int man = Math.abs(x - centre.getX()) + Math.abs(y - centre.getY()) + Math.abs(z - centre.getZ());
                        if (man <= 32) e.beds++;
                    }
                    if (isWorkstation(b)) e.workstations++;
                    if (isRedstone(b)) e.redstone = true;
                    if (isRailPortal(b)) e.railPortal = true;
                    if (!st.getFluidState().isEmpty()) e.fluid = true;
                    if (isArtificialWall(b)) wall++;
                    if (isNatural(b)) natural++;
                    total++;
                }
            }
        }
        if (total > 0) {
            e.natural = ((double) natural / total) > 0.9 && wall < 4;
            e.artificialWall = wall >= 8;
        }
        int surface = surfaceY(mc, centre.getX(), centre.getZ());
        if (surface != Integer.MIN_VALUE && surface - centre.getY() < 8) e.shallow = true;
        return e;
    }

    private static int surfaceY(Minecraft mc, int x, int z) {
        net.minecraft.core.BlockPos.MutableBlockPos mp = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int y = mc.level.getMaxY(); y >= mc.level.getMinY(); y--) {
            mp.set(x, y, z);
            BlockState st;
            try { st = mc.level.getBlockState(mp); } catch (Throwable t) { return Integer.MIN_VALUE; }
            if (!st.isAir() && st.getFluidState().isEmpty()) return y;
        }
        return Integer.MIN_VALUE;
    }

    private static boolean isWorkstation(Block b) {
        return b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b == Blocks.BLAST_FURNACE
            || b == Blocks.SMOKER || b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL
            || b == Blocks.DAMAGED_ANVIL || b == Blocks.ENCHANTING_TABLE || b == Blocks.BREWING_STAND
            || b == Blocks.GRINDSTONE || b == Blocks.CARTOGRAPHY_TABLE || b == Blocks.SMITHING_TABLE
            || b == Blocks.LOOM || b == Blocks.STONECUTTER || b == Blocks.FLETCHING_TABLE || b == Blocks.LECTERN;
    }

    private static boolean isRedstone(Block b) {
        return b == Blocks.REPEATER || b == Blocks.COMPARATOR || b == Blocks.OBSERVER
            || b == Blocks.PISTON || b == Blocks.STICKY_PISTON || b == Blocks.HOPPER
            || b == Blocks.DISPENSER || b == Blocks.DROPPER || b == Blocks.REDSTONE_WIRE
            || b == Blocks.REDSTONE_TORCH || b == Blocks.REDSTONE_LAMP || b == Blocks.REDSTONE_BLOCK;
    }

    private static boolean isRailPortal(Block b) {
        return b == Blocks.RAIL || b == Blocks.POWERED_RAIL || b == Blocks.DETECTOR_RAIL
            || b == Blocks.ACTIVATOR_RAIL || b == Blocks.NETHER_PORTAL || b == Blocks.END_PORTAL
            || b == Blocks.END_PORTAL_FRAME || b == Blocks.END_GATEWAY;
    }

    private static boolean isNatural(Block b) {
        return b == Blocks.STONE || b == Blocks.DEEPSLATE || b == Blocks.DIRT || b == Blocks.GRASS_BLOCK
            || b == Blocks.PODZOL || b == Blocks.GRAVEL || b == Blocks.SAND || b == Blocks.SANDSTONE
            || b == Blocks.NETHERRACK || b == Blocks.BASALT || b == Blocks.BLACKSTONE || b == Blocks.END_STONE
            || b == Blocks.WATER || b == Blocks.LAVA || b == Blocks.AIR || b == Blocks.CAVE_AIR || b == Blocks.VOID_AIR;
    }

    private static boolean isArtificialWall(Block b) {
        return b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE || b == Blocks.STONE_BRICKS
            || b == Blocks.MOSSY_STONE_BRICKS || b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS
            || b == Blocks.BIRCH_PLANKS || b == Blocks.JUNGLE_PLANKS || b == Blocks.ACACIA_PLANKS
            || b == Blocks.DARK_OAK_PLANKS || b == Blocks.MANGROVE_PLANKS || b == Blocks.CHERRY_PLANKS
            || b == Blocks.CRIMSON_PLANKS || b == Blocks.WARPED_PLANKS || b == Blocks.BAMBOO_PLANKS
            || b == Blocks.BRICKS || b == Blocks.DEEPSLATE_BRICKS || b == Blocks.DEEPSLATE_TILES
            || b == Blocks.POLISHED_DEEPSLATE || b == Blocks.SMOOTH_STONE || b == Blocks.QUARTZ_BLOCK
            || b == Blocks.OAK_LOG || b == Blocks.SPRUCE_LOG || b == Blocks.BIRCH_LOG;
    }

    private void onNewScoreFlag(net.minecraft.core.BlockPos centre, int score, int size, boolean isReal) {
        recordHeat(centre.getX(), centre.getZ());
        if (!notify.get()) return;
        String band = isReal ? "REAL base" : "ambiguous";
        String msg = band + " (score " + score + ", " + size + " storage) at X:" + centre.getX() + " Y:" + centre.getY() + " Z:" + centre.getZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§6[StashFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    private static boolean isStorage(BlockState state) {
        return STORAGE_BLOCKS.contains(state.getBlock());
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, int count) {
        recordHeat(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8);
        if (!notify.get()) return;
        String msg = "Stash chunk (" + count + " storage) at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§6[StashFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }

    /** Record a find into the heatmap (auto) or queue it for manual confirm, per the setting. */
    private void recordHeat(int blockX, int blockZ) {
        int cb = com.autism.seedcracker.modules.RegionMapModule.cellBlocks();
        if (com.autism.seedcracker.modules.RegionMapModule.isAutoHeatmap()) {
            com.autism.seedcracker.finder.BaseHeatTracker.recordFind(blockX, blockZ, cb);
        } else {
            com.autism.seedcracker.finder.BaseHeatTracker.queueFind(blockX, blockZ, cb);
            if (notify.get()) {
                AutismClientMessaging.sendPrefixed("§7[Heatmap] Queued ("
                    + com.autism.seedcracker.finder.BaseHeatTracker.pendingCount() + " pending). /heatconfirm to add.");
            }
        }
    }
}
