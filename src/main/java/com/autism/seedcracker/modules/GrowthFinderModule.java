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

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public GrowthFinderModule() {
        super(SeedcrackerAddon.ID + ":z-growth-finder", "Growth Finder",
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
            int count = ChunkScanHelper.countBlocksInChunk(chunk, this::isGrowth);
            if (count >= threshold.get()) {
                flagged.add(pos);
                if (notified.add(pos)) {
                    onNewFlag(pos, count);
                }
            } else {
                flagged.remove(pos);
            }
        }
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    /** Combined vegetation/growth predicate honouring each per-type toggle. */
    private boolean isGrowth(BlockState state) {
        if (state.isAir()) return false;

        if (vines.get() && (state.is(Blocks.VINE)
            || state.is(Blocks.CAVE_VINES) || state.is(Blocks.CAVE_VINES_PLANT)
            || state.is(Blocks.WEEPING_VINES) || state.is(Blocks.WEEPING_VINES_PLANT)
            || state.is(Blocks.TWISTING_VINES) || state.is(Blocks.TWISTING_VINES_PLANT))) return true;
        if (berries.get() && state.is(Blocks.SWEET_BERRY_BUSH)) return true;
        if (dripstone.get() && (state.is(Blocks.POINTED_DRIPSTONE) || state.is(Blocks.DRIPSTONE_BLOCK))) return true;
        return false;
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, int count) {
        if (!notify.get()) return;
        String msg = "Growth chunk (" + count + " blocks) at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§2[GrowthFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
