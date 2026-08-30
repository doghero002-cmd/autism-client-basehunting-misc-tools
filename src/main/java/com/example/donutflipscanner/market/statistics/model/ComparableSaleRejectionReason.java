package com.example.donutflipscanner.market.statistics.model;

public enum ComparableSaleRejectionReason {
    UNSUPPORTED_ITEM,
    FINGERPRINT_MISMATCH,
    OUTSIDE_LOOKBACK,
    FUTURE_TIMESTAMP,
    INVALID_PRICE,
    INVALID_ITEM_COUNT,
    MAD_OUTLIER,
    IQR_OUTLIER
}
