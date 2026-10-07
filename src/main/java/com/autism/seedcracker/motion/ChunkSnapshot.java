package com.autism.seedcracker.motion;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

/**
 * The loaded chunks around the player, captured on the game thread so the planner thread can read
 * blocks without touching the client level. Holds the chunks' section arrays: a block that changes
 * after capture may read either way, which the follower's per-tick re-check catches.
 */
final class ChunkSnapshot implements BlockGetter {
    /** Read mid-write by the game thread (rare palette race): treat as an unbreakable wall, the follower re-checks. */
    private static final BlockState UNKNOWN = Blocks.BEDROCK.defaultBlockState();

    private final LevelChunkSection[][] grid;
    private final int originX, originZ, side;
    private final int minY, height;
    // Single reader thread per snapshot: the last chunk looked up, since neighbours are almost always in it.
    private int lastCx = Integer.MIN_VALUE, lastCz = Integer.MIN_VALUE;
    private LevelChunkSection[] last;

    private ChunkSnapshot(LevelChunkSection[][] grid, int originX, int originZ, int side, int minY, int height) {
        this.grid = grid;
        this.originX = originX;
        this.originZ = originZ;
        this.side = side;
        this.minY = minY;
        this.height = height;
    }

    static ChunkSnapshot capture(Minecraft mc, int radiusChunks) {
        int pcx = mc.player.getBlockX() >> 4, pcz = mc.player.getBlockZ() >> 4;
        int side = radiusChunks * 2 + 1;
        LevelChunkSection[][] grid = new LevelChunkSection[side * side][];
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = pcx + dx, cz = pcz + dz;
                if (!mc.level.hasChunk(cx, cz)) continue;
                grid[(dx + radiusChunks) * side + dz + radiusChunks] = mc.level.getChunk(cx, cz).getSections();
            }
        }
        return new ChunkSnapshot(grid, pcx - radiusChunks, pcz - radiusChunks, side, mc.level.getMinY(), mc.level.getHeight());
    }

    private LevelChunkSection[] sections(int cx, int cz) {
        if (cx == lastCx && cz == lastCz) return last;
        int ix = cx - originX, iz = cz - originZ;
        LevelChunkSection[] s = ix < 0 || iz < 0 || ix >= side || iz >= side ? null : grid[ix * side + iz];
        lastCx = cx;
        lastCz = cz;
        last = s;
        return s;
    }

    boolean hasChunk(int cx, int cz) {
        return sections(cx, cz) != null || com.autism.seedcracker.motion.ChunkCache.hasChunk(cx, cz);
    }

    BlockState state(int x, int y, int z) {
        LevelChunkSection[] secs = sections(x >> 4, z >> 4);
        int i = (y >> 4) - (minY >> 4);
        if (secs == null) {
            // Not a loaded chunk: fall back to the disk/memory chunk cache (blocks seen earlier, now
            // beyond render distance). Null = never seen -> the planner treats it as UNLOADED.
            BlockState cached = com.autism.seedcracker.motion.ChunkCache.state(x, y, z);
            return cached == null ? Blocks.AIR.defaultBlockState() : cached;
        }
        if (i < 0 || i >= secs.length) return Blocks.AIR.defaultBlockState();
        LevelChunkSection sec = secs[i];
        if (sec == null || sec.hasOnlyAir()) return Blocks.AIR.defaultBlockState();
        try {
            BlockState s = sec.getBlockState(x & 15, y & 15, z & 15);
            return s == null ? UNKNOWN : s;
        } catch (RuntimeException e) {
            // A palette id the game thread is mid-way through adding: the follower re-checks this cell anyway.
            return UNKNOWN;
        }
    }

    @Override
    public BlockState getBlockState(BlockPos pos) {
        return state(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public FluidState getFluidState(BlockPos pos) {
        return getBlockState(pos).getFluidState();
    }

    @Override
    public BlockEntity getBlockEntity(BlockPos pos) {
        return null;
    }

    @Override
    public int getHeight() {
        return height;
    }

    @Override
    public int getMinY() {
        return minY;
    }
}
