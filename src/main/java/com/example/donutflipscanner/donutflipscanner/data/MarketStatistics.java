package com.example.donutflipscanner.data;

public record MarketStatistics(
        long activeListings,
        long storedTransactions,
        int opportunitiesFound,
        long databaseOpportunityCount,
        long combinedPotentialProfit,
        boolean databaseAvailable
) {
    public MarketStatistics(long activeListings, long storedTransactions, int opportunitiesFound) {
        this(activeListings, storedTransactions, opportunitiesFound, opportunitiesFound, 0L, true);
    }
}
