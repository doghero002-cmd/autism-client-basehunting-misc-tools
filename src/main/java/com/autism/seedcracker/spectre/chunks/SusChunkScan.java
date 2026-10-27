// SPDX-License-Identifier: GPL-3.0-only
// Spectre SusChunkFinderModule / GeodeGlowScan, d5a7dd3de0be67bea273d4ed74d2c76c11e0f6da.
package com.autism.seedcracker.spectre.chunks;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import com.autism.seedcracker.util.pure.PrimeLight;
import com.autism.seedcracker.util.pure.SusScore;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

/** One resumable, client-thread scan; it never requests or generates an unloaded chunk. */
public final class SusChunkScan {
    public static final int MAX_OBJECTS_PER_CHUNK = 1024;
    private static final Direction[] SIDES = Direction.values();
    public record Sample(int heat, boolean veto, boolean terrain, boolean complete,
                         ActivityClues.Summary activity, List<SuspectObjects.Found> objects,
                         List<BlockPos> lightEdges, boolean limited) {}
    private final ClientLevel level;
    private final LevelChunk chunk;
    private final Set<ActivityClues.Kind> clues;
    private final Set<SuspectObjects.Category> categories;
    private final Vec3 eye;
    private final double rangeSquared;
    private final boolean paletteOnly, glowMode;
    private final ActivityClues.Summary activity = new ActivityClues.Summary();
    private final List<SuspectObjects.Found> objects = new ArrayList<>();
    private final List<BlockPos> edges = new ArrayList<>();
    private final byte[][] light = new byte[72][];
    private final int[] adjacent = new int[6];
    private final boolean[] adjacentOpen = new boolean[6];
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private int sectionIndex, cell, grown, young, detached, unknown, glowCell, edgeCount;
    private boolean terrain, sawBud, sawCluster, glowStarted, glowDone, limited;

    public SusChunkScan(ClientLevel level, LevelChunk chunk, boolean paletteOnly, boolean glowMode,
                        Set<ActivityClues.Kind> clues, Set<SuspectObjects.Category> categories, Vec3 eye, int range) {
        this.level = level; this.chunk = chunk; this.paletteOnly = paletteOnly; this.glowMode = glowMode;
        this.clues = Set.copyOf(clues); this.categories = Set.copyOf(categories);
        this.eye = eye; this.rangeSquared = (double) range * range;
    }

    public boolean step(long deadline) {
        while (sectionIndex < chunk.getSectionsCount() && System.nanoTime() < deadline) {
            var section = chunk.getSection(sectionIndex);
            int baseY = chunk.getMinY() + (sectionIndex << 4);
            if (cell == 0) {
                if (section == null || section.hasOnlyAir()) { sectionIndex++; continue; }
                if (paletteOnly) {
                    sawBud |= section.maybeHas(SusChunkScan::bud);
                    sawCluster |= section.maybeHas(state -> state.is(Blocks.AMETHYST_CLUSTER));
                }
                boolean band = !terrain && baseY <= 30 && baseY + 15 >= -16;
                if (!band && !section.maybeHas(state -> SuspectObjects.category(state) != null)) { sectionIndex++; continue; }
            }
            int stop = Math.min(4096, cell + 64);
            for (; cell < stop; cell++) {
                int y = baseY + (cell >>> 8);
                BlockState state = section.getBlockState(cell & 15, cell >>> 8, (cell >>> 4) & 15);
                if (y >= -16 && y <= 30 && !state.isAir()) terrain = true;
                var category = SuspectObjects.category(state);
                if (category == null) continue;
                cursor.set(chunk.getPos().getMinBlockX() + (cell & 15), y, chunk.getPos().getMinBlockZ() + ((cell >>> 4) & 15));
                if (!paletteOnly && !glowMode) {
                    if (state.is(Blocks.AMETHYST_CLUSTER)) grown++;
                    else if (bud(state)) {
                        BlockPos support = cursor.relative(state.getValue(BlockStateProperties.FACING).getOpposite());
                        var neighbor = level.getChunkSource().getChunkNow(support.getX() >> 4, support.getZ() >> 4);
                        if (neighbor == null) unknown++;
                        else if (neighbor.getBlockState(support).is(Blocks.BUDDING_AMETHYST)) young++;
                        else detached++;
                    }
                }
                var kind = ActivityClues.kind(state);
                boolean selected = kind != null && clues.contains(kind);
                if (selected) activity.add(state);
                if (!categories.contains(category) || category == SuspectObjects.Category.GROWTH && !selected
                    || category == SuspectObjects.Category.GEODES && !clues.contains(ActivityClues.Kind.AMETHYST)) continue;
                if (Vec3.atCenterOf(cursor).distanceToSqr(eye) > rangeSquared) continue;
                if (objects.size() < MAX_OBJECTS_PER_CHUNK) objects.add(new SuspectObjects.Found(cursor.immutable(), state, category));
                else limited = true;
            }
            if (cell == 4096) { sectionIndex++; cell = 0; }
        }
        if (sectionIndex < chunk.getSectionsCount()) return false;
        return !glowMode || glow(deadline);
    }

    private boolean glow(long deadline) {
        if (!glowStarted) {
            glowStarted = true;
            var listener = level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK);
            boolean any = false;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (level.getChunkSource().getChunkNow(chunk.getPos().x() + dx, chunk.getPos().z() + dz) == null) {
                    unknown++; glowDone = true; return true;
                }
                for (int s = 0; s < 8; s++) {
                    var layer = listener.getDataLayerData(SectionPos.of(chunk.getPos().x() + dx, s - 5, chunk.getPos().z() + dz));
                    if (layer != null && !layer.isEmpty()) { light[slot(dx, dz, s)] = layer.getData().clone(); any = true; }
                }
            }
            if (!any) glowDone = true;
        }
        while (!glowDone && System.nanoTime() < deadline) {
            int stop = Math.min(89 * 256, glowCell + 64);
            for (; glowCell < stop; glowCell++) {
                int y = -58 + (glowCell >>> 8);
                int index = chunk.getSectionIndex(y);
                if (index < 0 || index >= chunk.getSectionsCount()) continue;
                int x = chunk.getPos().getMinBlockX() + (glowCell & 15);
                int z = chunk.getPos().getMinBlockZ() + ((glowCell >>> 4) & 15);
                if (lightAt(x, y, z) != 0 || !open(chunk.getSection(index).getBlockState(x & 15, y & 15, z & 15))) continue;
                boolean available = true;
                for (int i = 0; i < SIDES.length; i++) {
                    Direction side = SIDES[i];
                    int nx = x + side.getStepX(), ny = y + side.getStepY(), nz = z + side.getStepZ();
                    adjacent[i] = lightAt(nx, ny, nz);
                    var neighbor = level.getChunkSource().getChunkNow(nx >> 4, nz >> 4);
                    if (neighbor == null) { available = false; break; }
                    adjacentOpen[i] = open(neighbor.getBlockState(cursor.set(nx, ny, nz)));
                }
                if (!available) { unknown++; continue; }
                if (SusScore.glowEdge(0, adjacent, adjacentOpen)) {
                    edgeCount++;
                    cursor.set(x, y, z);
                    if (Vec3.atCenterOf(cursor).distanceToSqr(eye) <= rangeSquared) {
                        if (edges.size() < MAX_OBJECTS_PER_CHUNK) edges.add(cursor.immutable());
                        else limited = true;
                    }
                }
            }
            glowDone = glowCell >= 89 * 256;
        }
        return glowDone;
    }

    private int lightAt(int x, int y, int z) {
        int s = (y >> 4) + 5, dx = (x >> 4) - chunk.getPos().x(), dz = (z >> 4) - chunk.getPos().z();
        if (s < 0 || s >= 8 || dx < -1 || dx > 1 || dz < -1 || dz > 1) return 0;
        byte[] bytes = light[slot(dx, dz, s)];
        return bytes == null ? 0 : PrimeLight.value(bytes, ((y & 15) << 8) | ((z & 15) << 4) | (x & 15));
    }

    private static int slot(int dx, int dz, int section) { return ((dx + 1) * 3 + dz + 1) * 8 + section; }
    private static boolean open(BlockState state) { return state.isAir() || state.is(Blocks.AMETHYST_CLUSTER); }
    private static boolean bud(BlockState state) {
        return state.is(Blocks.SMALL_AMETHYST_BUD) || state.is(Blocks.MEDIUM_AMETHYST_BUD) || state.is(Blocks.LARGE_AMETHYST_BUD);
    }

    public Sample result() {
        int mature = paletteOnly ? (sawCluster && !sawBud ? 1 : 0) : grown + detached;
        boolean veto = paletteOnly ? sawBud : young > 0;
        return new Sample(glowMode ? edgeCount / 12 : mature, !glowMode && veto, terrain, unknown == 0,
            activity, List.copyOf(objects), List.copyOf(edges), limited);
    }
}
