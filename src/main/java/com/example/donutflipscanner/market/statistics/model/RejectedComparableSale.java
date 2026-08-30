package com.example.donutflipscanner.market.statistics.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

public record RejectedComparableSale(
        String saleKey,
        ComparableSaleRejectionReason reason,
        Optional<BigDecimal> comparablePrice,
        String explanation
) {
    public RejectedComparableSale {
        Objects.requireNonNull(saleKey, "saleKey");
        Objects.requireNonNull(reason, "reason");
        comparablePrice = comparablePrice == null ? Optional.empty() : comparablePrice;
        explanation = Objects.requireNonNull(explanation, "explanation");
    }
}
