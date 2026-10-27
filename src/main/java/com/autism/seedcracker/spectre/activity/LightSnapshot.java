// SPDX-License-Identifier: GPL-3.0-only
// Spectre LightSnapshot (d5a7dd3, Cody / cody-raves), with the addon's resumable two-ms collection budget.
package com.autism.seedcracker.spectre.activity;

import com.autism.seedcracker.util.pure.PrimeLight;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

public final class LightSnapshot {
    private final ArrayDeque<ChunkPos> chunks;
    private final Map<BlockPos, Integer> hits = new HashMap<>();
    private final int minimum;
    private Map<BlockPos, Integer> transferred;
    private ChunkPos current;
    private int sectionY;
    private int cell;
    private byte[] block;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public LightSnapshot(ChunkPos center, int radius, int minimum) {
        this.minimum = Math.clamp(minimum, 1, 15);
        int distance = Math.clamp(radius, 1, 16);
        ArrayList<ChunkPos> ordered = new ArrayList<>();
        for (int x = -distance; x <= distance; x++) for (int z = -distance; z <= distance; z++) {
            ordered.add(new ChunkPos(center.x() + x, center.z() + z));
        }
        ordered.sort(Comparator.comparingLong(pos -> (long) (pos.x() - center.x()) * (pos.x() - center.x())
            + (long) (pos.z() - center.z()) * (pos.z() - center.z())));
        chunks = new ArrayDeque<>(ordered);
    }

    public Map<BlockPos, Integer> hits() {
        if (transferred == null) transferred = Collections.unmodifiableMap(hits);
        return transferred;
    }

    /** Only the client thread reads the light engine; the worker receives the finished hit map. */
    public boolean collect(ClientLevel level, int chunkBudget) {
        if (transferred != null) throw new IllegalStateException("Light snapshot already transferred");
        long startedAt = System.nanoTime();
        int started = 0;
        var engine = level.getChunkSource().getLightEngine();
        var skylight = engine.getLayerListener(LightLayer.SKY);
        while (System.nanoTime() - startedAt < 2_000_000L) {
            if (hits.size() >= 250_000) { chunks.clear(); current = null; return true; }
            if (current == null) {
                if (chunks.isEmpty()) return true;
                if (started++ >= Math.max(1, chunkBudget)) return false;
                current = chunks.removeFirst();
                if (!level.getChunkSource().hasChunk(current.x(), current.z())) { current = null; continue; }
                sectionY = level.getMinSectionY();
                cell = 0;
                block = null;
            }
            if (!level.getChunkSource().hasChunk(current.x(), current.z()) || sectionY >= 0) { current = null; continue; }
            if (block == null) {
                DataLayer light = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(SectionPos.of(current.x(), sectionY, current.z()));
                if (light == null || light.isEmpty()) { sectionY++; continue; }
                block = light.getData().clone();
            }
            int value = PrimeLight.value(block, cell);
            if (value >= minimum) {
                cursor.set((current.x() << 4) + (cell & 15), (sectionY << 4) + (cell >> 8), (current.z() << 4) + ((cell >> 4) & 15));
                // A missing sky data layer can inherit sky light; treating it as zero produces false hits.
                if (qualifies(value, skylight.getLightValue(cursor), minimum)) hits.put(cursor.immutable(), value);
            }
            if (++cell == 4096) { cell = 0; sectionY++; block = null; }
        }
        return false;
    }

    public static Map<Integer, java.util.Set<BlockPos>> exclude(Map<Integer, java.util.Set<BlockPos>> cells,
                                                               java.util.Set<ChunkPos> unloaded) {
        if (unloaded.isEmpty()) return cells;
        Map<Integer, java.util.Set<BlockPos>> kept = new HashMap<>();
        cells.forEach((light, positions) -> {
            java.util.Set<BlockPos> remaining = new java.util.HashSet<>(positions);
            remaining.removeIf(pos -> unloaded.contains(new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4)));
            if (!remaining.isEmpty()) kept.put(light, java.util.Set.copyOf(remaining));
        });
        return Map.copyOf(kept);
    }

    static boolean qualifies(int block, int sky, int minimum) { return block >= minimum && block > sky; }
}
