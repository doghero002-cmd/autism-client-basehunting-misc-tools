package com.autism.seedcracker.flip.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/** Median/MAD outliers in log-price space, recency weighting and weighted percentiles. */
public final class RobustStats {

    /** Modified z-score cutoff (Iglewicz-Hoaglin). */
    public static final double OUTLIER_Z_CUTOFF = 3.5;
    private static final double MAD_TO_SIGMA = 1.4826;

    private RobustStats() {}

    public record WeightedValue(double value, double weight) {}

    public record OutlierSplit<T>(List<T> kept, List<T> outliers) {}

    /** Plain median; NaN for an empty list. */
    public static double median(List<Double> values) {
        if (values.isEmpty()) return Double.NaN;
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.naturalOrder());
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2) : (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    public static double mad(List<Double> values, double center) {
        List<Double> deviations = new ArrayList<>(values.size());
        for (double v : values) deviations.add(Math.abs(v - center));
        return median(deviations);
    }

    /**
     * Log space makes a $1 troll sale and a $100M troll listing symmetric problems. Outliers are
     * returned, not dropped, so callers can keep them for audit.
     */
    public static <T> OutlierSplit<T> splitOutliersByLogPrice(List<T> items, ToDoubleFunction<T> priceOf) {
        List<T> positive = new ArrayList<>();
        List<T> invalid = new ArrayList<>();
        for (T item : items) {
            if (priceOf.applyAsDouble(item) > 0) positive.add(item);
            else invalid.add(item);
        }
        if (positive.size() < 3) return new OutlierSplit<>(positive, invalid);

        List<Double> logs = new ArrayList<>(positive.size());
        for (T item : positive) logs.add(Math.log(priceOf.applyAsDouble(item)));
        double med = median(logs);
        double mad = mad(logs, med);

        List<T> kept = new ArrayList<>();
        List<T> outliers = new ArrayList<>(invalid);
        for (int i = 0; i < positive.size(); i++) {
            boolean keep = mad == 0
                ? Math.abs(logs.get(i) - med) <= 1e-9
                : Math.abs(0.6745 * (logs.get(i) - med) / mad) <= OUTLIER_Z_CUTOFF;
            (keep ? kept : outliers).add(positive.get(i));
        }
        return new OutlierSplit<>(kept, outliers);
    }

    /** weight = 2^(-age / halfLife); non-positive age gets full weight. */
    public static double recencyWeight(long ageMillis, long halfLifeMillis) {
        if (ageMillis <= 0) return 1.0;
        if (halfLifeMillis <= 0) throw new IllegalArgumentException("halfLife must be positive");
        return Math.pow(2.0, -((double) ageMillis / halfLifeMillis));
    }

    /** Weighted percentile (0..1) with cumulative-weight interpolation; NaN when no weight. */
    public static double weightedPercentile(List<WeightedValue> samples, double percentile) {
        if (percentile < 0 || percentile > 1) throw new IllegalArgumentException("percentile must be within [0,1]");
        List<WeightedValue> sorted = new ArrayList<>();
        double total = 0;
        for (WeightedValue s : samples) {
            if (s.weight() > 0) {
                sorted.add(s);
                total += s.weight();
            }
        }
        if (sorted.isEmpty() || total <= 0) return Double.NaN;
        sorted.sort(Comparator.comparingDouble(WeightedValue::value));

        double target = percentile * total;
        double cumulative = 0;
        for (int i = 0; i < sorted.size(); i++) {
            double next = cumulative + sorted.get(i).weight();
            if (next >= target) {
                if (i == 0 || next == target) return sorted.get(i).value();
                double prevValue = sorted.get(i - 1).value();
                double fraction = (target - cumulative) / sorted.get(i).weight();
                return prevValue + (sorted.get(i).value() - prevValue) * Math.min(1.0, fraction);
            }
            cumulative = next;
        }
        return sorted.get(sorted.size() - 1).value();
    }

    /** Fractional price scatter from the MAD of log prices (0.04 = about +-4%); NaN under 3 prices. */
    public static double robustLogVolatility(List<Double> prices) {
        List<Double> logs = new ArrayList<>();
        for (double p : prices) if (p > 0) logs.add(Math.log(p));
        if (logs.size() < 3) return Double.NaN;
        double med = median(logs);
        return Math.expm1(mad(logs, med) * MAD_TO_SIGMA);
    }
}
