package com.autism.seedcracker.finder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared base-detection registry for the Base Tracker HUD.
 *
 * Finder modules report their flagged chunks here (position + confidence + which finder raised
 * it) and the {@code BaseTrackerHud} reads a merged, distance-sorted view. Entries expire quickly
 * (finders re-report every tick), so when a finder un-flags a chunk or is disabled its entry
 * disappears on its own.
 */
public final class BaseTracker {

    private static final long TTL_MS = 500L;

    public record Entry(int blockX, int blockZ, int confidence, String source, long lastMs) {}

    private static final Map<Long, Entry> ENTRIES = new ConcurrentHashMap<>();

    private BaseTracker() {}

    private static long key(int blockX, int blockZ, String source) {
        // Keyed by chunk + source finder so two finders can report the same chunk independently.
        long cx = blockX >> 4, cz = blockZ >> 4;
        return (((long) cx) << 32) ^ (cz & 0xffffffffL) ^ (long) source.hashCode();
    }

    /** Report a flagged chunk. Call every tick while the chunk is flagged. */
    public static void report(int blockX, int blockZ, int confidence, String source) {
        ENTRIES.put(key(blockX, blockZ, source),
            new Entry(blockX, blockZ, confidence, source, System.currentTimeMillis()));
    }

    /** Snapshot of live entries, sorted nearest-first to the given point. */
    public static List<Entry> nearest(double playerX, double playerZ, int limit) {
        long now = System.currentTimeMillis();
        return ENTRIES.values().stream()
            .filter(e -> now - e.lastMs() < TTL_MS)
            .sorted((a, b) -> {
                double da = distSq(a, playerX, playerZ);
                double db = distSq(b, playerX, playerZ);
                return Double.compare(da, db);
            })
            .limit(Math.max(1, limit))
            .toList();
    }

    private static double distSq(Entry e, double px, double pz) {
        double dx = e.blockX() - px;
        double dz = e.blockZ() - pz;
        return dx * dx + dz * dz;
    }

    public static void clear() {
        ENTRIES.clear();
    }
}
