package com.autism.seedcracker.modules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import autismclient.util.AutismNotifications;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chunk Finder.
 *
 * Scans loaded chunks for several signs of player activity / modified terrain, and flags any
 * chunk that matches: rotated (player-placed) deepslate, unusually long dripstone or vines, kelp
 * that's all fully grown to the surface, diorite/granite/andesite "veins" enclosed in stone, and
 * long obsidian veins. Chunks with lots of dropped items or XP orbs (an active grinder/farm) are
 * skipped so it focuses on bases, not farms.
 *
 * Scanning runs on a background thread pool (chunk re-scans on an interval), so it stays cheap on
 * the render thread. Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 *
 * Port of the Xenon "ChunkFinder" module (multi-signal detector) to the AUTISM API (Mojang 26.2).
 */
public final class ChunkFinderModule extends Module {

    private static final int THREADS = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
    private static final int MAX_CONCURRENT = 16;
    private static final long RESCAN_MS = 5000L;

    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned.")
        .group("General"));
    private final IntSetting rotatedThreshold = add(new IntSetting(
            "rotated-threshold", "Rotated deepslate", 1, 1, 20, 1)
        .description("Rotated (player-placed) deepslate blocks needed to flag.")
        .group("Signals"));
    private final BoolSetting detectVeins = add(new BoolSetting(
            "veins", "Diorite/obsidian veins", true)
        .description("Flag enclosed diorite/granite/andesite + long obsidian veins.")
        .group("Signals"));
    private final BoolSetting detectGrowth = add(new BoolSetting(
            "growth", "Long dripstone/vine + grown kelp", true)
        .description("Flag unusually long dripstone/vines and fully-grown kelp.")
        .group("Signals"));
    private final BoolSetting ignoreItemChunks = add(new BoolSetting(
            "ignore-item-chunks", "Skip farm chunks (items/XP)", true)
        .description("Skip chunks with many dropped items or XP orbs (active farms).")
        .group("Signals"));
    private final IntSetting maxItems = add(new IntSetting(
            "max-items", "Max items", 3, 0, 100, 1)
        .description("Dropped items in a chunk before it's treated as a farm and skipped.")
        .group("Signals"));
    private final IntSetting maxXP = add(new IntSetting(
            "max-xp", "Max XP orbs", 3, 0, 100, 1)
        .description("XP orbs in a chunk before it's treated as a farm and skipped.")
        .group("Signals"));
    private final BoolSetting ignorePlayerChunk = add(new BoolSetting(
            "ignore-player-chunk", "Ignore own chunk", true)
        .description("Don't flag the chunk you're standing in.")
        .group("Signals"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", true)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0xFF0A822D)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a suspicious chunk is found.")
        .group("General"));

    private final Set<ChunkPos> flagged = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> notified = ConcurrentHashMap.newKeySet();
    private final Map<ChunkPos, Long> scannedAt = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> itemCounts = new ConcurrentHashMap<>();
    private final Map<ChunkPos, Integer> xpCounts = new ConcurrentHashMap<>();
    private final AtomicInteger activeScans = new AtomicInteger(0);
    private ExecutorService pool;
    private volatile boolean scanning = false;
    private int tickCounter = 0;

    public ChunkFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-chunk-finder", "Chunk Finder", category,
            "Flags chunks with base signs (rotated deepslate, long dripstone/vines, grown kelp, veins).");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        scannedAt.clear();
        itemCounts.clear();
        xpCounts.clear();
        activeScans.set(0);
        tickCounter = 0;
        scanning = true;
        pool = Executors.newFixedThreadPool(THREADS, r -> {
            Thread t = new Thread(r, "ChunkFinder-Scan");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void onDisable() {
        scanning = false;
        if (pool != null) { pool.shutdownNow(); pool = null; }
        flagged.clear();
        notified.clear();
        scannedAt.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-chunk-finder");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Refresh entity activity counts each tick (cheap).
        if (ignoreItemChunks.get()) {
            itemCounts.clear();
            xpCounts.clear();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e instanceof ItemEntity) itemCounts.merge(e.chunkPosition(), 1, Integer::sum);
                else if (e instanceof ExperienceOrb) xpCounts.merge(e.chunkPosition(), 1, Integer::sum);
            }
        }

        tickCounter++;
        if (tickCounter % 6 == 0) {
            dispatchScans(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-chunk-finder", flagged, color.get(), tracer.get());
    }

    /** Queue background scans for in-range chunks that are due a (re)scan. */
    private void dispatchScans(Minecraft mc) {
        if (!scanning || pool == null) return;
        int radius = scanRadius.get();
        ChunkPos center = mc.player.chunkPosition();
        long now = System.currentTimeMillis();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = center.x() + dx;
                int cz = center.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                ChunkPos pos = new ChunkPos(cx, cz);
                Long last = scannedAt.get(pos);
                if (last != null && now - last < RESCAN_MS) continue;
                if (activeScans.get() >= MAX_CONCURRENT) return;

                scannedAt.put(pos, now);
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                activeScans.incrementAndGet();
                pool.submit(() -> {
                    try {
                        analyzeChunk(mc, chunk, pos, center);
                    } catch (Throwable ignored) {
                    } finally {
                        activeScans.decrementAndGet();
                    }
                });
            }
        }
    }

    private static final class Analysis {
        int rotated = 0;
        boolean longDripstone, longVine, grownKelp, dioriteVein, obsidianVein;
        BlockPos susPos;
    }

    /** Single-pass chunk scan for all the detection signals. Runs off-thread. */
    private void analyzeChunk(Minecraft mc, LevelChunk chunk, ChunkPos pos, ChunkPos playerChunk) {
        if (!scanning || mc.level == null) return;
        if (ignorePlayerChunk.get() && pos.equals(playerChunk)) return;
        if (ignoreItemChunks.get()) {
            if (itemCounts.getOrDefault(pos, 0) > maxItems.get()) return;
            if (xpCounts.getOrDefault(pos, 0) > maxXP.get()) return;
        }

        int startX = pos.getMinBlockX();
        int startZ = pos.getMinBlockZ();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY() - 1;
        Analysis a = new Analysis();
        Set<Long> veinVisited = new HashSet<>();
        Set<Long> obsidianVisited = new HashSet<>();
        Set<Long> vineTops = new HashSet<>();
        Set<Long> kelpBases = new HashSet<>();
        int kelpPlants = 0, fullKelp = 0;
        BlockPos firstKelp = null;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (!scanning) return;
                    m.set(startX + x, y, startZ + z);
                    BlockState state = chunk.getBlockState(m);

                    // Rotated deepslate (player-placed) at Y 0..16.
                    if (y >= 0 && y <= 16 && state.is(Blocks.DEEPSLATE) && state.hasProperty(BlockStateProperties.AXIS)) {
                        if (state.getValue(BlockStateProperties.AXIS) != Direction.Axis.Y) {
                            a.rotated++;
                            if (a.susPos == null) a.susPos = m.immutable();
                        }
                    }

                    // Diorite/granite/andesite vein enclosed in stone (length >= 5).
                    if (detectVeins.get() && !a.dioriteVein && isStoneOre(state)) {
                        long key = m.asLong();
                        if (!veinVisited.contains(key)) {
                            BlockPos imm = m.immutable();
                            int total = 1 + countRun(mc, imm, Direction.UP, this::isStoneOre) + countRun(mc, imm, Direction.DOWN, this::isStoneOre);
                            if (total >= 5) {
                                BlockPos start = imm.below(countRun(mc, imm, Direction.DOWN, this::isStoneOre));
                                boolean enclosed = true;
                                for (int i = 0; i < total; i++) {
                                    BlockPos bp = start.above(i);
                                    veinVisited.add(bp.asLong());
                                    if (!enclosedBy(mc, bp, Blocks.STONE)) enclosed = false;
                                }
                                if (enclosed) { a.dioriteVein = true; if (a.susPos == null) a.susPos = imm; }
                            }
                        }
                    }

                    // Obsidian vein (length >= 15, Y 15..63) enclosed by non-obsidian.
                    if (detectVeins.get() && !a.obsidianVein && y >= 15 && y <= 63 && state.is(Blocks.OBSIDIAN)) {
                        long key = m.asLong();
                        if (!obsidianVisited.contains(key)) {
                            BlockPos imm = m.immutable();
                            java.util.function.Predicate<BlockState> isObs = s -> s.is(Blocks.OBSIDIAN);
                            int total = 1 + countRun(mc, imm, Direction.UP, isObs) + countRun(mc, imm, Direction.DOWN, isObs);
                            if (total >= 15) {
                                BlockPos start = imm.below(countRun(mc, imm, Direction.DOWN, isObs));
                                boolean enclosed = true;
                                for (int i = 0; i < total; i++) {
                                    BlockPos bp = start.above(i);
                                    obsidianVisited.add(bp.asLong());
                                    if (!enclosedByNot(mc, bp, Blocks.OBSIDIAN)) enclosed = false;
                                }
                                if (enclosed) { a.obsidianVein = true; if (a.susPos == null) a.susPos = imm; }
                            }
                        }
                    }

                    // Long dripstone (>= 12) - top piece of a stalactite.
                    if (detectGrowth.get() && !a.longDripstone && state.is(Blocks.POINTED_DRIPSTONE)
                        && state.hasProperty(BlockStateProperties.VERTICAL_DIRECTION)
                        && state.getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN) {
                        if (!mc.level.getBlockState(m.above()).is(Blocks.POINTED_DRIPSTONE)) {
                            int len = 1;
                            BlockPos cur = m.below();
                            while (len < 63 && mc.level.getBlockState(cur).is(Blocks.POINTED_DRIPSTONE)
                                && mc.level.getBlockState(cur).getValue(BlockStateProperties.VERTICAL_DIRECTION) == Direction.DOWN) {
                                len++;
                                cur = cur.below();
                            }
                            if (len >= 12) { a.longDripstone = true; if (a.susPos == null) a.susPos = m.immutable(); }
                        }
                    }

                    // Long vine (>= 8, Y >= 40) - top piece.
                    if (detectGrowth.get() && !a.longVine && y >= 40 && state.is(Blocks.VINE)) {
                        long key = m.asLong();
                        if (!vineTops.contains(key) && !mc.level.getBlockState(m.above()).is(Blocks.VINE)) {
                            vineTops.add(key);
                            int len = 1;
                            BlockPos cur = m.below();
                            while (mc.level.getBlockState(cur).is(Blocks.VINE)) { len++; cur = cur.below(); }
                            if (len >= 8) { a.longVine = true; if (a.susPos == null) a.susPos = m.immutable(); }
                        }
                    }

                    // Grown kelp (>= 4 kelp plants that all reach the surface).
                    if (detectGrowth.get() && (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT))) {
                        long key = m.asLong();
                        if (!kelpBases.contains(key)) {
                            BlockState below = mc.level.getBlockState(m.below());
                            if (!below.is(Blocks.KELP) && !below.is(Blocks.KELP_PLANT)) {
                                kelpBases.add(key);
                                if (firstKelp == null) firstKelp = m.immutable();
                                BlockPos cur = m.above();
                                boolean reachedSurface = false;
                                while (cur.getY() <= maxY) {
                                    BlockState cs = mc.level.getBlockState(cur);
                                    if (cs.is(Blocks.KELP) || cs.is(Blocks.KELP_PLANT)) { cur = cur.above(); continue; }
                                    if (cs.getFluidState().isEmpty()) reachedSurface = true;
                                    break;
                                }
                                kelpPlants++;
                                if (reachedSurface) fullKelp++;
                            }
                        }
                    }
                }
            }
        }

        if (kelpPlants >= 4 && kelpPlants == fullKelp) {
            a.grownKelp = true;
            if (a.susPos == null) a.susPos = firstKelp;
        }

        evaluate(mc, pos, a);
    }

    private void evaluate(Minecraft mc, ChunkPos pos, Analysis a) {
        List<String> reasons = new ArrayList<>();
        if (a.rotated >= rotatedThreshold.get()) reasons.add("Rotated:" + a.rotated);
        if (a.longDripstone) reasons.add("LongDripstone");
        if (a.longVine) reasons.add("LongVine");
        if (a.grownKelp) reasons.add("GrownKelp");
        if (a.dioriteVein) reasons.add("DioriteVein");
        if (a.obsidianVein) reasons.add("ObsidianVein");

        if (!reasons.isEmpty()) {
            if (flagged.add(pos) && notified.add(pos)) {
                int x = a.susPos != null ? a.susPos.getX() : pos.getMinBlockX() + 8;
                int z = a.susPos != null ? a.susPos.getZ() : pos.getMinBlockZ() + 8;
                String r = String.join(" ", reasons);
                mc.execute(() -> onNewFlag(pos, r, x, z));
            }
        }
    }

    private boolean isStoneOre(BlockState s) {
        return s.is(Blocks.DIORITE) || s.is(Blocks.GRANITE) || s.is(Blocks.ANDESITE);
    }

    private int countRun(Minecraft mc, BlockPos from, Direction dir, java.util.function.Predicate<BlockState> pred) {
        int count = 0;
        BlockPos.MutableBlockPos m = from.mutable();
        while (count <= 20) {
            m.move(dir);
            if (!pred.test(mc.level.getBlockState(m))) break;
            count++;
        }
        return count;
    }

    private boolean enclosedBy(Minecraft mc, BlockPos pos, net.minecraft.world.level.block.Block block) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (!mc.level.getBlockState(pos.relative(d)).is(block)) return false;
        }
        return true;
    }

    private boolean enclosedByNot(Minecraft mc, BlockPos pos, net.minecraft.world.level.block.Block block) {
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (mc.level.getBlockState(pos.relative(d)).is(block)) return false;
        }
        return true;
    }

    private void onNewFlag(ChunkPos pos, String reasons, int x, int z) {
        if (!notify.get()) return;
        String msg = reasons + " (X:" + x + " Z:" + z + ")";
        AutismNotifications.warning("Chunk: " + msg);
        AutismClientMessaging.sendPrefixed("§6[ChunkFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.9f);
    }
}
