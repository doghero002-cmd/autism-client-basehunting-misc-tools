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
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Activity Finder.
 *
 * A y-level-gated activity detector. The Zelith original listened to network packets, pulled block
 * positions out of them via reflection and flagged chunks below a configurable Y level. This
 * clean-room port keeps the essence - "flag chunks showing block-entity activity at or below a Y
 * cut-off" - but reads it from loaded chunk data instead of packet reflection: any chunk with one
 * or more block entities (chests, furnaces, hoppers, spawners, beehives, ... - i.e. signs of a
 * worked area) at or below the configured Y level is flagged.
 *
 * Flagged chunks are drawn by the shared {@link ChunkFlagRenderer}.
 */
public final class ActivityFinderModule extends Module {

    // ---- settings ----
    private final IntSetting yLevel = add(new IntSetting(
            "y-level", "Y level", 16, -64, 320, 1)
        .description("Only flag chunks whose block-entity activity is at or below this Y level.")
        .group("General"));
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 12, 1)
        .description("Chunk bubble around the player scanned for activity.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", false)
        .description("Toast + chat ping when activity is found.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "color", "Chunk colour", 0xB4FFDC00)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", false)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public ActivityFinderModule() {
        super(SeedcrackerAddon.ID + ":z-activity-finder", "Activity Finder",
            "Flags chunks with block-entity activity at or below a Y level - signs of a worked area.");
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
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-activity-finder");
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
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-activity-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();
        int yGate = yLevel.get();

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            boolean active = hasActivityAtOrBelow(chunk, yGate);
            if (active) {
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

    /** True when the chunk has any block entity at or below {@code yGate}. */
    private static boolean hasActivityAtOrBelow(LevelChunk chunk, int yGate) {
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be == null) continue;
            BlockPos p = be.getBlockPos();
            if (p != null && p.getY() <= yGate) return true;
        }
        return false;
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        String msg = "Activity detected in chunk " + pos.x() + ", " + pos.z() + " (Y<=" + yLevel.get() + ")";
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§e[ActivityFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
