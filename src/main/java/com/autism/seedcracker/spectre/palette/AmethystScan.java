// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Spectre AmethystBypass + GeodeGlowScan; palette, light-peak and ANBS+ edge methods are distinct. */
public final class AmethystScan {
    private static final Direction[] SIDES = Direction.values();
    private final DataLayer[] light = new DataLayer[72];
    private final int[] neighbours = new int[6];
    private final boolean[] openings = new boolean[6];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int chunkX;
    private int chunkZ;

    public void clear() { Arrays.fill(light, null); }

    public static boolean bud(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD);
    }
    private static boolean geode(BlockState state) { return state.is(Blocks.AMETHYST_BLOCK) || state.is(Blocks.BUDDING_AMETHYST); }
    private static boolean open(BlockState state) { return state.isAir() || state.is(Blocks.AMETHYST_CLUSTER); }

    public static BlockPos paletteMarker(LevelChunk chunk, List<Integer> rawSections) {
        var sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) continue;
            int sectionY = chunk.getMinSectionY() + i;
            var states = section.getStates();
            boolean localBud = !states.maybeHas(state -> false) && states.maybeHas(AmethystScan::bud);
            if (!rawSections.contains(sectionY) && !localBud) continue;
            BlockPos best = null;
            int bestScore = Integer.MAX_VALUE;
            BlockPos.MutableBlockPos neighbour = new BlockPos.MutableBlockPos();
            int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ(), baseY = sectionY * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                if (!section.getBlockState(x, y, z).isAir()) continue;
                int wx = baseX + x, wy = baseY + y, wz = baseZ + z;
                for (Direction side : SIDES) {
                    int nx = wx + side.getStepX(), ny = wy + side.getStepY(), nz = wz + side.getStepZ();
                    if ((nx >> 4) != chunk.getPos().x() || (nz >> 4) != chunk.getPos().z()
                        || ny < chunk.getMinY() || ny > chunk.getMaxY()) continue;
                    if (!geode(chunk.getBlockState(neighbour.set(nx, ny, nz)))) continue;
                    int score = PaletteScan.scatter(wx, wy, wz);
                    if (score < bestScore) {
                        bestScore = score;
                        best = new BlockPos(wx, wy, wz);
                    }
                    break;
                }
            }
            return best == null ? new BlockPos(baseX + 8, baseY + 8, baseZ + 8) : best;
        }
        return null;
    }

    public Set<BlockPos> lightPeaks(ClientLevel level, LevelChunk chunk) {
        Set<BlockPos> found = new LinkedHashSet<>();
        var sections = chunk.getSections();
        int baseX = chunk.getPos().getMinBlockX(), baseZ = chunk.getPos().getMinBlockZ();
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null || section.hasOnlyAir() || !section.getStates().maybeHas(AmethystScan::geode)) continue;
            int baseY = (chunk.getMinSectionY() + i) * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                int emission = section.getBlockState(x, y, z).getLightEmission();
                if (emission > 0) continue;
                cursor.set(baseX + x, baseY + y, baseZ + z);
                int value = level.getBrightness(LightLayer.BLOCK, cursor);
                if (value != 1 && value != 2 && value != 4 && value != 5) continue;
                boolean adjacentGeode = false;
                for (int side = 0; side < SIDES.length; side++) {
                    BlockPos neighbour = cursor.relative(SIDES[side]);
                    if (!SpawnerScan.loaded(level, neighbour)) {
                        neighbours[side] = -1;
                        continue;
                    }
                    adjacentGeode |= geode(level.getBlockState(neighbour));
                    neighbours[side] = level.getBrightness(LightLayer.BLOCK, neighbour);
                }
                if (PaletteScan.budLight(emission, value, neighbours, adjacentGeode)) found.add(cursor.immutable());
                if (found.size() >= 512) return Set.copyOf(found);
            }
        }
        return Set.copyOf(found);
    }

    public Set<BlockPos> glowEdges(ClientLevel level, LevelChunk chunk, int minimumCells) {
        chunkX = chunk.getPos().x();
        chunkZ = chunk.getPos().z();
        Set<BlockPos> found = new LinkedHashSet<>();
        try {
            if (!cacheLight(level)) return Set.of();
            int count = 0;
            var sections = chunk.getSections();
            for (int sy = -4; sy <= 1; sy++) {
                int index = chunk.getSectionIndexFromSectionY(sy);
                if (index < 0 || index >= sections.length || sections[index] == null || !nearLight(sy)) continue;
                int from = Math.max(Math.max(-58, sy * 16), level.getMinY());
                int to = Math.min(Math.min(30, sy * 16 + 15), level.getMaxY());
                for (int y = from; y <= to; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                    if (!open(sections[index].getBlockState(x, y & 15, z))) continue;
                    int wx = chunkX * 16 + x, wz = chunkZ * 16 + z;
                    if (lightAt(wx, y, wz) != 0) continue;
                    for (int side = 0; side < SIDES.length; side++) {
                        Direction direction = SIDES[side];
                        int nx = wx + direction.getStepX(), ny = y + direction.getStepY(), nz = wz + direction.getStepZ();
                        cursor.set(nx, ny, nz);
                        neighbours[side] = SpawnerScan.loaded(level, cursor) ? lightAt(nx, ny, nz) : -1;
                        openings[side] = neighbours[side] == 4 && open(level.getBlockState(cursor));
                    }
                    if (PaletteScan.glowEdge(0, neighbours, openings)) {
                        count++;
                        if (found.size() < 200) found.add(new BlockPos(wx, y, wz));
                    }
                }
            }
            return count >= minimumCells ? Set.copyOf(found) : Set.of();
        } finally {
            clear();
        }
    }

    private boolean cacheLight(ClientLevel level) {
        var listener = level.getLightEngine().getLayerListener(LightLayer.BLOCK);
        boolean any = false;
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int section = 0; section < 8; section++) {
            DataLayer data = level.getChunkSource().hasChunk(chunkX + dx, chunkZ + dz)
                ? listener.getDataLayerData(SectionPos.of(chunkX + dx, -5 + section, chunkZ + dz)) : null;
            if (data != null && data.isEmpty()) data = null;
            light[slot(dx, dz, section)] = data;
            any |= data != null;
        }
        return any;
    }

    private boolean nearLight(int sy) {
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int s = sy - 1; s <= sy + 1; s++) {
            if (light[slot(dx, dz, s + 5)] != null) return true;
        }
        return false;
    }

    private int lightAt(int x, int y, int z) {
        int section = (y >> 4) + 5, dx = (x >> 4) - chunkX, dz = (z >> 4) - chunkZ;
        if (section < 0 || section >= 8 || dx < -1 || dx > 1 || dz < -1 || dz > 1) return 0;
        DataLayer data = light[slot(dx, dz, section)];
        return data == null ? 0 : data.get(x & 15, y & 15, z & 15);
    }

    private static int slot(int dx, int dz, int section) { return ((dx + 1) * 3 + dz + 1) * 8 + section; }
}
