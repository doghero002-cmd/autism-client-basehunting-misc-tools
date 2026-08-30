package com.example.donutflipscanner.market.opportunity;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public record ItemFilterPolicy(
        ItemFilterMode mode,
        Set<String> whitelistedItemIds,
        Set<String> blacklistedItemIds
) {
    public ItemFilterPolicy {
        Objects.requireNonNull(mode, "mode");
        whitelistedItemIds = Set.copyOf(Objects.requireNonNull(whitelistedItemIds, "whitelistedItemIds"));
        blacklistedItemIds = Set.copyOf(Objects.requireNonNull(blacklistedItemIds, "blacklistedItemIds"));
        HashSet<String> overlap = new HashSet<>(whitelistedItemIds);
        overlap.retainAll(blacklistedItemIds);
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException("items cannot be both whitelisted and blacklisted: " + overlap);
        }
    }

    public static ItemFilterPolicy allowAll() {
        return new ItemFilterPolicy(ItemFilterMode.ALL_ITEMS, Set.of(), Set.of());
    }

    public FilterDecision evaluate(String itemId) {
        Objects.requireNonNull(itemId, "itemId");
        return switch (mode) {
            case ALL_ITEMS -> new FilterDecision(true, "All-items mode accepts this item.");
            case WHITELIST_ONLY -> whitelistedItemIds.contains(itemId)
                    ? new FilterDecision(true, "Item is present in the whitelist.")
                    : new FilterDecision(false, "Item is not present in the whitelist.");
            case ALL_EXCEPT_BLACKLIST -> blacklistedItemIds.contains(itemId)
                    ? new FilterDecision(false, "Item is present in the blacklist.")
                    : new FilterDecision(true, "Item is not present in the blacklist.");
        };
    }
}
