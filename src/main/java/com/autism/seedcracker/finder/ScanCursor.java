package com.autism.seedcracker.finder;

import java.util.Collections;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Budgeted chunk-scan cursor.
 *
 * Spreads a full chunk-bubble scan across multiple ticks so the game never does a full-volume scan
 * in a single tick (the FPS spike / lag source). Keeps a queue of chunks around the player and an
 * index into it; each call to {@link #nextBatch} returns up to {@code perTick} chunks, refreshing
 * the queue once it's exhausted (and the rescan delay has passed).
 *
 * One instance per finder module. Usage:
 *   private final ScanCursor scan = new ScanCursor();
 *   // in tick():
 *   for (LevelChunk chunk : scan.nextBatch(mc, scanRadius.get(), rescanMs.get(), chunksPerTick.get())) {
 *       scanChunk(mc, chunk);
 *   }
 */
public final class ScanCursor {
    private List<LevelChunk> queue = Collections.emptyList();
    private int index = 0;
    private long lastRefreshMs = 0;

    /**
     * Return the next batch of up to {@code perTick} chunks to scan this tick. Refreshes the queue
     * when the previous pass finished AND {@code rescanMs} has elapsed since the last refresh.
     */
    public List<LevelChunk> nextBatch(Minecraft mc, int radius, long rescanMs, int perTick) {
        long now = System.currentTimeMillis();
        if (index >= queue.size() && now - lastRefreshMs >= rescanMs) {
            queue = ChunkScanHelper.loadedChunksAround(mc, radius);
            index = 0;
            lastRefreshMs = now;
        }
        if (index >= queue.size()) return Collections.emptyList();
        int end = Math.min(queue.size(), index + Math.max(1, perTick));
        List<LevelChunk> batch = queue.subList(index, end);
        index = end;
        return batch;
    }

    /** Progress through the current pass (0.0 - 1.0), for an optional info readout. */
    public double progress() {
        return queue.isEmpty() ? 1.0 : index / (double) queue.size();
    }

    /** Reset (call on enable/disable or radius change). */
    public void reset() {
        queue = Collections.emptyList();
        index = 0;
        lastRefreshMs = 0;
    }
}
