package com.example.donutflipscanner.service;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface MarketStatisticsRefreshService {
    /** Returns the number of fingerprints whose cached statistics changed. */
    CompletableFuture<Integer> refresh(Set<String> fingerprints, Instant calculatedAt);
}
