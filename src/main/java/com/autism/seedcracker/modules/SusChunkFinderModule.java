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
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Sus Chunk Finder.
 *
 * Counts "suspicious" blocks in each chunk - things that rarely generate naturally in a way that
 * looks like player activity or an odd/modified chunk: kelp, cave vines, regular vines, amethyst
 * clusters, bamboo, full bee nests and rotated (non-vertical) deepslate. Each type is individually
 * toggleable; when a chunk's total count reaches the configured sensitivity it is flagged.
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 *
 * Clean-room port of the Zelith "SusChunkFinder" module against the AUTISM module API. The
 * original used per-block structural heuristics (e.g. "kelp with more kelp above"); here each type
 * is matched by a straightforward block-state predicate, keeping the module readable while
 * preserving the per-type toggles and sensitivity threshold.
 */
public final class SusChunkFinderModule extends Module {

    private static final int FULL_HONEY = 5;

    // ---- settings ----
    private final IntSetting scanRadius = add(new IntSetting(
            "simulation-distance", "Simulation distance (chunks)", 4, 2, 16, 1)
        .description("Chunk bubble around the player scanned for suspicious blocks.")
        .group("General"));
    private final IntSetting sensitivity = add(new IntSetting(
            "sensitivity", "Sensitivity", 3, 1, 20, 1)
        .description("Suspicious blocks needed in a chunk to flag it.")
        .group("General"));
    private final IntSetting scanInterval = add(new IntSetting(
            "scan-interval", "Scan interval (ticks)", 8, 2, 40, 1)
        .description("Ticks between scan steps. Higher = less CPU, slower detection.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "alpha", "Marker colour", 0x50FF5050)
        .description("Colour (with alpha) of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a suspicious chunk is found.")
        .group("General"));

    // ---- per-type toggles ----
    private final BoolSetting kelp = add(new BoolSetting("kelp", "Kelp", true)
        .description("Count kelp / kelp plants.").group("Types"));
    private final BoolSetting caveVines = add(new BoolSetting("cave-vines", "Cave Vines", true)
        .description("Count cave vines / glow-berry vines.").group("Types"));
    private final BoolSetting vines = add(new BoolSetting("vines", "Vines", true)
        .description("Count regular vines.").group("Types"));
    private final BoolSetting amethyst = add(new BoolSetting("amethyst", "Amethyst", true)
        .description("Count amethyst clusters.").group("Types"));
    private final BoolSetting bamboo = add(new BoolSetting("bamboo", "Bamboo", true)
        .description("Count bamboo.").group("Types"));
    private final BoolSetting beeNest = add(new BoolSetting("bee-nest", "Bee Nest", true)
        .description("Count full bee nests/hives (honey level 5).").group("Types"));
    private final BoolSetting rotatedDeepslate = add(new BoolSetting("rotated-deepslate", "Rotated Deepslate", true)
        .description("Count deepslate rotated off the vertical axis (player-placed).").group("Types"));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    /** Rolling queue of chunks left to scan this pass (spread across ticks to avoid frame spikes). */
    private final java.util.ArrayDeque<LevelChunk> scanQueue = new java.util.ArrayDeque<>();
    /** Chunks that were queued this pass (so we don't queue the same chunk twice). */
    private final Set<ChunkPos> queued = new HashSet<>();
    /** Toggles snapshotted once per pass so the hot predicate reads plain booleans, not settings. */
    private boolean fKelp, fCaveVines, fVines, fAmethyst, fBamboo, fBeeNest, fRotatedDeepslate;
    private int scanIntervalCached = 8;
    private int tickCounter = 0;
    /** How many chunks to scan per scan tick. Small = smooth FPS, slower full pass. */
    private static final int CHUNKS_PER_TICK = 1;

    public SusChunkFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-sus-chunk-finder", "Sus Chunk Finder", category,
            "Flags chunks dense with suspicious blocks (kelp, vines, amethyst, bamboo, ...) - likely player activity.");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        scanQueue.clear();
        queued.clear();
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        scanQueue.clear();
        queued.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-sus-chunk-finder");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        tickCounter++;
        if (tickCounter % scanIntervalCached == 0) {
            scanStep(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-sus-chunk-finder", flagged, color.get(), tracer.get());
    }

    /**
     * Incremental scan: refills the queue when empty (snapshotting toggles + rebuilding the chunk
     * bubble), then scans only {@link #CHUNKS_PER_TICK} chunk(s). This spreads the expensive block
     * scan across many ticks so a single frame never does a whole-bubble scan (the cause of the
     * 1 FPS drops). Already-flagged chunks are skipped (no need to re-scan a known match).
     */
    private void scanStep(Minecraft mc) {
        if (scanQueue.isEmpty()) {
            // New pass: snapshot toggles + scan interval, then queue the chunks around the player.
            fKelp = kelp.get();
            fCaveVines = caveVines.get();
            fVines = vines.get();
            fAmethyst = amethyst.get();
            fBamboo = bamboo.get();
            fBeeNest = beeNest.get();
            fRotatedDeepslate = rotatedDeepslate.get();
            scanIntervalCached = Math.max(2, scanInterval.get());

            List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
            for (LevelChunk c : chunks) {
                ChunkPos p = c.getPos();
                if (flagged.contains(p)) continue;   // already a known match; skip re-scan
                if (queued.add(p)) scanQueue.add(c);
            }
            pruneFar(mc);
            if (scanQueue.isEmpty()) return; // nothing new to scan this pass
        }

        int threshold = sensitivity.get();
        for (int i = 0; i < CHUNKS_PER_TICK && !scanQueue.isEmpty(); i++) {
            LevelChunk chunk = scanQueue.poll();
            if (chunk == null) continue;
            ChunkPos pos = chunk.getPos();
            queued.remove(pos);
            int count = ChunkScanHelper.countBlocksInChunk(chunk, this::isSuspicious, threshold);
            if (count >= threshold) {
                flagged.add(pos);
                if (notified.add(pos)) onNewFlag(pos, count);
            } else {
                flagged.remove(pos);
            }
        }
    }

    /** Drop flags/notifications that scrolled out of range. */
    private void pruneFar(Minecraft mc) {
        ChunkPos playerChunk = mc.player.chunkPosition();
        int r = scanRadius.get() + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    /** Combined predicate honouring each per-type toggle (reads snapshotted booleans). */
    private boolean isSuspicious(BlockState state) {
        if (state.isAir()) return false;
        if (fKelp && (state.is(Blocks.KELP) || state.is(Blocks.KELP_PLANT))) return true;
        if (fCaveVines && (state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT))) return true;
        if (fVines && state.is(Blocks.VINE)) return true;
        if (fAmethyst && state.is(Blocks.AMETHYST_CLUSTER)) return true;
        if (fBamboo && (state.is(Blocks.BAMBOO) || state.is(Blocks.BAMBOO_SAPLING))) return true;
        if (fBeeNest && isFullHive(state)) return true;
        if (fRotatedDeepslate && isRotatedDeepslate(state)) return true;
        return false;
    }

    private static boolean isFullHive(BlockState state) {
        if (!state.is(Blocks.BEE_NEST) && !state.is(Blocks.BEEHIVE)) return false;
        return state.hasProperty(BeehiveBlock.HONEY_LEVEL)
            && state.getValue(BeehiveBlock.HONEY_LEVEL) == FULL_HONEY;
    }

    /** Deepslate whose pillar axis is horizontal (player-placed), as opposed to natural vertical. */
    private static boolean isRotatedDeepslate(BlockState state) {
        if (!state.is(Blocks.DEEPSLATE)) return false;
        if (!state.hasProperty(RotatedPillarBlock.AXIS)) return false;
        return state.getValue(RotatedPillarBlock.AXIS) != Direction.Axis.Y;
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, int count) {
        if (!notify.get()) return;
        String msg = "Sus chunk (" + count + " blocks) at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§d[SusChunkFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
