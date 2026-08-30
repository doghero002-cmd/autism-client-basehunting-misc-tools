package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.ItemFilterSnapshot;
import com.example.donutflipscanner.market.opportunity.ItemFilterMode;
import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

public final class LiveItemFilterController implements ItemFilterController {
    private final AtomicReference<ItemFilterPolicy> policy;
    private final AtomicInteger pendingReevaluations = new AtomicInteger();
    private final Function<ItemFilterPolicy, CompletableFuture<Void>> applyAsync;

    public LiveItemFilterController(
            ItemFilterPolicy initial,
            Function<ItemFilterPolicy, CompletableFuture<Void>> applyAsync
    ) {
        policy = new AtomicReference<>(Objects.requireNonNull(initial, "initial"));
        this.applyAsync = Objects.requireNonNull(applyAsync, "applyAsync");
    }

    @Override
    public ItemFilterSnapshot getItemFilters() {
        ItemFilterPolicy current = policy.get();
        return new ItemFilterSnapshot(
                current.mode(), current.whitelistedItemIds(), current.blacklistedItemIds(),
                pendingReevaluations.get() > 0
        );
    }

    @Override
    public synchronized CompletableFuture<Void> setMode(ItemFilterMode mode) {
        ItemFilterPolicy current = policy.get();
        return update(new ItemFilterPolicy(mode, current.whitelistedItemIds(), current.blacklistedItemIds()));
    }

    @Override
    public synchronized CompletableFuture<Void> setWhitelisted(String itemId, boolean whitelisted) {
        ItemFilterPolicy current = policy.get();
        Set<String> whitelist = new HashSet<>(current.whitelistedItemIds());
        Set<String> blacklist = new HashSet<>(current.blacklistedItemIds());
        if (whitelisted) {
            whitelist.add(itemId);
            blacklist.remove(itemId);
        } else {
            whitelist.remove(itemId);
        }
        return update(new ItemFilterPolicy(current.mode(), whitelist, blacklist));
    }

    @Override
    public synchronized CompletableFuture<Void> setBlacklisted(String itemId, boolean blacklisted) {
        ItemFilterPolicy current = policy.get();
        Set<String> whitelist = new HashSet<>(current.whitelistedItemIds());
        Set<String> blacklist = new HashSet<>(current.blacklistedItemIds());
        if (blacklisted) {
            blacklist.add(itemId);
            whitelist.remove(itemId);
        } else {
            blacklist.remove(itemId);
        }
        return update(new ItemFilterPolicy(current.mode(), whitelist, blacklist));
    }

    private CompletableFuture<Void> update(ItemFilterPolicy updated) {
        ItemFilterPolicy previous = policy.getAndSet(updated);
        pendingReevaluations.incrementAndGet();
        CompletableFuture<Void> future;
        try {
            future = Objects.requireNonNull(applyAsync.apply(updated), "filter apply future");
        } catch (RuntimeException exception) {
            policy.compareAndSet(updated, previous);
            pendingReevaluations.decrementAndGet();
            return CompletableFuture.failedFuture(exception);
        }
        return future.whenComplete((ignored, error) -> {
            if (error != null) {
                policy.compareAndSet(updated, previous);
            }
            pendingReevaluations.decrementAndGet();
        });
    }
}
