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
    private final IntSetting threshold = add(new IntSetting(
            "threshold", "Threshold", 10, 1, 100, 1)
        .description("Storage blocks in a chunk needed to flag it as a stash.")
        .group("General"));
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

    /** Chunks currently over the threshold. */
    private final Set<ChunkPos> flagged = new HashSet<>();
    /** Chunks already announced (so we only notify once per chunk). */
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public StashFinderModule() {
        super(SeedcrackerAddon.ID + ":z-stash-finder", "Stash Finder",
            "Flags chunks dense with chests/hoppers/shulkers - likely hidden player stashes.");
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
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-stash-finder");
    }

    @Override
    public void onGameLeft() {
        setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        // Throttle the full scan to roughly every 8 ticks.
        tickCounter++;
        if (tickCounter % 8 == 0) {
            scan(mc);
        }

        // Feed the renderer every tick so markers stay alive.
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-stash-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            int count = ChunkScanHelper.countBlocksInChunk(chunk, StashFinderModule::isStorage);
            if (count >= threshold.get()) {
                flagged.add(pos);
                if (notified.add(pos)) {
                    onNewFlag(pos, count);
                }
            } else {
                flagged.remove(pos);
            }
        }
        // Drop flags that scrolled out of range.
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    private static boolean isStorage(BlockState state) {
        return STORAGE_BLOCKS.contains(state.getBlock());
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos, int count) {
        if (!notify.get()) return;
        String msg = "Stash chunk (" + count + " storage) at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§6[StashFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
