package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.OpportunityHistoryEntry;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface OpportunityHistoryProvider {
    List<OpportunityHistoryEntry> getHistory();

    default CompletableFuture<Integer> clearHistory() {
        return CompletableFuture.completedFuture(0);
    }
}
