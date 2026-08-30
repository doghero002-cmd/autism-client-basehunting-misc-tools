package com.example.donutflipscanner.profit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, render-safe view of confirmed purchases and matched completed sales. */
public record PersonalProfitSnapshot(
        BigDecimal realizedProfit,
        BigDecimal realizedAcquisitionCost,
        BigDecimal saleProceeds,
        int openPositions,
        int realizedTrades,
        List<PersonalProfitPoint> points,
        Optional<Instant> refreshedAt
) {
    public PersonalProfitSnapshot {
        Objects.requireNonNull(realizedProfit, "realizedProfit");
        Objects.requireNonNull(realizedAcquisitionCost, "realizedAcquisitionCost");
        Objects.requireNonNull(saleProceeds, "saleProceeds");
        if (openPositions < 0 || realizedTrades < 0) {
            throw new IllegalArgumentException("profit counts must not be negative");
        }
        points = List.copyOf(Objects.requireNonNull(points, "points"));
        refreshedAt = Objects.requireNonNullElse(refreshedAt, Optional.empty());
    }

    public static PersonalProfitSnapshot empty() {
        return new PersonalProfitSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                0, 0, List.of(), Optional.empty()
        );
    }

    public double returnPercent() {
        if (realizedAcquisitionCost.signum() <= 0) {
            return 0.0D;
        }
        return realizedProfit
                .multiply(BigDecimal.valueOf(100L))
                .divide(realizedAcquisitionCost, 4, java.math.RoundingMode.HALF_UP)
                .doubleValue();
    }
}
