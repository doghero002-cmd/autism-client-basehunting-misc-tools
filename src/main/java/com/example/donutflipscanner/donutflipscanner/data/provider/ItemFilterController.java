package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.ItemFilterSnapshot;
import com.example.donutflipscanner.market.opportunity.ItemFilterMode;

import java.util.concurrent.CompletableFuture;

public interface ItemFilterController {
    ItemFilterSnapshot getItemFilters();

    CompletableFuture<Void> setMode(ItemFilterMode mode);

    CompletableFuture<Void> setWhitelisted(String itemId, boolean whitelisted);

    CompletableFuture<Void> setBlacklisted(String itemId, boolean blacklisted);
}
