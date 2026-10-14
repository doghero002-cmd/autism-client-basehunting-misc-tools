package com.autism.seedcracker.finder;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

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
    private record Sample(int confidence, long tick) {}
    private final Map<ChunkPos, Sample> cache = new ConcurrentHashMap<>();
    private Object world;
    private long age;
    private int ticks;

    public FinderReport(String source, int floor) {
        this.source = source;
        this.floor = floor;
    }

    /** Call every tick with the current flagged set. */
    public void tick(Minecraft mc, Set<ChunkPos> flagged) {
        var level = mc.level;
        tick(level, flagged, pos -> level.hasChunk(pos.x(), pos.z()),
            pos -> BaseConfidence.score(level.getChunk(pos.x(), pos.z())).score(),
            (pos, confidence) -> BaseTracker.report(pos.getMinBlockX() + 8, pos.getMinBlockZ() + 8, confidence, source));
    }

    void tick(Object currentWorld, Set<ChunkPos> flagged, Predicate<ChunkPos> loaded,
              ToIntFunction<ChunkPos> scorer, BiConsumer<ChunkPos, Integer> reporter) {
        if (world != currentWorld) {
            clear();
            world = currentWorld;
        }
        if (currentWorld == null || flagged.isEmpty()) {
            clear();
            return;
        }
        age++;
        cache.keySet().removeIf(pos -> !flagged.contains(pos) || !loaded.test(pos));
        ChunkPos candidate = null;
        long oldest = Long.MAX_VALUE;
        for (ChunkPos pos : flagged) {
            if (!loaded.test(pos)) continue;
            Sample sample = cache.get(pos);
            if (sample == null) {
                candidate = pos;
                break;
            }
            if (age - sample.tick() >= 100 && sample.tick() < oldest) {
                candidate = pos;
                oldest = sample.tick();
            }
        }
        if (candidate != null) cache.put(candidate, new Sample(Math.max(floor, scorer.applyAsInt(candidate)), age));
        if (++ticks < 20) return;
        ticks = 0;
        for (var entry : cache.entrySet()) {
            if (age - entry.getValue().tick() < 100) reporter.accept(entry.getKey(), entry.getValue().confidence());
        }
    }

    public void clear() {
        cache.clear();
        ticks = 0;
        age = 0;
        world = null;
    }
}
