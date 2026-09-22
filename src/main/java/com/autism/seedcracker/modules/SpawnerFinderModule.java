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
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Spawner Finder.
 *
 * Flags any chunk that contains a monster-spawner block entity (a dungeon / spawner farm, i.e.
 * strong evidence of player structures). Detection looks at the chunk's block-entity map for a
 * {@link SpawnerBlockEntity}. Flagged chunks are drawn by the shared {@link ChunkFlagRenderer},
 * with an optional tracer and toast.
 *
 * Clean-room port of the Zelith "SpawnerFinder" module against the AUTISM module API.
 */
public final class SpawnerFinderModule extends Module {

    // ---- settings ----
    private final IntSetting scanRadius = add(new IntSetting(
            "scan-radius", "Scan radius (chunks)", 4, 1, 8, 1)
        .description("Chunk bubble around the player scanned for spawners.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting(
            "spawner-color", "Spawner colour", 0x64FF0000)
        .description("Colour of the flagged chunk marker.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting(
            "tracer", "Tracer", true)
        .description("Draw a tracer line from the camera to each flagged chunk.")
        .group("Render"));
    private final IntSetting chunksPerTick = add(new IntSetting(
            "chunks-per-tick", "Chunks per tick", 1, 1, 32, 1)
        .description("How many chunks to scan per tick. 1 = smoothest FPS, higher = faster full sweep.")
        .group("Performance"));
    private final com.autism.seedcracker.finder.ScanCursor scanCursor = new com.autism.seedcracker.finder.ScanCursor();
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a spawner chunk is found.")
        .group("General"));
    private final BoolSetting activatedDetector = add(new BoolSetting(
            "activated-detector", "Activated detector", true)
        .description("Also flag ACTIVATED spawners (spawn delay ticking = a player has been within 16 blocks - Shoreline). Strong evidence of a player base/farm.")
        .group("General"));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private final Set<net.minecraft.core.BlockPos> activatedNotified = new HashSet<>();
    private int tickCounter = 0;

    public SpawnerFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-spawner-finder", "Spawner Finder", category,
            "Flags chunks containing a monster spawner block entity (dungeons / spawner farms).");
    }

    @Override
    public void onEnable() {
        flagged.clear();
        notified.clear();
        activatedNotified.clear();
        tickCounter = 0;
    }

    @Override
    public void onDisable() {
        flagged.clear();
        notified.clear();
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-spawner-finder");
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;

        scan(mc);
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-spawner-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();

        for (LevelChunk chunk : scanCursor.nextBatch(mc, radius, 400, chunksPerTick.get())) {
            ChunkPos pos = chunk.getPos();
            boolean hasSpawner = false;
            for (var be : chunk.getBlockEntities().values()) {
                if (!(be instanceof SpawnerBlockEntity spawner)) continue;
                hasSpawner = true;
                if (activatedDetector.get()) checkActivated(mc, spawner);
            }
            if (hasSpawner) {
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

    /**
     * Shoreline activated-spawner detection: an idle spawner's spawnDelay sits at 20; any other
     * value means it's been ticking because a player stood within 16 blocks of it. Out in the
     * wild (not dungeons someone just walked through), that's a player farm.
     */
    private void checkActivated(Minecraft mc, SpawnerBlockEntity spawner) {
        int delay;
        try {
            delay = ((kaptainwutax.seedcrackerX.mixin.BaseSpawnerAccessor) spawner.getSpawner())
                .seedcracker$getSpawnDelay();
        } catch (Throwable t) {
            return;
        }
        if (delay == 20) return; // idle
        // Nether spawners tick to 0 naturally when chunk-loaded; skip that false positive (Shoreline).
        if (mc.level.dimension() == net.minecraft.world.level.Level.NETHER && delay == 0) return;
        net.minecraft.core.BlockPos pos = spawner.getBlockPos();
        if (!activatedNotified.add(pos)) return;
        if (notify.get()) {
            String msg = "ACTIVATED spawner at " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + " (delay=" + delay + ") - player was nearby!";
            AutismNotifications.warning(msg);
            AutismClientMessaging.sendPrefixed("§d[SpawnerFinder] §f" + msg);
            if (mc.player != null) mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 0.6f);
        }
    }

    private static boolean tooFar(ChunkPos a, ChunkPos b, int radius) {
        return Math.abs(a.x() - b.x()) > radius || Math.abs(a.z() - b.z()) > radius;
    }

    private void onNewFlag(ChunkPos pos) {
        if (!notify.get()) return;
        String msg = "Spawner found at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ();
        AutismNotifications.warning(msg);
        AutismClientMessaging.sendPrefixed("§c[SpawnerFinder] §f" + msg);
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
        }
    }
}
