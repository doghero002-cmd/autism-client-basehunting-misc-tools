package com.autism.seedcracker.finder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Shared helpers for the chunk-scanner finder modules.
 *
 * All finders follow the same pattern: every tick they walk a small bubble of chunks around the
 * player, scan each chunk's sections / block entities for target blocks, and keep a set of flagged
 * chunk positions. These helpers centralise the section iteration (with the {@code maybeHas}
 * fast-skip), block-entity iteration and the loaded-chunk bubble lookup so each module stays small
 * and readable.
 */
public final class ChunkScanHelper {

    private ChunkScanHelper() {
    }

    /**
     * Counts blockstates matching {@code predicate} across the whole chunk.
     *
     * Iterates the chunk's {@link LevelChunkSection}s top to bottom and uses
     * {@link LevelChunkSection#maybeHas(Predicate)} to skip sections that cannot contain a match,
     * then walks the 16x16x16 volume of each candidate section.
     *
     * @return the number of matching blockstates (0 if the chunk has none)
     */
    public static int countBlocksInChunk(LevelChunk chunk, Predicate<BlockState> predicate) {
        if (chunk == null || predicate == null) return 0;
        int count = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) continue;
            // Cheap broad-phase: skip sections that provably have no matching state.
            if (!section.maybeHas(predicate)) continue;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (predicate.test(state)) count++;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Counts blockstates matching {@code predicate} across the whole chunk, stopping as soon as
     * the count reaches {@code limit}. Use this when the caller only needs to know whether the
     * count meets a threshold - it avoids scanning the rest of the chunk once the answer is known.
     *
     * @return the number of matching blockstates, capped at {@code limit}
     */
    public static int countBlocksInChunk(LevelChunk chunk, Predicate<BlockState> predicate, int limit) {
        if (chunk == null || predicate == null) return 0;
        if (limit <= 0) return countBlocksInChunk(chunk, predicate);
        int count = 0;
        LevelChunkSection[] sections = chunk.getSections();
        for (LevelChunkSection section : sections) {
            if (section == null || section.hasOnlyAir()) continue;
            if (!section.maybeHas(predicate)) continue;
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (predicate.test(state) && ++count >= limit) return count;
                    }
                }
            }
        }
        return count;
    }

    /**
     * Returns true when any block entity in the chunk matches {@code predicate}.
     *
     * Iterates {@link LevelChunk#getBlockEntities()} values (a pos -> blockEntity map) and stops at
     * the first match.
     */
    public static boolean chunkHasBlockEntity(LevelChunk chunk, Predicate<BlockEntity> predicate) {
        if (chunk == null || predicate == null) return false;
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            if (be != null && predicate.test(be)) return true;
        }
        return false;
    }

    /**
     * Returns the {@link LevelChunk}s within {@code chunkRadius} (Chebyshev, square bubble) of the
     * player's chunk that are actually loaded.
     *
     * Uses {@code level.hasChunk(cx, cz)} to skip unloaded positions, then fetches the full
     * {@link LevelChunk} via {@code level.getChunk(cx, cz)}.
     */
    public static List<LevelChunk> loadedChunksAround(Minecraft mc, int chunkRadius) {
        List<LevelChunk> out = new ArrayList<>();
        if (mc == null || mc.level == null || mc.player == null) return out;
        int radius = Math.max(0, chunkRadius);
        ChunkPos centre = mc.player.chunkPosition();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int cx = centre.x() + dx;
                int cz = centre.z() + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                if (chunk != null) out.add(chunk);
            }
        }
        return out;
    }

    /**
     * Returns the world-space centre of a chunk column at a fixed display Y, used for tracers and
     * box placement.
     */
    public static BlockPos chunkCentre(ChunkPos pos, int displayY) {
        return new BlockPos(pos.getMinBlockX() + 8, displayY, pos.getMinBlockZ() + 8);
    }
}
