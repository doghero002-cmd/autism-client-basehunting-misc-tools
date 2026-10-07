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
        BaseAlerts.onReport(blockX, blockZ);
    }

    /** A cluster of nearby finder reports fused into one row. */
    public record Fused(int blockX, int blockZ, int confidence, String sources, double distSq) {}

    /** Cluster radius: entries within this many blocks merge into one fused row. */
    static final int CLUSTER_RADIUS_BLOCKS = 48;

    /**
     * Live entries clustered by proximity (3-chunk radius), each cluster fused with noisy-OR
     * confidence and its member finder names joined, sorted nearest-first.
     */
    public static List<Fused> fusedNearest(double playerX, double playerZ, int limit) {
        long now = System.currentTimeMillis();
        List<Entry> live = ENTRIES.values().stream()
            .filter(e -> now - e.lastMs() < TTL_MS)
            .toList();
        List<Fused> out = new java.util.ArrayList<>();
        boolean[] used = new boolean[live.size()];
        for (int i = 0; i < live.size(); i++) {
            if (used[i]) continue;
            Entry seed = live.get(i);
            java.util.List<Entry> cluster = new java.util.ArrayList<>();
            cluster.add(seed);
            used[i] = true;
            for (int j = i + 1; j < live.size(); j++) {
                if (used[j]) continue;
                Entry e = live.get(j);
                if (Math.abs(e.blockX() - seed.blockX()) <= CLUSTER_RADIUS_BLOCKS
                    && Math.abs(e.blockZ() - seed.blockZ()) <= CLUSTER_RADIUS_BLOCKS) {
                    cluster.add(e);
                    used[j] = true;
                }
            }
            // Centroid position; one confidence per distinct source (a finder re-reporting the
            // same chunk shouldn't double-count itself in the fusion).
            java.util.Map<String, Integer> bySource = new java.util.LinkedHashMap<>();
            long sx = 0, sz = 0;
            for (Entry e : cluster) {
                bySource.merge(e.source(), e.confidence(), Math::max);
                sx += e.blockX();
                sz += e.blockZ();
            }
            int[] confs = bySource.values().stream().mapToInt(Integer::intValue).toArray();
            int fusedConf = com.autism.seedcracker.util.pure.Fusion.fuse(confs);
            int cx = (int) (sx / cluster.size());
            int cz = (int) (sz / cluster.size());
            double dx = cx - playerX, dz = cz - playerZ;
            out.add(new Fused(cx, cz, fusedConf, String.join("+", bySource.keySet()), dx * dx + dz * dz));
        }
        out.sort(java.util.Comparator.comparingDouble(Fused::distSq));
        return out.size() > limit ? out.subList(0, Math.max(1, limit)) : out;
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
