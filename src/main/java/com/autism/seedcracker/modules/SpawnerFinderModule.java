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
    private final BoolSetting notify = add(new BoolSetting(
            "notification", "Notification", true)
        .description("Toast + chat ping when a spawner chunk is found.")
        .group("General"));

    private final Set<ChunkPos> flagged = new HashSet<>();
    private final Set<ChunkPos> notified = new HashSet<>();
    private int tickCounter = 0;

    public SpawnerFinderModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":z-spawner-finder", "Spawner Finder", category,
            "Flags chunks containing a monster spawner block entity (dungeons / spawner farms).");
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
        ChunkFlagRenderer.clear(SeedcrackerAddon.ID + ":z-spawner-finder");
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
        ChunkFlagRenderer.feed(SeedcrackerAddon.ID + ":z-spawner-finder", flagged, color.get(), tracer.get());
    }

    private void scan(Minecraft mc) {
        List<LevelChunk> chunks = ChunkScanHelper.loadedChunksAround(mc, scanRadius.get());
        ChunkPos playerChunk = mc.player.chunkPosition();
        int radius = scanRadius.get();

        for (LevelChunk chunk : chunks) {
            ChunkPos pos = chunk.getPos();
            boolean hasSpawner = ChunkScanHelper.chunkHasBlockEntity(chunk, be -> be instanceof SpawnerBlockEntity);
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
