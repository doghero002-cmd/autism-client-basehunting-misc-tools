package com.autism.seedcracker.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;

public final class UndergroundLight {
    private final ArrayDeque<ChunkPos> chunks;
    private final Map<BlockPos, Integer> hits = new HashMap<>();
    private Map<BlockPos, Integer> transferred;
    private final int minimum;
    private ChunkPos current;
    private int sectionY;
    private int cell;
    private byte[] block;
    private byte[] sky;

    public UndergroundLight(ChunkPos center, int radius, int minimum) {
        this.minimum = minimum;
        ArrayList<ChunkPos> ordered = new ArrayList<>();
        for (int offsetX = -radius; offsetX <= radius; offsetX++) {
            for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) ordered.add(new ChunkPos(center.x() + offsetX, center.z() + offsetZ));
        }
        ordered.sort(Comparator.comparingLong(pos -> (long) (pos.x() - center.x()) * (pos.x() - center.x())
            + (long) (pos.z() - center.z()) * (pos.z() - center.z())));
        chunks = new ArrayDeque<>(ordered);
    }

    public Map<BlockPos, Integer> hits() {
        if (transferred == null) transferred = java.util.Collections.unmodifiableMap(hits);
        return transferred;
    }

    public boolean collect(ClientLevel level, int chunkBudget) {
        if (transferred != null) throw new IllegalStateException("Light snapshot already transferred");
        long deadline = System.nanoTime() + 2_000_000L;
        int started = 0;
        var engine = level.getChunkSource().getLightEngine();
        while (System.nanoTime() < deadline) {
            if (hits.size() >= 250_000) { chunks.clear(); current = null; return true; }
            if (current == null) {
                if (chunks.isEmpty()) return true;
                if (started++ >= chunkBudget) return false;
                current = chunks.removeFirst();
                if (!level.getChunkSource().hasChunk(current.x(), current.z())) { current = null; continue; }
                sectionY = level.getMinY() >> 4;
                cell = 0;
                block = null;
            }
            if (!level.getChunkSource().hasChunk(current.x(), current.z()) || sectionY >= 0) { current = null; continue; }
            if (block == null) {
                SectionPos section = SectionPos.of(current.x(), sectionY, current.z());
                DataLayer light = engine.getLayerListener(LightLayer.BLOCK).getDataLayerData(section);
                if (light == null || light.isEmpty()) { sectionY++; continue; }
                block = light.getData().clone();
                DataLayer skylight = engine.getLayerListener(LightLayer.SKY).getDataLayerData(section);
                sky = skylight == null ? new byte[2048] : skylight.getData().clone();
            }
            int value = com.autism.seedcracker.util.pure.PrimeLight.value(block, cell);
            if (qualifies(value, com.autism.seedcracker.util.pure.PrimeLight.value(sky, cell), minimum)) {
                hits.put(new BlockPos((current.x() << 4) + (cell & 15), (sectionY << 4) + (cell >> 8),
                    (current.z() << 4) + ((cell >> 4) & 15)), value);
            }
            if (++cell == 4096) { cell = 0; sectionY++; block = null; sky = null; }
        }
        return false;
    }

    static boolean qualifies(int block, int sky, int minimum) { return block >= minimum && block > sky; }

    public static Map<Integer, Set<BlockPos>> surface(Map<BlockPos, Integer> hits, boolean denoise, int minimum) {
        Set<BlockPos> skip = new HashSet<>();
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        if (denoise) {
            for (var entry : hits.entrySet()) {
                interrupted();
                BlockPos source = entry.getKey();
                int light = entry.getValue();
                if (light < minimum + 1 || light > minimum + 2 || skip.contains(source)) continue;
                boolean stronger = false;
                for (int offsetX = -1; offsetX <= 1 && !stronger; offsetX++) {
                    for (int offsetY = -1; offsetY <= 1 && !stronger; offsetY++) {
                        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                            if (hits.getOrDefault(probe.set(source.getX() + offsetX, source.getY() + offsetY, source.getZ() + offsetZ), 0) > light) { stronger = true; break; }
                        }
                    }
                }
                if (stronger) continue;
                ArrayDeque<BlockPos> queue = new ArrayDeque<>();
                queue.add(source); skip.add(source);
                while (!queue.isEmpty()) {
                    interrupted();
                    BlockPos pos = queue.removeFirst();
                    for (Direction direction : Direction.values()) {
                        probe.set(pos.getX() + direction.getStepX(), pos.getY() + direction.getStepY(), pos.getZ() + direction.getStepZ());
                        int value = hits.getOrDefault(probe, 0);
                        if (value >= minimum && value <= light && !skip.contains(probe)) {
                            BlockPos next = probe.immutable(); skip.add(next); queue.addLast(next);
                        }
                    }
                }
            }
        }
        Map<Integer, Set<BlockPos>> built = new HashMap<>();
        int count = 0;
        for (var entry : hits.entrySet()) {
            interrupted();
            BlockPos pos = entry.getKey();
            if (skip.contains(pos)) continue;
            boolean exposed = false;
            for (Direction direction : Direction.values()) {
                probe.set(pos.getX() + direction.getStepX(), pos.getY() + direction.getStepY(), pos.getZ() + direction.getStepZ());
                if (!hits.containsKey(probe) || skip.contains(probe)) { exposed = true; break; }
            }
            if (exposed) {
                built.computeIfAbsent(entry.getValue(), ignored -> new HashSet<>()).add(pos);
                if (++count >= 12_000) break;
            }
        }
        built.replaceAll((light, positions) -> Set.copyOf(positions));
        return Map.copyOf(built);
    }

    private static void interrupted() { if (Thread.currentThread().isInterrupted()) throw new CancellationException(); }
}