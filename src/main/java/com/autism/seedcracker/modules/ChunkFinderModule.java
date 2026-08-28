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
import net.minecraft.world.level.block.BeehiveBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Chunk Finder.
 *
 * Detects chunks containing a full bee nest / beehive (honey level == {@value #FULL_HONEY}).
 * Full hives are a reliable sign of player activity / a tended base. Flagged chunks are drawn by
 * the shared {@link ChunkFlagRenderer}, with an optional tracer and toast.
 *
 * Clean-room port of the Zelith "ChunkFinder" module (bee-nest-at-honey-5 detector) against the
 * AUTISM module API.
 */
public final class ChunkFinderModule extends Module {

    private static final int FULL_HONEY = 5;

    // ---- settings ----
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for full bee nests.")
        .group("General"));
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
        .description("Toast + chat ping when a full hive chunk is found.")
        .group("General"));
    private final BoolSetting notifySound = add(new BoolSetting(
            "notification-sound", "Notification sound", true)
        .description("Play a sound when a full hive chunk is found.")
        .group("General")
        .visibleWhen(() -> notify.get()));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public ChunkFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-chunk-finder", "Chunk Finder", category,
            "Flags chunks with a full bee nest/hive (honey level 5) - a sign of tended bases.");
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

        tickCounter++;
        if (tickCounter % 8 == 0) {
            scan(mc);
        }
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-chunk-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            boolean hasFullHive = ChunkScanHelper.countBlocksInChunk(chunk, ChunkFinderModule::isFullHive) > 0;
            if (hasFullHive) {
                flagged.add(pos);
                if (notified.add(pos)) {
                    onNewFlag(pos);
                }
            } else {
                flagged.remove(pos);
            }
        }
        int r = radius + 2;
        flagged.removeIf(p -> tooFar(p, playerChunk, r));
        notified.removeIf(p -> tooFar(p, playerChunk, r));
    }

    /** True for a bee nest or beehive at maximum honey level. */
    private static boolean isFullHive(BlockState state) {
        if (!state.is(Blocks.BEE_NEST) && !state.is(Blocks.BEEHIVE)) return false;
        return state.hasProperty(BeehiveBlock.HONEY_LEVEL)
            && state.getValue(BeehiveBlock.HONEY_LEVEL) == FULL_HONEY;
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        String msg = "Full beehive at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§a[ChunkFinder] §f" + msg);
        if (notifySound.get()) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            }
        }
    }
}
