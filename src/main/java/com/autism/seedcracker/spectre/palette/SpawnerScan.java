// SPDX-License-Identifier: GPL-3.0-only
package com.autism.seedcracker.spectre.palette;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import com.autism.seedcracker.finder.ChunkScanHelper;
import com.autism.seedcracker.finder.ScanCursor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;

/** Spectre ChunkScanner's spawner consumer, using the addon's existing scan budget/cursor. */
public final class SpawnerScan {
    public static final int MAX_SPAWNERS = 4096;
    private final ScanCursor cursor = new ScanCursor();
    private final Set<BlockPos> visible = new LinkedHashSet<>();
    private ClientLevel level;
    private int radius;

    public void clear() {
        cursor.reset();
        visible.clear();
        level = null;
    }

    public Set<BlockPos> tick(Minecraft mc, int chunkRadius, int chunksPerTick) {
        if (mc.level != level || radius != chunkRadius) {
            clear();
            level = mc.level;
            radius = chunkRadius;
        }
        if (level == null || mc.player == null) return Set.of();
        ChunkPos center = mc.player.chunkPosition();
        visible.removeIf(pos -> !inRange(pos, center, chunkRadius) || !loaded(level, pos)
            || !level.getBlockState(pos).is(Blocks.SPAWNER));
        for (var chunk : cursor.nextBatch(mc, chunkRadius, 1000, chunksPerTick)) {
            if (level.getChunkSource().getChunkNow(chunk.getPos().x(), chunk.getPos().z()) != chunk) continue;
            ChunkScanHelper.forEachBlockInChunk(chunk, state -> state.is(Blocks.SPAWNER), pos -> {
                if (visible.size() < MAX_SPAWNERS) visible.add(pos);
            });
        }
        Set<BlockPos> result = new HashSet<>(visible);
        for (var entry : BlockEntityCapture.snapshot(level)) {
            if (!entry.blockId().equals("minecraft:spawner")) continue;
            BlockPos pos = BlockEntityCapture.blockPos(entry);
            if (result.size() < MAX_SPAWNERS && inRange(pos, center, chunkRadius) && loaded(level, pos)) result.add(pos);
        }
        return Set.copyOf(result);
    }

    public static boolean loaded(ClientLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    private static boolean inRange(BlockPos pos, ChunkPos center, int radius) {
        return Math.abs((long) (pos.getX() >> 4) - center.x()) <= radius
            && Math.abs((long) (pos.getZ() >> 4) - center.z()) <= radius;
    }
}
