package com.example.donutflipscanner.market.confidence;

public record ConfidenceWeights(
        int comparableSales,
        int sellerDiversity,
        int priceStability,
        int itemMatch,
        int liquidity,
        int freshness
) {
    public ConfidenceWeights {
        if (comparableSales < 0 || sellerDiversity < 0 || priceStability < 0
                || itemMatch < 0 || liquidity < 0 || freshness < 0) {
            throw new IllegalArgumentException("confidence weights must not be negative");
        }
        if (comparableSales + sellerDiversity + priceStability + itemMatch + liquidity + freshness != 100) {
            throw new IllegalArgumentException("confidence weights must total exactly 100");
        }
    }

    public static ConfidenceWeights defaults() {
        return new ConfidenceWeights(25, 15, 20, 20, 15, 5);
    }
}
