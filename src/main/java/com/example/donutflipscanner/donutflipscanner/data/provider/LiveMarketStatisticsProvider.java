package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.MarketStatistics;
import com.example.donutflipscanner.provider.LiveMarketSnapshot;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;

import java.util.Objects;

public final class LiveMarketStatisticsProvider implements MarketStatisticsProvider {
    private final LiveMarketSnapshotService snapshots;

    public LiveMarketStatisticsProvider(LiveMarketSnapshotService snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public MarketStatistics getMarketStatistics() {
        LiveMarketSnapshot value = snapshots.snapshot();
        int opportunities = value.activeOpportunityCount() > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) value.activeOpportunityCount();
        return new MarketStatistics(
                value.activeListings(), value.storedTransactions(), opportunities,
                value.databaseOpportunityCount(), ClientDataFormat.saturatedLong(value.combinedPotentialProfit()),
                value.databaseAvailable()
        );
    }
}
