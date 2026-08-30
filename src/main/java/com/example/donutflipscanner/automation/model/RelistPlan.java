package com.example.donutflipscanner.automation.model;

import java.math.BigDecimal;
import java.util.Objects;

public record RelistPlan(BigDecimal listingPrice, RelistPricingStrategy strategy, String explanation) {
    public RelistPlan {
        Objects.requireNonNull(listingPrice, "listingPrice");
        Objects.requireNonNull(strategy, "strategy");
        explanation = Objects.requireNonNullElse(explanation, "");
        if (listingPrice.signum() <= 0) {
            throw new IllegalArgumentException("relist price must be positive");
        }
    }
}
