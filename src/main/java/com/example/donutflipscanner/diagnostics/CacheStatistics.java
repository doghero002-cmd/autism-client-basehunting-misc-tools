package com.example.donutflipscanner.diagnostics;

public record CacheStatistics(
        int pageHashEntries,
        int trackedOpportunities,
        int knownActiveListings,
        long estimatedBytes
) {
    public CacheStatistics {
        if (pageHashEntries < 0 || trackedOpportunities < 0 || knownActiveListings < 0
                || estimatedBytes < 0) {
            throw new IllegalArgumentException("cache statistics must not be negative");
        }
    }

    public static CacheStatistics empty() {
        return new CacheStatistics(0, 0, 0, 0);
    }
}
