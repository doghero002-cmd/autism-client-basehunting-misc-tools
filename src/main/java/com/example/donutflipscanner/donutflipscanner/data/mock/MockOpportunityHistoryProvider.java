package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.OpportunityHistoryEntry;
import com.example.donutflipscanner.data.provider.OpportunityHistoryProvider;

import java.util.List;

public final class MockOpportunityHistoryProvider implements OpportunityHistoryProvider {
    private static final List<OpportunityHistoryEntry> HISTORY = List.of(
            new OpportunityHistoryEntry("mock-history-001", "Diamond Block", "REVIEWED"),
            new OpportunityHistoryEntry("mock-history-002", "Emerald Block", "EXPIRED")
    );

    @Override
    public List<OpportunityHistoryEntry> getHistory() {
        return HISTORY;
    }
}

