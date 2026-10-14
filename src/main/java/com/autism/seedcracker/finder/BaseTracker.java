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

    // Finders report on a ~1s cadence (see StashFinder's reportTicks), so the TTL must outlive two
    // report intervals or every entry expires between reports and the HUD flickers.
    private static final long TTL_MS = 2_500L;

    public record Entry(int blockX, int blockZ, int confidence, String source, long lastMs) {}

    private record Key(int chunkX, int chunkZ, String source) {}
    private static final Map<Key, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static long lastPruneMs = Long.MIN_VALUE;

    private BaseTracker() {}

    /** Report a flagged chunk. Call every tick while the chunk is flagged. */
    public static void report(int blockX, int blockZ, int confidence, String source) {
        if (storeReport(blockX, blockZ, confidence, source, System.currentTimeMillis())) BaseAlerts.onReport(blockX, blockZ);
    }

    static boolean storeReport(int blockX, int blockZ, int confidence, String source, long now) {
        if (source == null || source.isBlank() || confidence <= 0) return false;
        if (lastPruneMs == Long.MIN_VALUE || now < lastPruneMs || now - lastPruneMs >= 1000) prune(now);
        String name = source.trim();
        ENTRIES.put(new Key(blockX >> 4, blockZ >> 4, name), new Entry(blockX, blockZ, Math.min(100, confidence), name, now));
        return true;
    }

    private static void prune(long now) {
        ENTRIES.values().removeIf(entry -> now < entry.lastMs() || now - entry.lastMs() >= TTL_MS);
        lastPruneMs = now;
    }

    static int retainedEntries() { return ENTRIES.size(); }

    /** A cluster of nearby finder reports fused into one row. */
    public record Fused(int blockX, int blockZ, int confidence, String sources, double distSq) {}

    /** Cluster radius: entries within this many blocks merge into one fused row. */
    static final int CLUSTER_RADIUS_BLOCKS = 48;

    /**
    * Live entries clustered by proximity (3-chunk radius), retaining the strongest score
    * and its member finder names, sorted nearest-first.
     */
    public static List<Fused> fusedNearest(double playerX, double playerZ, int limit) {
        return fusedNearestAt(playerX, playerZ, limit, System.currentTimeMillis());
    }

    public static Fused nearestCandidate(double playerX, double playerZ, int minimumScore, double radius) {
        return nearestCandidateAt(playerX, playerZ, minimumScore, radius, System.currentTimeMillis());
    }

    static Fused nearestCandidateAt(double playerX, double playerZ, int minimumScore, double radius, long now) {
        if (!Double.isFinite(radius) || radius < 0) return null;
        for (Fused candidate : fusedNearestAt(playerX, playerZ, Integer.MAX_VALUE, now)) {
            if (candidate.distSq() > radius * radius) break;
            if (candidate.confidence() >= minimumScore) return candidate;
        }
        return null;
    }

    static List<Fused> fusedNearestAt(double playerX, double playerZ, int limit, long now) {
        if (limit <= 0) return List.of();
        prune(now);
        List<Entry> live = ENTRIES.values().stream()
            .sorted(java.util.Comparator.comparingInt(Entry::blockX).thenComparingInt(Entry::blockZ).thenComparing(Entry::source))
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
                long deltaX = (long) e.blockX() - seed.blockX();
                long deltaZ = (long) e.blockZ() - seed.blockZ();
                if (Math.abs(deltaX) <= CLUSTER_RADIUS_BLOCKS && Math.abs(deltaZ) <= CLUSTER_RADIUS_BLOCKS
                    && deltaX * deltaX + deltaZ * deltaZ <= (long) CLUSTER_RADIUS_BLOCKS * CLUSTER_RADIUS_BLOCKS) {
                    cluster.add(e);
                    used[j] = true;
                }
            }
            java.util.Map<String, Integer> bySource = new java.util.LinkedHashMap<>();
            Entry strongest = seed;
            for (Entry e : cluster) {
                bySource.merge(e.source(), e.confidence(), Math::max);
                if (e.confidence() > strongest.confidence()) strongest = e;
            }
            int fusedConf = strongest.confidence();
            int cx = strongest.blockX();
            int cz = strongest.blockZ();
            double dx = cx - playerX, dz = cz - playerZ;
            out.add(new Fused(cx, cz, fusedConf, String.join("+", bySource.keySet()), dx * dx + dz * dz));
        }
        out.sort(java.util.Comparator.comparingDouble(Fused::distSq));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Snapshot of live entries, sorted nearest-first to the given point. */
    public static List<Entry> nearest(double playerX, double playerZ, int limit) {
        return nearestAt(playerX, playerZ, limit, System.currentTimeMillis());
    }

    static List<Entry> nearestAt(double playerX, double playerZ, int limit, long now) {
        if (limit <= 0) return List.of();
        prune(now);
        return ENTRIES.values().stream()
            .sorted((a, b) -> {
                double da = distSq(a, playerX, playerZ);
                double db = distSq(b, playerX, playerZ);
                return Double.compare(da, db);
            })
            .limit(limit)
            .toList();
    }

    private static double distSq(Entry e, double px, double pz) {
        double dx = e.blockX() - px;
        double dz = e.blockZ() - pz;
        return dx * dx + dz * dz;
    }

    public static void clear() {
        ENTRIES.clear();
        lastPruneMs = Long.MIN_VALUE;
    }
}
