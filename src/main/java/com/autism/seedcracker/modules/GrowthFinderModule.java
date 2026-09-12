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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Growth Finder.
 *
 * Flags chunks dense with vegetation / growth - vines, cave vines, sweet-berry bushes and
 * dripstone - above a small threshold. Overgrown chunks often mark an old, long-loaded or
 * player-tended area. Each growth type is individually toggleable and the marker colour's alpha is
 * configurable.
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 *
 * Clean-room port of the Zelith "GrowthFinder" module against the AUTISM module API. The original
 * walked heightmaps and weighted several growth features; this port counts the same growth blocks
 * per chunk with per-type toggles and a single density threshold.
 */
public final class GrowthFinderModule extends Module {

    // ---- settings ----
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for growth.")
        .group("General"));
    private final IntSetting threshold = add(new IntSetting(
            "threshold", "Threshold", 8, 1, 100, 1)
        .description("Growth blocks needed in a chunk to flag it.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "alpha", "Marker colour", 0x5040FF40)
        .description("Colour (with alpha) of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a growth chunk is found.")
        .group("General"));

    // ---- per-type toggles ----
    private final BoolSetting vines = add(new BoolSetting("render-vines", "Vines", true)
        .description("Count vines (regular + cave vines).").group("Types"));
    private final BoolSetting berries = add(new BoolSetting("render-berries", "Berries", true)
        .description("Count sweet-berry bushes.").group("Types"));
    private final BoolSetting dripstone = add(new BoolSetting("render-dripstone", "Dripstone", true)
        .description("Count pointed dripstone / dripstone blocks.").group("Types"));
    private final BoolSetting sourceTracking = add(new BoolSetting("source-tracking", "Source estimation", true)
        .description("Estimate the farm/source chunk from weighted growth (Xenon logic).")
        .group("General"));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public GrowthFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-growth-finder", "Growth Finder", category,
            "Flags chunks dense with vegetation/growth (vines, berries, dripstone) - overgrown, long-loaded areas.");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-growth-finder");
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
        if (tickCounter % 8 == 0) {
            scan(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-growth-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            Suspicion s = analyzeChunk(mc, chunk, pos);
            if (s.score >= threshold.get()) {
                flagged.add(pos);
                if (notified.add(pos)) {
                    onNewFlag(pos, s);
                }
            } else {
                flagged.remove(pos);
            }
        }
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    /** Suspicion result for a chunk: a weighted score + the estimated farm-source position. */
    private static final class Suspicion {
        int score;
        int maxVine;
        net.minecraft.core.BlockPos source;
    }

    /**
     * Xenon-style analysis: instead of just counting growth blocks, it weights them by how
     * "tended" they look - long vines count heavily (a long vine = an old, loaded farm), berries
     * and dripstone count a little - and produces a suspicion score plus a weighted centroid that
     * points at the likely farm/source location.
     */
    private Suspicion analyzeChunk(Minecraft mc, LevelChunk chunk, ChunkPos pos) {
        Suspicion out = new Suspicion();
        int baseX = pos.getMinBlockX();
        int baseZ = pos.getMinBlockZ();
        int minY = mc.level.getMinY();
        int maxY = mc.level.getMaxY() - 1;

        double vineWeight = 0, dripWeight = 0, berryWeight = 0;
        double sumX = 0, sumZ = 0, sumW = 0;
        int maxVine = 0;

        net.minecraft.core.BlockPos.MutableBlockPos m = new net.minecraft.core.BlockPos.MutableBlockPos();
        Set<Long> vineTops = new HashSet<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    m.set(baseX + x, y, baseZ + z);
                    BlockState state = chunk.getBlockState(m);
                    if (state.isAir()) continue;

                    // Long vine: weight = its length (a long vine signals an old loaded farm).
                    if (vines.get() && isVine(state)) {
                        long key = m.asLong();
                        if (!vineTops.contains(key) && !isVine(chunk.getBlockState(m.above()))) {
                            vineTops.add(key);
                            int len = 1;
                            net.minecraft.core.BlockPos cur = m.below();
                            while (isVine(mc.level.getBlockState(cur))) { len++; cur = cur.below(); }
                            if (len >= 6) {
                                vineWeight += len;
                                if (len > maxVine) maxVine = len;
                                double w = len;
                                sumX += m.getX() * w; sumZ += m.getZ() * w; sumW += w;
                            }
                        }
                    }

                    // Dripstone: small fixed weight.
                    if (dripstone.get() && (state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.DRIPSTONE_BLOCK))) {
                        dripWeight += 2.5;
                        sumX += m.getX() * 2.5; sumZ += m.getZ() * 2.5; sumW += 2.5;
                    }

                    // Berries: tiny weight.
                    if (berries.get() && state.is(Blocks.SWEET_BERRY_BUSH)) {
                        berryWeight += 1.0;
                        sumX += m.getX() * 1.0; sumZ += m.getZ() * 1.0; sumW += 1.0;
                    }
                }
            }
        }

        out.maxVine = maxVine;
        out.score = (int) Math.round(vineWeight * 0.5 + dripWeight * 0.75 + berryWeight);
        if (sourceTracking.get() && sumW > 0) {
            out.source = new net.minecraft.core.BlockPos(
                (int) Math.round(sumX / sumW), (int) mc.player.getY(), (int) Math.round(sumZ / sumW));
        } else {
            out.source = new net.minecraft.core.BlockPos(pos.getMinBlockX() + 8, (int) mc.player.getY(), pos.getMinBlockZ() + 8);
        }
        return out;
    }

    private boolean isVine(BlockState state) {
        return state.is(Blocks.VINE) || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)
            || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)
            || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT);
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, Suspicion s) {
        if (!notify.get()) return;
        int sx = s.source.getX(), sz = s.source.getZ();
        String msg = "Growth (score " + s.score + (s.maxVine > 0 ? ", max vine " + s.maxVine : "") + ") near X:" + sx + " Z:" + sz;
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§2[GrowthFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
