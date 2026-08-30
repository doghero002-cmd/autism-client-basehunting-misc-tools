package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.market.item.model.ItemMatchType;

import java.util.Objects;
import java.util.Set;

public record SupportedItemPolicy(Set<ItemMatchType> allowedMatchTypes) {
    public SupportedItemPolicy {
        allowedMatchTypes = Set.copyOf(Objects.requireNonNull(allowedMatchTypes, "allowedMatchTypes"));
    }

    public static SupportedItemPolicy safeDefaults() {
        return new SupportedItemPolicy(Set.of(ItemMatchType.EXACT, ItemMatchType.COMMODITY));
    }

    public static SupportedItemPolicy liveDefaults() {
        return new SupportedItemPolicy(Set.of(
                ItemMatchType.EXACT,
                ItemMatchType.COMMODITY,
                ItemMatchType.VISIBLE_METADATA
        ));
    }

    public boolean supports(ItemMatchType matchType) {
        return allowedMatchTypes.contains(Objects.requireNonNull(matchType, "matchType"));
    }
}
