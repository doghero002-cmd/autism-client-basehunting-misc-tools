package com.example.donutflipscanner.market.value;

import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.MarketStatisticsConfig;
import com.example.donutflipscanner.market.statistics.MarketStatisticsProvider;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Builds three independent completed-sale windows without blocking the caller. */
public final class StatisticsBackedFairValueService implements FairValueProvider {
    private final MarketStatisticsProvider statisticsProvider;
    private final MarketStatisticsConfig statisticsConfig;
    private final FairValueConfig fairValueConfig;
    private final FairValueEstimator estimator;

    public StatisticsBackedFairValueService(
            MarketStatisticsProvider statisticsProvider,
            MarketStatisticsConfig statisticsConfig,
            FairValueConfig fairValueConfig
    ) {
        this(statisticsProvider, statisticsConfig, fairValueConfig, new FairValueEstimator());
    }

    public StatisticsBackedFairValueService(
            MarketStatisticsProvider statisticsProvider,
            MarketStatisticsConfig statisticsConfig,
            FairValueConfig fairValueConfig,
            FairValueEstimator estimator
    ) {
        this.statisticsProvider = Objects.requireNonNull(statisticsProvider, "statisticsProvider");
        this.statisticsConfig = Objects.requireNonNull(statisticsConfig, "statisticsConfig");
        this.fairValueConfig = Objects.requireNonNull(fairValueConfig, "fairValueConfig");
        this.estimator = Objects.requireNonNull(estimator, "estimator");
    }

    @Override
    public CompletableFuture<FairValueEstimate> estimateFor(NormalizedItem item) {
        Objects.requireNonNull(item, "item");
        var primary = statisticsProvider.statisticsFor(
                item, statisticsConfig.withLookback(fairValueConfig.primaryLookback())
        );
        var recent = statisticsProvider.statisticsFor(
                item, statisticsConfig.withLookback(fairValueConfig.recentLookback())
        );
        var longTerm = statisticsProvider.statisticsFor(
                item, statisticsConfig.withLookback(fairValueConfig.longTermLookback())
        );
        return primary.thenCombine(recent, WindowPair::new)
                .thenCombine(longTerm, (pair, longTermStatistics) -> estimator.estimate(
                        item,
                        new FairValueMarketContext(pair.primary(), pair.recent(), longTermStatistics),
                        fairValueConfig
                ));
    }

    private record WindowPair(
            com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics primary,
            com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics recent
    ) {
    }
}
