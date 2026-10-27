// SPDX-License-Identifier: GPL-3.0-only
// Adapted from Spectre LightEspModule, d5a7dd3 (Cody / cody-raves); GoNuts portions retain their MIT notice.
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
import com.autism.seedcracker.spectre.activity.ActivityRender;
import com.autism.seedcracker.spectre.activity.LightSnapshot;
import autismclient.api.module.BoolSetting;
import autismclient.api.module.ColorSetting;
import autismclient.api.module.IntSetting;
import autismclient.modules.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

public final class LightSourceFinderModule extends Module {
    private final IntSetting radius = add(new IntSetting("scan-radius", "Scan radius (chunks)", 8, 1, 16, 1).group("General"));
    private final IntSetting minimum = add(new IntSetting("min-light", "Min light", 5, 1, 15, 1).group("General"));
    private final BoolSetting denoise = add(new BoolSetting("denoise", "Denoise", true).group("General"));
    private final IntSetting chunksPerTick = add(new IntSetting("chunks-per-tick", "Chunks per tick", 16, 1, 128, 1).group("Performance"));
    private final BoolSetting notify = add(new BoolSetting("notification", "Notification", true).group("General"));
    private final ColorSetting color = add(new ColorSetting("color", "Colour", 0x80ffcc00).group("Render"));
    private final BoolSetting tracer = add(new BoolSetting("tracer", "Tracer", false).group("Render"));
    private final FinderReport reporter = new FinderReport("Light", 45);
    private final Set<ChunkPos> notified = new HashSet<>();
    private Map<Integer, Set<BlockPos>> cells = Map.of();
    private ClientLevel world;
    private ClientPacketListener connection;
    private LightSnapshot collecting;
    private ChunkPos scanCenter;
    private int scanRadius;
    private final Set<ChunkPos> invalidated = new HashSet<>();
    private ExecutorService worker;
    private Future<Map<Integer, Set<BlockPos>>> pending;
    private long lastScan;
    private int previousRadius, previousMinimum;
    private boolean previousDenoise;

    public LightSourceFinderModule() {
        super(SeedcrackerAddon.ID + ":light-finder", "Light Source Finder", "Spectre underground block-light snapshots and denoising. Light is a clue, not proof of a base.");
    }

    @Override public void onEnable() { reset(); }
    @Override public void onDisable() { reset(); }
    @Override public void onGameLeft() { reset(); if (com.autism.seedcracker.util.RelogPersistence.shouldDisableOnGameLeft()) setEnabledSilently(false); }

    private void cancelScan() {
        if (pending != null) pending.cancel(true);
        if (worker != null) worker.shutdownNow();
        pending = null;
        worker = null;
        collecting = null;
        scanCenter = null;
        invalidated.clear();
        lastScan = 0;
    }

    private void clearRender() {
        ChunkFlagRenderer.clear(id());
        for (int light = 1; light <= 15; light++) {
            BlockEspRenderer.clear(id() + ":" + light);
            ActivityRender.clear(id() + ":" + light);
        }
    }

    private void reset() {
        cancelScan();
        world = null;
        connection = null;
        cells = Map.of();
        notified.clear();
        reporter.clear();
        clearRender();
    }

    /** Client-thread ClientPacketListener TAIL hook, shared with the other activity ports. */
    public void onPacketApplied(Packet<?> packet) {
        if (packet instanceof ClientboundForgetLevelChunkPacket forget) onChunkUnload(forget.pos());
    }

    public void onChunkUnload(ChunkPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.isSameThread() || !isEnabled() || pos == null) return;
        if (world != mc.level || connection != mc.getConnection()) { reset(); return; }
        if (scanCenter != null && Math.abs((long) pos.x() - scanCenter.x()) <= scanRadius
            && Math.abs((long) pos.z() - scanCenter.z()) <= scanRadius) invalidated.add(pos);
        cells = LightSnapshot.exclude(cells, Set.of(pos));
        notified.remove(pos);
        reporter.clear();
        clearRender();
    }

    @Override public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (world != mc.level || connection != mc.getConnection() || previousRadius != radius.get()
            || previousMinimum != minimum.get() || previousDenoise != denoise.get()) {
            reset();
            world = mc.level;
            connection = mc.getConnection();
            previousRadius = radius.get();
            previousMinimum = minimum.get();
            previousDenoise = denoise.get();
        }
        if (world == null || mc.player == null) return;
        if (pending != null && pending.isDone()) {
            try { cells = LightSnapshot.exclude(pending.get(), invalidated); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); reset(); return; }
            catch (java.util.concurrent.CancellationException failure) { cells = Map.of(); }
            catch (ExecutionException failure) {
                if (failure.getCause() instanceof Error fatal) throw fatal;
                com.autism.seedcracker.compat.ClientNotify.warning("Light scan failed: " + failure.getCause().getClass().getSimpleName());
                cells = Map.of();
            }
            pending = null;
            scanCenter = null;
            invalidated.clear();
        }
        long now = System.nanoTime();
        if (pending == null) {
            if (collecting == null && (lastScan == 0 || now - lastScan >= 1_000_000_000L)) {
                scanCenter = mc.player.chunkPosition();
                scanRadius = radius.get();
                invalidated.clear();
                collecting = new LightSnapshot(scanCenter, scanRadius, minimum.get());
            }
            if (collecting != null && collecting.collect(world, chunksPerTick.get())) {
                Map<BlockPos, Integer> hits = collecting.hits();
                boolean filter = denoise.get();
                int min = minimum.get();
                collecting = null;
                lastScan = now;
                if (worker == null) worker = Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "Addon-Spectre-Light");
                    thread.setDaemon(true);
                    return thread;
                });
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
            int fill = ActivityRender.lightFill(color.get(), light);
            ActivityRender.feed(id() + ":" + light, visible.stream().map(AABB::new).toList(), ActivityRender.lightLine(fill), fill);
        }
        notified.retainAll(flagged);
        for (ChunkPos pos : flagged) if (notified.add(pos) && notify.get()) com.autism.seedcracker.finder.FinderNotify.flag("[LightFinder]", "Underground light signal at X:" + pos.getMinBlockX() + " Z:" + pos.getMinBlockZ(), false);
        ChunkFlagRenderer.feed(id(), flagged, color.get(), tracer.get());
        reporter.tick(mc, flagged);
    }

    @Override public String info() { return cells.values().stream().mapToInt(Set::size).sum() + " light cells"; }
}
