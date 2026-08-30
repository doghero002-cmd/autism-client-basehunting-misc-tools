package com.example.donutflipscanner.data;

import com.example.donutflipscanner.market.opportunity.ItemFilterMode;

import java.util.Objects;
import java.util.Set;

public record ItemFilterSnapshot(
        ItemFilterMode mode,
        Set<String> whitelistedItems,
        Set<String> blacklistedItems,
        boolean reevaluationPending
) {
    public ItemFilterSnapshot {
        Objects.requireNonNull(mode, "mode");
        whitelistedItems = Set.copyOf(Objects.requireNonNull(whitelistedItems, "whitelistedItems"));
        blacklistedItems = Set.copyOf(Objects.requireNonNull(blacklistedItems, "blacklistedItems"));
    }
}
