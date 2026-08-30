package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;

import java.util.concurrent.CompletableFuture;

public interface MarketStatisticsProvider {
    CompletableFuture<ItemMarketStatistics> statisticsFor(
            NormalizedItem item,
            MarketStatisticsConfig config
    );
}
