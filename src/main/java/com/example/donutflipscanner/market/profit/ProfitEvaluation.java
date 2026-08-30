package com.example.donutflipscanner.market.profit;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ProfitEvaluation(
        String itemFingerprint,
        Optional<ProfitBreakdown> breakdown,
        EffectiveProfitThresholds thresholds,
        boolean accepted,
        List<ProfitRejection> rejections
) {
    public ProfitEvaluation {
        itemFingerprint = Objects.requireNonNull(itemFingerprint, "itemFingerprint");
        breakdown = Objects.requireNonNullElse(breakdown, Optional.empty());
        Objects.requireNonNull(thresholds, "thresholds");
        rejections = List.copyOf(Objects.requireNonNull(rejections, "rejections"));
        if (accepted != rejections.isEmpty()) {
            throw new IllegalArgumentException("accepted must be true exactly when no rejections exist");
        }
    }
}
