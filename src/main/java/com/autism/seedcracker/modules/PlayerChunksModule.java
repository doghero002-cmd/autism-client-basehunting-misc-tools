package com.autism.seedcracker.modules;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import autismclient.util.AutismClientMessaging;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;

/**
 * Player Chunks (nyx PlayerChunksModule port).
 *
 * Records the chunk of every OTHER player seen in render distance and keeps the markers CACHED
 * after they leave range - an out-of-range player's last known anchor stays on screen. Players
 * loiter at their bases: repeated sightings in the same chunks (high visit counts) are strong
 * base-location intel, especially right after an /rtp lands you near someone.
 *
 * Markers render through the shared ChunkFlagRenderer; visit counts are kept per chunk and the
 * most-visited chunks survive the cache cap.
 */
public final class PlayerChunksModule extends Module {

    private final IntSetting maxCached = add(new IntSetting("max-cached", "Max cached chunks", 200, 20, 1000, 10)
        .description("Cached player-anchor chunks kept (least-visited evicted first).")
        .group("General"));
    private final IntSetting minVisits = add(new IntSetting("min-visits", "Min visits to render", 1, 1, 50, 1)
        .description("Sightings (1/s while in range) a chunk needs before it renders - raise to only show loiter spots, not travel paths.")
        .group("General"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true)
        .description("Chat ping when a NEW player is first seen.")
        .group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Chunk colour", 0x9600FFFF)
        .description("Colour of player-anchor chunk markers.")
        .group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false)
        .description("Tracer line to each cached anchor chunk.")
        .group("Render"));

    /** Visit count per chunk (concurrent: render thread reads while tick writes). */
    private final Map<ChunkPos, Integer> visits = new ConcurrentHashMap<>();
    private final Map<ChunkPos, String> lastPlayer = new ConcurrentHashMap<>();
    private final java.util.Set<String> seenPlayers = ConcurrentHashMap.newKeySet();
    private int sampleTicks = 0;

    public PlayerChunksModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":player-chunks", "Player Chunks", category,
            "Caches every other player's chunk positions - loiter clusters reveal their base.");
    }

    @Override
    public void onEnable() {
        // Keep the cache across toggles ON PURPOSE (it's intel); only sampling resets.
        sampleTicks = 0;
    }

    @Override
    public void onDisable() {
        ChunkFlagRenderer.clear(id());
    }

    @Override
    public void onGameLeft() { if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Sample once per second (20t): per-tick counting would inflate loiter counts instantly.
        if (++sampleTicks < 20) {
            feedRenderer();
            return;
        }
        sampleTicks = 0;

        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator()) continue;
            ChunkPos cp = p.chunkPosition();
            visits.merge(cp, 1, Integer::sum);
            String name = p.getPlainTextName();
            lastPlayer.put(cp, name);
            if (seenPlayers.add(name) && notify.get()) {
                AutismClientMessaging.sendPrefixed("§b[PlayerChunks] §f" + name
                    + " seen at X:" + cp.getMinBlockX() + " Z:" + cp.getMinBlockZ());
            }
        }

        // Evict least-visited entries over the cap.
        int over = visits.size() - maxCached.get();
        if (over > 0) {
            visits.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(over)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(k -> { visits.remove(k); lastPlayer.remove(k); });
        }
        feedRenderer();
    }

    private void feedRenderer() {
        int min = minVisits.get();
        java.util.Set<ChunkPos> render = new java.util.HashSet<>();
        for (Map.Entry<ChunkPos, Integer> e : visits.entrySet()) {
            if (e.getValue() >= min) render.add(e.getKey());
        }
        ChunkFlagRenderer.feed(id(), render, color.get(), tracer.get());
    }

    @Override
    public String info() {
        return seenPlayers.size() + " players / " + visits.size() + " chunks";
    }
}
