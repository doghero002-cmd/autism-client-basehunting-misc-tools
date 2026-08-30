package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.market.item.model.NormalizedItem;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Objects;
import java.util.Optional;

/** Precise stack arithmetic for item tiers whose comparable prices are normalized per item. */
public final class StackPriceNormalizer {
    private static final MathContext PRICE_CONTEXT = MathContext.DECIMAL128;

    public Optional<BigDecimal> unitPrice(NormalizedItem item, BigDecimal totalPrice) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(totalPrice, "totalPrice");
        if (totalPrice.signum() < 0) {
            throw new IllegalArgumentException("totalPrice must not be negative");
        }
        if (!item.unitPriceEligible()) {
            return Optional.empty();
        }
        return Optional.of(canonical(totalPrice.divide(
                BigDecimal.valueOf(item.stackCount().getAsInt()),
                PRICE_CONTEXT
        )));
    }

    public BigDecimal totalForCount(BigDecimal unitPrice, int count) {
        Objects.requireNonNull(unitPrice, "unitPrice");
        if (unitPrice.signum() < 0 || count < 1) {
            throw new IllegalArgumentException("unitPrice must be non-negative and count must be positive");
        }
        return canonical(unitPrice.multiply(BigDecimal.valueOf(count), PRICE_CONTEXT));
    }

    private BigDecimal canonical(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.signum() == 0 ? BigDecimal.ZERO : new BigDecimal(normalized.toPlainString());
    }
}
