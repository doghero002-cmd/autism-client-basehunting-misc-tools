package com.example.donutflipscanner.market.statistics.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record ComparableSale(
        String saleKey,
        BigDecimal comparablePrice,
        int itemCount,
        Optional<String> sellerIdentity,
        Optional<String> buyerIdentity,
        Instant soldAt,
        BigDecimal recencyWeight
) {
    public ComparableSale {
        Objects.requireNonNull(saleKey, "saleKey");
        Objects.requireNonNull(comparablePrice, "comparablePrice");
        if (comparablePrice.signum() < 0) {
            throw new IllegalArgumentException("comparablePrice must not be negative");
        }
        if (itemCount < 1) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        sellerIdentity = sellerIdentity == null ? Optional.empty() : sellerIdentity;
        buyerIdentity = buyerIdentity == null ? Optional.empty() : buyerIdentity;
        Objects.requireNonNull(soldAt, "soldAt");
        Objects.requireNonNull(recencyWeight, "recencyWeight");
        if (recencyWeight.signum() <= 0) {
            throw new IllegalArgumentException("recencyWeight must be positive");
        }
    }
}
