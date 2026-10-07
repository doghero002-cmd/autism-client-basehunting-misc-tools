package com.autism.seedcracker.util.pure;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/**
 * "When are the best deals?" analysis (pure, unit tested).
 *
 * Buckets price samples by hour-of-day (local time) and reports each hour's median unit price
 * against the overall median. Hours whose median sits well below the overall median are when
 * sellers dump cheaply (often late night / right after a server restart) - the best time to buy.
 */
public final class DealTiming {
    private DealTiming() {}

    /** Per-hour summary: sample count + median unit price (NaN when the hour has no samples). */
    public record HourStat(int hour, int samples, double median) {}

    /** 24 HourStats (index = hour 0..23) over the samples, in the given zone. */
    public static HourStat[] byHour(List<PriceStats.Sample> samples, ZoneId zone) {
        @SuppressWarnings("unchecked")
        java.util.List<Double>[] buckets = new java.util.List[24];
        for (int h = 0; h < 24; h++) buckets[h] = new java.util.ArrayList<>();
        if (samples != null) {
            for (PriceStats.Sample s : samples) {
                int hour = Instant.ofEpochMilli(s.seenAtMs()).atZone(zone).getHour();
                buckets[hour].add(s.unitPrice());
            }
        }
        HourStat[] out = new HourStat[24];
        for (int h = 0; h < 24; h++) {
            List<Double> b = buckets[h];
            if (b.isEmpty()) {
                out[h] = new HourStat(h, 0, Double.NaN);
            } else {
                java.util.Collections.sort(b);
                out[h] = new HourStat(h, b.size(), PriceStats.percentile(b, 0.5));
            }
        }
        return out;
    }

    /**
     * The hour with the lowest median among hours with at least {@code minSamples} samples,
     * or -1 when no hour qualifies (not enough data to say anything honest).
     */
    public static int cheapestHour(HourStat[] hours, int minSamples) {
        int best = -1;
        double bestMedian = Double.MAX_VALUE;
        for (HourStat h : hours) {
            if (h.samples() < minSamples || Double.isNaN(h.median())) continue;
            if (h.median() < bestMedian) {
                bestMedian = h.median();
                best = h.hour();
            }
        }
        return best;
    }

    /**
     * How much cheaper the given hour is than the overall median, as a fraction
     * (0.2 = 20% cheaper). NaN when either value is unknown.
     */
    public static double hourDiscount(HourStat hour, double overallMedian) {
        if (hour == null || Double.isNaN(hour.median()) || overallMedian <= 0) return Double.NaN;
        return 1.0 - hour.median() / overallMedian;
    }
}
