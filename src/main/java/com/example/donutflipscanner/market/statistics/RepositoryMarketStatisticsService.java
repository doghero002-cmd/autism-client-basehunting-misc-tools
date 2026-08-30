package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.diagnostics.PerformanceMetrics;
import com.example.donutflipscanner.diagnostics.PerformanceOperation;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronously composes bounded repository snapshots. The pure calculator is
 * never run on Minecraft's render thread by this service.
 */
public final class RepositoryMarketStatisticsService implements MarketStatisticsProvider {
    private final SaleRepository saleRepository;
    private final ListingRepository listingRepository;
    private final MarketStatisticsCalculator calculator;
    private final Clock clock;
    private final PerformanceMetrics performanceMetrics;

    public RepositoryMarketStatisticsService(
            SaleRepository saleRepository,
            ListingRepository listingRepository
    ) {
        this(saleRepository, listingRepository, new MarketStatisticsCalculator(), Clock.systemUTC(),
                new PerformanceMetrics());
    }

    public RepositoryMarketStatisticsService(
            SaleRepository saleRepository,
            ListingRepository listingRepository,
            MarketStatisticsCalculator calculator,
            Clock clock
    ) {
        this(saleRepository, listingRepository, calculator, clock, new PerformanceMetrics());
    }

    public RepositoryMarketStatisticsService(
            SaleRepository saleRepository,
            ListingRepository listingRepository,
            MarketStatisticsCalculator calculator,
            Clock clock,
            PerformanceMetrics performanceMetrics
    ) {
        this.saleRepository = Objects.requireNonNull(saleRepository, "saleRepository");
        this.listingRepository = Objects.requireNonNull(listingRepository, "listingRepository");
        this.calculator = Objects.requireNonNull(calculator, "calculator");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.performanceMetrics = Objects.requireNonNull(performanceMetrics, "performanceMetrics");
    }

    @Override
    public CompletableFuture<ItemMarketStatistics> statisticsFor(
            NormalizedItem item,
            MarketStatisticsConfig config
    ) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(config, "config");
        Instant snapshotTime = clock.instant();
        String fingerprint = item.fingerprint().sha256();
        return performanceMetrics.measureAsync(PerformanceOperation.COMPARABLE_SALE_QUERY, () -> {
            var sales = saleRepository.findByFingerprintBetween(
                    fingerprint,
                    config.lookback().cutoff(snapshotTime),
                    snapshotTime,
                    config.maxComparableSales()
            );
            var asks = listingRepository.findActiveByFingerprint(
                    fingerprint,
                    config.maxActiveListings()
            );
            return sales.thenCombine(asks, (saleSnapshot, askSnapshot) -> calculator.calculate(
                    item, saleSnapshot, askSnapshot, config, snapshotTime
            ));
        });
    }
}
