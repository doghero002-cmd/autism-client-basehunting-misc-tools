package com.example.donutflipscanner.market.item.model;

public enum ItemMatchType {
    EXACT(100, false),
    COMMODITY(75, true),
    VISIBLE_METADATA(60, true),
    APPROXIMATE(50, false),
    UNSUPPORTED(0, false);

    private final int defaultQualityScore;
    private final boolean unitPriceBased;

    ItemMatchType(int defaultQualityScore, boolean unitPriceBased) {
        this.defaultQualityScore = defaultQualityScore;
        this.unitPriceBased = unitPriceBased;
    }

    public int defaultQualityScore() {
        return defaultQualityScore;
    }

    public boolean unitPriceBased() {
        return unitPriceBased;
    }
}
