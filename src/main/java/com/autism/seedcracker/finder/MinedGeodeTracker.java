package com.autism.seedcracker.finder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

public final class MinedGeodeTracker {
    record Slice(Set<BlockPos> lining, Set<BlockPos> budding, int tick) {}
    record Cluster(Set<BlockPos> lining, int budding, int minX, int minZ, int maxX, int maxZ, ChunkPos chunk) {
        boolean large() { return budding > 0 ? budding >= 10 : lining.size() >= 160; }
    }
    private final Map<ChunkPos, Slice> slices = new HashMap<>();
    private Map<ChunkPos, Slice> clusterInputs = Map.of();
    private List<Cluster> cachedClusters = List.of();
    public void clear() { slices.clear(); clusterInputs = Map.of(); cachedClusters = List.of(); }

    public boolean collect(ClientLevel world, LevelChunk chunk, int tick) {
        ChunkPos chunkPos = chunk.getPos();
        if (slices.containsKey(chunkPos)) return true;
        for (int offsetX = -1; offsetX <= 1; offsetX++) for (int offsetZ = -1; offsetZ <= 1; offsetZ++) if (!world.hasChunk(chunkPos.x() + offsetX, chunkPos.z() + offsetZ)) return false;
        Set<BlockPos> lining = new HashSet<>(), budding = new HashSet<>();
        for (int index = 0; index < chunk.getSectionsCount(); index++) {
            int baseY = chunk.getMinY() + (index << 4);
            if (baseY > 47) break;
            var section = chunk.getSection(index);
            if (section == null || !section.maybeHas(MinedGeodeTracker::lining)) continue;
            for (int localY = 0; localY < 16; localY++) for (int localZ = 0; localZ < 16; localZ++) for (int localX = 0; localX < 16; localX++) {
                BlockState state = section.getBlockState(localX, localY, localZ);
                if (!lining(state)) continue;
                BlockPos pos = new BlockPos(chunkPos.getMinBlockX() + localX, baseY + localY, chunkPos.getMinBlockZ() + localZ);
                for (Direction side : Direction.values()) {
                    BlockState neighbor = world.getBlockState(pos.relative(side));
                    if (neighbor.isAir() || growth(neighbor) || !neighbor.getFluidState().isEmpty()) {
                        lining.add(pos); if (state.is(Blocks.BUDDING_AMETHYST)) budding.add(pos); break;
                    }
                }
            }
        }
        slices.put(chunkPos, new Slice(Set.copyOf(lining), Set.copyOf(budding), tick));
        return true;
    }

    public Set<ChunkPos> shown(ClientLevel world, int tick, ChunkPos center, int radius) {
        Map<ChunkPos, Slice> active = new HashMap<>();
        for (int offsetX = -radius; offsetX <= radius; offsetX++) for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
            ChunkPos pos = new ChunkPos(center.x() + offsetX, center.z() + offsetZ);
            Slice slice = slices.get(pos);
            if (slice != null && world.hasChunk(pos.x(), pos.z())) active.put(pos, slice);
        }
        Set<ChunkPos> result = new HashSet<>();
        if (!active.equals(clusterInputs)) { cachedClusters = clusters(active.values()); clusterInputs = Map.copyOf(active); }
        for (Cluster cluster : cachedClusters) {
            if (!cluster.large()) continue;
            boolean settled = true;
            for (int chunkX = (cluster.minX - 1) >> 4; chunkX <= (cluster.maxX + 1) >> 4 && settled; chunkX++) {
                for (int chunkZ = (cluster.minZ - 1) >> 4; chunkZ <= (cluster.maxZ + 1) >> 4; chunkZ++) {
                    Slice slice = active.get(new ChunkPos(chunkX, chunkZ));
                    if (slice == null || tick - slice.tick < 40) { settled = false; break; }
                }
            }
            if (settled && unlit(world, cluster.lining)) result.add(cluster.chunk);
        }
        return Set.copyOf(result);
    }

    static List<Cluster> clusters(java.util.Collection<Slice> slices) {
        Map<BlockPos, Set<BlockPos>> cells = new HashMap<>(); Set<BlockPos> buds = new HashSet<>();
        for (Slice slice : slices) {
            buds.addAll(slice.budding);
            for (BlockPos pos : slice.lining) cells.computeIfAbsent(new BlockPos(pos.getX() >> 2, pos.getY() >> 2, pos.getZ() >> 2), ignored -> new HashSet<>()).add(pos);
        }
        Set<BlockPos> seen = new HashSet<>(); List<Cluster> result = new ArrayList<>();
        for (BlockPos start : cells.keySet()) {
            if (!seen.add(start)) continue;
            Set<BlockPos> lining = new HashSet<>(); ArrayDeque<BlockPos> queue = new ArrayDeque<>(); queue.add(start);
            while (!queue.isEmpty()) {
                BlockPos cell = queue.removeFirst(); lining.addAll(cells.get(cell));
                for (int offsetX = -1; offsetX <= 1; offsetX++) for (int offsetY = -1; offsetY <= 1; offsetY++) for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                    BlockPos next = cell.offset(offsetX, offsetY, offsetZ);
                    if (cells.containsKey(next) && seen.add(next)) queue.add(next);
                }
            }
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE, count = 0;
            long sumX = 0, sumZ = 0;
            for (BlockPos pos : lining) {
                minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX()); minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
                sumX += pos.getX(); sumZ += pos.getZ(); if (buds.contains(pos)) count++;
            }
            result.add(new Cluster(Set.copyOf(lining), count, minX, minZ, maxX, maxZ, new ChunkPos((int) Math.floorDiv(sumX, lining.size()) >> 4, (int) Math.floorDiv(sumZ, lining.size()) >> 4)));
        }
        return List.copyOf(result);
    }

    private static boolean unlit(ClientLevel world, Set<BlockPos> lining) {
        Map<BlockPos, Integer> peaks = new HashMap<>();
        ToIntFunction<BlockPos> light = pos -> world.hasChunk(pos.getX() >> 4, pos.getZ() >> 4) ? world.getBrightness(LightLayer.BLOCK, pos) : -1;
        for (BlockPos pos : lining) for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.relative(side);
            if (growth(world.getBlockState(neighbor))) return false;
            int value = light.applyAsInt(neighbor);
            if (value < 0 || value > 0 && peak(neighbor, value, light, peaks) != 14) return false;
        }
        return true;
    }

    static int peak(BlockPos start, int value, ToIntFunction<BlockPos> light, Map<BlockPos, Integer> cached) {
        List<BlockPos> path = new ArrayList<>(); BlockPos current = start;
        int result;
        while (true) {
            Integer known = cached.get(current); if (known != null) { result = known; break; }
            path.add(current); BlockPos next = null; int best = value;
            for (Direction side : Direction.values()) {
                BlockPos neighbor = current.relative(side); int candidate = light.applyAsInt(neighbor);
                if (candidate < 0) return -1;
                if (candidate > best) { best = candidate; next = neighbor; }
            }
            if (next == null) { result = value; break; }
            current = next; value = best;
        }
        for (BlockPos pos : path) cached.put(pos, result);
        return result;
    }

    private static boolean lining(BlockState state) { return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST); }
    private static boolean growth(BlockState state) { return state.is(Blocks.AMETHYST_CLUSTER) || state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD); }
}