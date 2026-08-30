package com.example.donutflipscanner.data.mock;

import com.example.donutflipscanner.data.MarketStatistics;
import com.example.donutflipscanner.data.provider.MarketStatisticsProvider;
import com.example.donutflipscanner.data.provider.OpportunityProvider;

import java.util.Objects;

public final class MockMarketStatisticsProvider implements MarketStatisticsProvider {
    private final OpportunityProvider opportunityProvider;

    public MockMarketStatisticsProvider(OpportunityProvider opportunityProvider) {
        this.opportunityProvider = Objects.requireNonNull(opportunityProvider, "opportunityProvider");
    }

    @Override
    public MarketStatistics getMarketStatistics() {
        return new MarketStatistics(12_482, 87_310, opportunityProvider.getOpportunities().size());
    }
}

