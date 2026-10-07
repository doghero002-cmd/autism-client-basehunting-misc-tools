package com.autism.seedcracker.flip.core;

import java.util.List;

/** Value types shared by the flip core. Prices are whole coins for the full stack. */
public final class FlipModel {
    private FlipModel() {}

    /** x16/x32/x64 stacks can be separate markets; anything else is priced per unit. */
    public enum StackBucket {
        X1(1), X16(16), X32(32), X64(64), OTHER(-1);

        private final int exactCount;

        StackBucket(int exactCount) {
            this.exactCount = exactCount;
        }

        public static StackBucket of(int count) {
            return switch (count) {
                case 1 -> X1;
                case 16 -> X16;
                case 32 -> X32;
                case 64 -> X64;
                default -> OTHER;
            };
        }

        public int exactCount() {
            return exactCount;
        }

        public String label() {
            return this == OTHER ? "other" : "x" + exactCount;
        }
    }

    /** Where an opportunity's resale value came from, strongest evidence first. */
    public enum Basis { SALES, INFERRED, ASKS }

    /** A completed sale; {@code inferred} = a listing that vanished between two full keyless scans. */
    public record Sale(String saleKey, long soldAt, String seller, String itemKey, int count,
                       long totalPrice, boolean inferred) {
        public double unitPrice() {
            return (double) totalPrice / count;
        }

        public StackBucket bucket() {
            return StackBucket.of(count);
        }

        public boolean isValid() {
            return totalPrice > 0 && count > 0 && soldAt > 0 && itemKey != null && !itemKey.isBlank();
        }
    }

    /** An active listing observed at {@code observedAt}. */
    public record Listing(String listingKey, long observedAt, String seller, String itemKey, int count,
                          long totalPrice) {
        public double unitPrice() {
            return (double) totalPrice / count;
        }

        public StackBucket bucket() {
            return StackBucket.of(count);
        }

        public boolean isValid() {
            return totalPrice > 0 && count > 0 && itemKey != null && !itemKey.isBlank();
        }
    }

    /** Exact buckets are priced per stack; OTHER per unit. */
    public record MarketStats(
        String itemKey,
        StackBucket bucket,
        int sampleCount,
        int outlierCount,
        int uniqueSellers,
        long newestSaleAt,
        double lowerBound,
        double quickSalePrice,
        double weightedMedian,
        double patientSalePrice,
        double upperBound,
        double salesPerHour,
        double robustVolatility,
        double trend,
        double confidence,
        double inferredShare,
        long calculatedAt
    ) {
        public boolean hasPrices() {
            return sampleCount > 0 && quickSalePrice > 0 && Double.isFinite(quickSalePrice)
                && weightedMedian > 0 && Double.isFinite(weightedMedian);
        }
    }

    /** A listing the core believes can be bought and conservatively resold; reasons explain why. */
    public record Opportunity(
        Listing listing,
        MarketStats stats,
        Basis basis,
        long buyPrice,
        long sellPrice,
        double expectedProfit,
        double roiPercent,
        double holdHours,
        double saleProbability,
        double confidence,
        double score,
        List<String> reasons
    ) {
        public Opportunity {
            reasons = List.copyOf(reasons);
        }
    }
}
