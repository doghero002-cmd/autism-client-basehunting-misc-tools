package com.example.donutflipscanner.market.statistics.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record ComparableSaleSet(
        int inputCount,
        List<ComparableSale> accepted,
        List<RejectedComparableSale> rejected,
        Optional<Instant> earliestIncludedTime,
        Instant calculatedAt
) {
    public ComparableSaleSet {
        if (inputCount < 0) {
            throw new IllegalArgumentException("inputCount must not be negative");
        }
        accepted = List.copyOf(Objects.requireNonNull(accepted, "accepted"));
        rejected = List.copyOf(Objects.requireNonNull(rejected, "rejected"));
        earliestIncludedTime = earliestIncludedTime == null ? Optional.empty() : earliestIncludedTime;
        Objects.requireNonNull(calculatedAt, "calculatedAt");
        if (accepted.size() + rejected.size() != inputCount) {
            throw new IllegalArgumentException("every input sale must be accepted or rejected");
        }
    }
}
