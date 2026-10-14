package com.autism.seedcracker.modules;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import com.autism.seedcracker.SeedcrackerAddon;
import com.autism.seedcracker.finder.ChunkFlagRenderer;
import com.autism.seedcracker.finder.FinderReport;
import com.autism.seedcracker.finder.UndergroundLight;
import com.autism.seedcracker.render.BlockEspRenderer;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;

public final class LightSourceFinderModule extends Module {
    private final IntSetting radius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 8, 1, 16, 1).group("General"));
    private final IntSetting minimum = add(new IntSetting("min-light", "Min light", 5, 1, 15, 1).group("General"));
    private final BoolSetting denoise = add(new BoolSetting("denoise", "Denoise", true).group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 16, 1, 128, 1).group("Performance"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0x8CFFE080).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final FinderReport reporter = new FinderReport("Light", 45);
    private final Set<ChunkPos> notified = new HashSet<>();
    private Map<Integer, Set<BlockPos>> cells = Map.of();
    private ClientLevel world;
    private UndergroundLight collecting;
    private ExecutorService worker;
    private Future<Map<Integer, Set<BlockPos>>> pending;
    private long lastScan;
    private int previousRadius, previousMinimum;
    private boolean previousDenoise;

    public LightSourceFinderModule() {
        super(SeedcrackerAddon.ID + ":light-finder", "Light Source Finder", "Highlights underground block light using Anubis-style snapshots and denoising.");
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() { reset(); if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false); }

    private void reset() {
        if (pending != null) pending.cancel(true);
        if (worker != null) worker.shutdownNow();
        pending = null; worker = null; collecting = null; world = null; lastScan = 0;
        cells = Map.of(); notified.clear();
        ChunkFlagRenderer.clear(id());
        for (int light = 1; light <= 15; light++) BlockEspRenderer.clear(id() + ":" + light);
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level || previousRadius != radius.get() || previousMinimum != minimum.get() || previousDenoise != denoise.get()) {
            reset(); world = mc.level; previousRadius = radius.get(); previousMinimum = minimum.get(); previousDenoise = denoise.get();
        }
        if (world == null || mc.player == null) return;
        if (pending != null && pending.isDone()) {
            try { cells = pending.get(); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); reset(); return; }
            catch (ExecutionException | java.util.concurrent.CancellationException failure) {
                com.autism.seedcracker.compat.ClientNotify.warning("Light scan failed: " + failure.getClass().getSimpleName());
                cells = Map.of();
            }
            pending = null;
        }
        long now = System.currentTimeMillis();
        if (pending == null) {
            if (collecting == null && now - lastScan >= 1000) collecting = new UndergroundLight(mc.player.chunkPosition(), radius.get(), minimum.get());
            if (collecting != null && collecting.collect(world, chunksPerTick.get())) {
                Map<BlockPos, Integer> hits = collecting.hits();
                boolean filter = denoise.get(); int min = minimum.get();
                collecting = null; lastScan = now;
                if (worker == null) worker = Executors.newSingleThreadExecutor(task -> { Thread thread = new Thread(task, "Addon-Light"); thread.setDaemon(true); return thread; });
                pending = worker.submit(() -> UndergroundLight.surface(hits, filter, min));
            }
        }
        Set<ChunkPos> flagged = new HashSet<>();
        ChunkPos center = mc.player.chunkPosition();
        for (int light = 1; light <= 15; light++) {
            Set<BlockPos> visible = new HashSet<>();
            for (BlockPos pos : cells.getOrDefault(light, Set.of())) {
                ChunkPos chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
                if (Math.abs(chunk.x() - center.x()) <= radius.get() && Math.abs(chunk.z() - center.z()) <= radius.get()
                    && world.getChunkSource().hasChunk(chunk.x(), chunk.z())) { visible.add(pos); flagged.add(chunk); }
            }
            int alpha = Math.round((color.get() >>> 24) * (0.5f + light / 30.0f));
            BlockEspRenderer.feed(id() + ":" + light, visible, (alpha << 24) | (color.get() & 0xFFFFFF), false, true);
        }
        for (ChunkPos pos : flagged) if (notified.add(pos) && notify.get()) com.autism.seedcracker.finder.FinderNotify.flag("[LightFinder]", "Underground light at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), false);
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    @Override public String info() { return cells.values().stream().mapToInt(Set::size).sum() + " light cells"; }
}
