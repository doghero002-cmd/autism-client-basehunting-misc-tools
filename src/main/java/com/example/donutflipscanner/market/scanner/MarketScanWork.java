package com.example.donutflipscanner.market.scanner;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Asynchronous page/batch work boundary. Implementations must never block the caller. */
public interface MarketScanWork extends AutoCloseable {
    CompletableFuture<ScanBatchResult> pollRecentlyListed();

    CompletableFuture<ScanBatchResult> pollCompletedTransactions();

    CompletableFuture<ScanBatchResult> refreshActiveListings();

    CompletableFuture<ScanBatchResult> recalculateStatistics(Set<String> changedFingerprints);

    CompletableFuture<ScanBatchResult> runRetentionCleanup();

    CompletableFuture<Void> flushPendingWrites();

    CompletableFuture<Void> saveConfiguration();

    @Override
    void close();
}
