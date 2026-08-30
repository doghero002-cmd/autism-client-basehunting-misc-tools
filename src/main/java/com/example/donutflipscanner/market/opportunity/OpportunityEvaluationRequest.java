package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.value.FairValueMarketContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public record OpportunityEvaluationRequest(
        ListingEntity listing,
        NormalizedItem item,
        FairValueMarketContext market,
        Optional<ItemEvaluationProfile> itemProfile,
        BigDecimal currentOpenExposure,
        Duration marketSnapshotAge,
        Optional<Duration> variantKnownAge,
        long completedSalesVersion,
        boolean scannerRestarted
) {
    public OpportunityEvaluationRequest {
        Objects.requireNonNull(listing, "listing");
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(market, "market");
        itemProfile = Objects.requireNonNullElse(itemProfile, Optional.empty());
        Objects.requireNonNull(currentOpenExposure, "currentOpenExposure");
        if (currentOpenExposure.signum() < 0 || completedSalesVersion < 0) {
            throw new IllegalArgumentException("exposure and sales version must not be negative");
        }
        marketSnapshotAge = nonNegative(marketSnapshotAge, "marketSnapshotAge");
        variantKnownAge = Objects.requireNonNullElse(variantKnownAge, Optional.empty());
        variantKnownAge.ifPresent(value -> nonNegative(value, "variantKnownAge"));
    }

    private static Duration nonNegative(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }
}
