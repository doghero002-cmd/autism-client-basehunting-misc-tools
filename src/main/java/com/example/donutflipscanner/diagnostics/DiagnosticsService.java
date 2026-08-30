package com.example.donutflipscanner.diagnostics;

import com.example.donutflipscanner.api.ApiConnectionSnapshot;
import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.MarketStatisticsRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.market.scanner.MarketScannerSnapshot;
import com.example.donutflipscanner.security.SensitiveDataSanitizer;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Builds bounded diagnostics asynchronously; it never reads credentials or personal paths. */
public final class DiagnosticsService {
    private final DatabaseManager database;
    private final FingerprintRepository fingerprints;
    private final ListingRepository listings;
    private final SaleRepository sales;
    private final MarketStatisticsRepository statistics;
    private final OpportunityRepository opportunities;
    private final PerformanceMetrics performanceMetrics;

    public DiagnosticsService(DatabaseManager database, PerformanceMetrics performanceMetrics) {
        this.database = Objects.requireNonNull(database, "database");
        this.performanceMetrics = Objects.requireNonNull(performanceMetrics, "performanceMetrics");
        fingerprints = new FingerprintRepository(database);
        listings = new ListingRepository(database);
        sales = new SaleRepository(database);
        statistics = new MarketStatisticsRepository(database);
        opportunities = new OpportunityRepository(database);
    }

    public CompletableFuture<DiagnosticsSnapshot> generate(
            DiagnosticsVersions versions,
            MarketScannerSnapshot scanner,
            ApiConnectionSnapshot api,
            CacheStatistics caches,
            int opportunityQueueSize
    ) {
        Objects.requireNonNull(versions, "versions");
        Objects.requireNonNull(scanner, "scanner");
        Objects.requireNonNull(api, "api");
        Objects.requireNonNull(caches, "caches");
        if (opportunityQueueSize < 0) {
            throw new IllegalArgumentException("opportunityQueueSize must not be negative");
        }
        CompletableFuture<Long> fingerprintCount = fingerprints.count();
        CompletableFuture<Long> listingCount = listings.countActive();
        CompletableFuture<Long> saleCount = sales.count();
        CompletableFuture<Long> statisticCount = statistics.count();
        CompletableFuture<Long> opportunityCount = opportunities.count();
        CompletableFuture<Integer> schemaVersion = database.schemaVersion();
        return CompletableFuture.allOf(
                fingerprintCount, listingCount, saleCount, statisticCount, opportunityCount, schemaVersion
        ).thenApply(ignored -> new DiagnosticsSnapshot(
                Instant.now(), versions, scanner.state(), api.state(),
                api.attemptedRequestCount(), api.successfulRequestCount(), api.failedRequestCount(),
                api.requestsInCurrentWindow(), api.averageLatency(),
                new DatabaseRecordCounts(
                        fingerprintCount.join(), listingCount.join(), saleCount.join(), statisticCount.join(),
                        opportunityCount.join()
                ),
                schemaVersion.join(),
                performanceMetrics.snapshot(caches, database.queuedOperationCount(), opportunityQueueSize),
                sanitizedError(scanner, api)
        ));
    }

    private static Optional<String> sanitizedError(
            MarketScannerSnapshot scanner,
            ApiConnectionSnapshot api
    ) {
        return scanner.lastSanitizedError().or(() -> api.lastErrorSummary())
                .map(SensitiveDataSanitizer::sanitize);
    }
}
