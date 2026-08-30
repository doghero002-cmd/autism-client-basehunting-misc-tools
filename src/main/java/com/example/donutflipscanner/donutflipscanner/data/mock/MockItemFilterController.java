package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.ItemFilterSnapshot;
import com.example.donutflipscanner.data.provider.ItemFilterController;
import com.example.donutflipscanner.market.opportunity.ItemFilterMode;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class MockItemFilterController implements ItemFilterController {
    private ItemFilterMode mode = ItemFilterMode.ALL_ITEMS;
    private final Set<String> whitelist = new LinkedHashSet<>();
    private final Set<String> blacklist = new LinkedHashSet<>();

    @Override
    public synchronized ItemFilterSnapshot getItemFilters() {
        return new ItemFilterSnapshot(mode, whitelist, blacklist, false);
    }

    @Override
    public synchronized CompletableFuture<Void> setMode(ItemFilterMode mode) {
        this.mode = mode;
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> setWhitelisted(String itemId, boolean whitelisted) {
        if (whitelisted) {
            whitelist.add(itemId);
            blacklist.remove(itemId);
        } else {
            whitelist.remove(itemId);
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public synchronized CompletableFuture<Void> setBlacklisted(String itemId, boolean blacklisted) {
        if (blacklisted) {
            blacklist.add(itemId);
            whitelist.remove(itemId);
        } else {
            blacklist.remove(itemId);
        }
        return CompletableFuture.completedFuture(null);
    }
}
