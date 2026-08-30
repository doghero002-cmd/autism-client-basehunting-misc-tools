package com.example.donutflipscanner.market.value;

import com.example.donutflipscanner.market.item.model.NormalizedItem;

import java.util.concurrent.CompletableFuture;

public interface FairValueProvider {
    CompletableFuture<FairValueEstimate> estimateFor(NormalizedItem item);
}
