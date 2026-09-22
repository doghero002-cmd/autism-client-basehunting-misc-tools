package com.autism.seedcracker.modules;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;

import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * Heat Map Chunk Radar (nyx HeatMapChunkRadarModule port).
 *
 * Records every chunk YOU have visited (per server + dimension) with a visit timestamp and
 * persists the map to disk. While hunting, the radar shows which nearby chunks you have already
 * swept so you never re-scan the same area twice; stale entries age out after a configurable
 * number of hours.
 *
 * Persist format: one "x,z,lastVisitMs" line per chunk under
 * {@code <autism folder>/heatmap/<server>_<dimension>.txt}.
 */
public final class HeatMapRadarModule extends Module {

    private final IntSetting maxAgeHours = add(new IntSetting("max-age-hours", "Max age (hours)", 24, 1, 24 * 14, 1)
        .description("Visited chunks older than this fall off the radar.").group("General"));
    private final IntSetting renderRadius = add(new IntSetting("render-radius", "Render radius (chunks)", 16, 4, 64, 2)
        .description("How far around you visited chunks are rendered.").group("Render"));
    private final ColorSetting color = add(new ColorSetting("color", "Visited colour", 0x5040FF40)
        .description("Colour of already-visited chunk markers.").group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false)
        .description("Tracer line to visited chunk markers (very noisy - off by default).").group("Render"));
    private final BoolSetting persist = add(new BoolSetting("persist", "Persist to disk", true)
        .description("Save/load the visited map per server+dimension.").group("General"));

    /** Visited chunk -> last visit ms (for the CURRENT server+dimension only). */
    private final Map<Long, Long> visited = new ConcurrentHashMap<>();
    private String loadedKey = null;
    private int saveTicks = 0;
    private boolean dirty = false;

    public HeatMapRadarModule(autismclient.modules.ModuleCategory category) {
        super(SeedcrackerAddon.ID + ":heatmap-radar", "HeatMap Radar", category,
            "Per-server visited-chunk heat map so you never sweep the same area twice.");
    }

    @Override
    public void onEnable() {
        loadedKey = null; // force reload for the current server+dim
    }

    @Override
    public void onDisable() {
        if (dirty) save();
        ChunkFlagRenderer.clear(id());
    }

    @Override
    public void onGameLeft() {
        if (dirty) save();
        if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false);
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        String key = mapKey(mc);
        if (!key.equals(loadedKey)) {
            if (dirty) save();
            visited.clear();
            loadedKey = key;
            if (persist.get()) load();
        }

        // Record the current chunk.
        ChunkPos cp = mc.player.chunkPosition();
        long packed = pack(cp.x(), cp.z());
        Long prev = visited.put(packed, System.currentTimeMillis());
        if (prev == null) dirty = true;

        // Prune stale entries once a second.
        if (++saveTicks % 20 == 0) {
            long cutoff = System.currentTimeMillis() - maxAgeHours.get() * 3600_000L;
            visited.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
        // Autosave every 30s if dirty.
        if (persist.get() && saveTicks >= 600) {
            saveTicks = 0;
            if (dirty) save();
        }

        feedRenderer(mc, cp);
    }

    private Set<ChunkPos> shownCache = new HashSet<>();
    private int shownRebuildTicks = 0;

    private void feedRenderer(Minecraft mc, ChunkPos center) {
        // Rebuild twice a second: the visited map grows huge over long sessions and re-walking
        // it every tick was measurable; markers only need ~0.5s freshness.
        if (--shownRebuildTicks <= 0) {
            shownRebuildTicks = 10;
            int r = renderRadius.get();
            Set<ChunkPos> shown = new HashSet<>();
            for (Map.Entry<Long, Long> e : visited.entrySet()) {
                int x = unpackX(e.getKey());
                int z = unpackZ(e.getKey());
                if (Math.abs(x - center.x()) > r || Math.abs(z - center.z()) > r) continue;
                if (x == center.x() && z == center.z()) continue; // current chunk marker is just noise
                shown.add(new ChunkPos(x, z));
            }
            shownCache = shown;
        }
        ChunkFlagRenderer.feed(id(), shownCache, color.get(), tracer.get());
    }

    @Override
    public String info() {
        return visited.size() + " chunks";
    }

    // ---- persistence ----

    private String mapKey(Minecraft mc) {
        String server = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "singleplayer";
        String dim = mc.level.dimension().identifier().toString();
        return (server + "_" + dim).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private Path file() {
        return autismclient.AutismClientAddon.FOLDER.toPath().resolve("heatmap").resolve(loadedKey + ".txt");
    }

    private void load() {
        try {
            Path f = file();
            if (!Files.exists(f)) return;
            long cutoff = System.currentTimeMillis() - maxAgeHours.get() * 3600_000L;
            for (String line : Files.readAllLines(f)) {
                String[] parts = line.split(",");
                if (parts.length != 3) continue;
                try {
                    long ms = Long.parseLong(parts[2]);
                    if (ms < cutoff) continue;
                    visited.put(pack(Integer.parseInt(parts[0]), Integer.parseInt(parts[1])), ms);
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable t) {
            com.autism.seedcracker.util.FlagLog.warn("HEATMAP", "HeatMapRadar", "load failed: " + t);
        }
        dirty = false;
    }

    private void save() {
        if (loadedKey == null || !persist.get()) { dirty = false; return; }
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<Long, Long> e : visited.entrySet()) {
                sb.append(unpackX(e.getKey())).append(',').append(unpackZ(e.getKey()))
                  .append(',').append(e.getValue()).append('\n');
            }
            Files.writeString(f, sb.toString());
        } catch (Throwable t) {
            com.autism.seedcracker.util.FlagLog.warn("HEATMAP", "HeatMapRadar", "save failed: " + t);
        }
        dirty = false;
    }

    private static long pack(int x, int z) {
        return (((long) x) << 32) | (z & 0xffffffffL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    private static int unpackZ(long packed) {
        return (int) packed;
    }
}
