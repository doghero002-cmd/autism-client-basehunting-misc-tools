package com.autism.seedcracker.finder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;

/**
 * Per-finder BaseTracker reporter: once a second, scores each flagged chunk with
 * {@link BaseConfidence} (cached - it walks every chunk section) and reports it under the
 * finder's name so the Base Tracker HUD / fusion sees all finders, not just StashFinder.
 */
public final class FinderReport {
    private final String source;
    /** Confidence floor: the finder's own signal is evidence even when the chunk's block mix is bland. */
    private final int floor;
    private final Map<ChunkPos, Integer> cache = new ConcurrentHashMap<>();
    private int ticks;

    public FinderReport(String source, int floor) {
        this.source = source;
        this.floor = floor;
    }

    /** Call every tick with the current flagged set. */
    public void tick(Minecraft mc, Set<ChunkPos> flagged) {
        if (mc.level == null || flagged.isEmpty()) return;
        if (++ticks < 20) return;
        ticks = 0;
        cache.keySet().retainAll(flagged);
        for (ChunkPos pos : flagged) {
            int conf = cache.computeIfAbsent(pos, p -> {
                if (!mc.level.hasChunk(p.x(), p.z())) return floor;
                int scored = BaseConfidence.score(mc.level.getChunk(p.x(), p.z())).score();
                return Math.max(floor, scored);
            });
            BaseTracker.report(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, conf, source);
        }
    }

    public void clear() {
        cache.clear();
        ticks = 0;
    }
}
