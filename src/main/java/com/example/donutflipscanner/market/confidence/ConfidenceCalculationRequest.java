package com.example.donutflipscanner.market.confidence;

import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.value.FairValueMarketContext;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record ConfidenceCalculationRequest(
        NormalizedItem item,
        FairValueMarketContext market,
        Duration listingAge,
        Duration snapshotAge,
        Optional<ExternalConfidenceCap> externalCap
) {
    public ConfidenceCalculationRequest {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(market, "market");
        listingAge = nonNegative(listingAge, "listingAge");
        snapshotAge = nonNegative(snapshotAge, "snapshotAge");
        externalCap = Objects.requireNonNullElse(externalCap, Optional.empty());
        if (!item.fingerprint().sha256().equals(market.primary().itemFingerprint())) {
            throw new IllegalArgumentException("item and market fingerprints must match");
        }
    }

    private static Duration nonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return duration;
    }
}
