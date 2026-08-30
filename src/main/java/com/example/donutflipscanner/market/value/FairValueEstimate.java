package com.example.donutflipscanner.market.value;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FairValueEstimate(
        String itemFingerprint,
        int targetItemCount,
        boolean unitPriceBased,
        Optional<BigDecimal> conservativeValue,
        Optional<BigDecimal> centralValue,
        Optional<BigDecimal> optimisticValue,
        Optional<BigDecimal> observedLow,
        Optional<BigDecimal> observedHigh,
        MarketTrend trend,
        boolean sufficientData,
        BigDecimal recommendedSafetyBufferMultiplier,
        List<FairValueWarning> warnings,
        FairValueExplanation explanation
) {
    public FairValueEstimate {
        itemFingerprint = Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        if (targetItemCount < 1) {
            throw new IllegalArgumentException("targetItemCount must be positive");
        }
        conservativeValue = optional(conservativeValue);
        centralValue = optional(centralValue);
        optimisticValue = optional(optimisticValue);
        observedLow = optional(observedLow);
        observedHigh = optional(observedHigh);
        Objects.requireNonNull(trend, "trend");
        recommendedSafetyBufferMultiplier = Objects.requireNonNull(
                recommendedSafetyBufferMultiplier, "recommendedSafetyBufferMultiplier"
        );
        if (recommendedSafetyBufferMultiplier.compareTo(BigDecimal.ONE) < 0) {
            throw new IllegalArgumentException("recommendedSafetyBufferMultiplier must be at least one");
        }
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        Objects.requireNonNull(explanation, "explanation");
        if (sufficientData && (conservativeValue.isEmpty() || centralValue.isEmpty() || optimisticValue.isEmpty())) {
            throw new IllegalArgumentException("sufficient estimates require conservative, central, and optimistic values");
        }
        if (!sufficientData && (conservativeValue.isPresent() || centralValue.isPresent() || optimisticValue.isPresent())) {
            throw new IllegalArgumentException("insufficient estimates must not fabricate fair values");
        }
        if (conservativeValue.isPresent() && centralValue.isPresent()
                && conservativeValue.get().compareTo(centralValue.get()) > 0) {
            throw new IllegalArgumentException("conservative value must not exceed central value");
        }
        if (centralValue.isPresent() && optimisticValue.isPresent()
                && centralValue.get().compareTo(optimisticValue.get()) > 0) {
            throw new IllegalArgumentException("central value must not exceed optimistic value");
        }
    }

    private static Optional<BigDecimal> optional(Optional<BigDecimal> value) {
        return Objects.requireNonNullElse(value, Optional.empty());
    }
}
