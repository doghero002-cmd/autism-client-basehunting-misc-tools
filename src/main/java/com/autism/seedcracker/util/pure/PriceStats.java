package com.autism.seedcracker.util.pure;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Price-history statistics (pure, no I/O, unit tested).
 *
 * All the math behind the Price Tracker lives here so it can be read and tested in isolation:
 *  - {@link #summarize}: min / median / mean / max over all samples, plus a recent-window median
 *  - {@link #dealScore}: how far below the recent median a price is (0.45 = 45% under market)
 *  - {@link #isDuplicateListing}: dedup rule for re-rendered AH pages
 *  - {@link #normalizeItemId}: canonical item key ("minecraft:oak_log" -> "oak_log")
 */
public final class PriceStats {
    private PriceStats() {}

    /** One observed listing: unit price + when it was seen (epoch ms). */
    public record Sample(double unitPrice, long seenAtMs) {}

    /** Summary over a sample list. recentMedian falls back to the all-time median. */
    public record Summary(int samples, double min, double median, double mean, double max, double recentMedian) {}

    /**
     * Compute summary stats. {@code recentWindowMs} bounds the recent-median window
     * (samples with {@code seenAtMs >= nowMs - recentWindowMs}).
     *
     * @return null when the list is empty (no data = no stats, callers branch on it)
     */
    public static Summary summarize(List<Sample> samples, long nowMs, long recentWindowMs) {
        if (samples == null || samples.isEmpty()) return null;
        List<Double> all = new ArrayList<>(samples.size());
        List<Double> recent = new ArrayList<>();
        long cutoff = nowMs - recentWindowMs;
        for (Sample s : samples) {
            all.add(s.unitPrice());
            if (s.seenAtMs() >= cutoff) recent.add(s.unitPrice());
        }
        Collections.sort(all);
        double min = all.get(0);
        double max = all.get(all.size() - 1);
        double median = percentile(all, 0.5);
        double mean = 0;
        for (double v : all) mean += v;
        mean /= all.size();
        double recentMedian = median;
        if (!recent.isEmpty()) {
            Collections.sort(recent);
            recentMedian = percentile(recent, 0.5);
        }
        return new Summary(all.size(), min, median, mean, max, recentMedian);
    }

    /**
     * Deal rating: fraction below the recent median (0.45 = 45% under market, negative = above).
     *
     * @return NaN when the median is unknown or non-positive
     */
    public static double dealScore(double unitPrice, double recentMedian) {
        if (recentMedian <= 0 || Double.isNaN(recentMedian)) return Double.NaN;
        return 1.0 - unitPrice / recentMedian;
    }

    /**
     * True when a new observation is the SAME listing re-rendered, not new market data:
     * an identical unit price seen within the dedup window. Checks the most recent
     * {@code lookback} samples only (AH pages re-render often; old samples can't collide).
     */
    public static boolean isDuplicateListing(List<Sample> existing, double unitPrice,
                                             long nowMs, long dedupWindowMs, int lookback) {
        if (existing == null || existing.isEmpty()) return false;
        for (int i = existing.size() - 1; i >= 0 && i >= existing.size() - lookback; i--) {
            Sample s = existing.get(i);
            if (nowMs - s.seenAtMs() > dedupWindowMs) break; // older than the window: stop
            if (Math.abs(s.unitPrice() - unitPrice) < 0.001) return true;
        }
        return false;
    }

    /** Canonical item key: trimmed, lowercase, "minecraft:" prefix stripped. */
    public static String normalizeItemId(String itemId) {
        String k = itemId.trim().toLowerCase(java.util.Locale.ROOT);
        return k.startsWith("minecraft:") ? k.substring("minecraft:".length()) : k;
    }

    /** Nearest-rank percentile over a pre-sorted list (p in 0..1). */
    static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.round(p * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }
}
