package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.OpportunityHistoryEntry;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;
import com.example.donutflipscanner.provider.LiveMarketSnapshot;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class DatabaseOpportunityHistoryProvider implements OpportunityHistoryProvider {
    private final LiveMarketSnapshotService snapshots;
    private final Clock clock;
    private volatile LiveMarketSnapshot cachedSource;
    private volatile List<OpportunityHistoryEntry> cachedHistory = List.of();

    public DatabaseOpportunityHistoryProvider(LiveMarketSnapshotService snapshots) {
        this(snapshots, Clock.systemUTC());
    }

    DatabaseOpportunityHistoryProvider(LiveMarketSnapshotService snapshots, Clock clock) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public List<OpportunityHistoryEntry> getHistory() {
        LiveMarketSnapshot current = snapshots.snapshot();
        if (current != cachedSource) {
            synchronized (this) {
                if (current != cachedSource) {
                    cachedHistory = current.history().stream().map(value -> new OpportunityHistoryEntry(
                            value.opportunityId(), value.itemId(), ClientDataFormat.itemName(value.itemId()),
                            ClientDataFormat.age(value.detectedAt(), clock),
                            ClientDataFormat.saturatedLong(value.listingPrice()),
                            ClientDataFormat.saturatedLong(value.conservativeFairValue()),
                            ClientDataFormat.saturatedLong(value.estimatedProfit()),
                            value.roiPercent().doubleValue(), value.confidencePercent().doubleValue(), value.state()
                    )).toList();
                    cachedSource = current;
                }
            }
        }
        return cachedHistory;
    }

    @Override
    public CompletableFuture<Integer> clearHistory() {
        return snapshots.clearHistory();
    }
}
