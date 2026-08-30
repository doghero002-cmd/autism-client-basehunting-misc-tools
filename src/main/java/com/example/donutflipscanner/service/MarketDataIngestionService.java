package com.example.donutflipscanner.service;

import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.market.scanner.ScanBatchResult;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Asynchronous API-page to local-market boundary used by scanner orchestration. */
public interface MarketDataIngestionService extends AutoCloseable {
    CompletableFuture<ScanBatchResult> ingestListings(
            String sourceKey,
            ApiAuctionPage page,
            Instant receivedAt
    );

    CompletableFuture<ScanBatchResult> ingestTransactions(
            String sourceKey,
            ApiTransactionPage page,
            Instant receivedAt
    );

    CompletableFuture<ScanBatchResult> recalculateStatistics(Set<String> changedFingerprints);

    CompletableFuture<ScanBatchResult> runRetentionCleanup();

    CompletableFuture<Void> flushPendingWrites();

    CompletableFuture<Void> saveConfiguration();

    @Override
    void close();
}
