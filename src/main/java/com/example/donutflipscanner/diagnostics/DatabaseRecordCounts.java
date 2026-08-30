package com.example.donutflipscanner.diagnostics;

public record DatabaseRecordCounts(
        long fingerprints,
        long activeListings,
        long completedSales,
        long marketStatistics,
        long opportunities
) {
    public DatabaseRecordCounts {
        if (fingerprints < 0 || activeListings < 0 || completedSales < 0
                || marketStatistics < 0 || opportunities < 0) {
            throw new IllegalArgumentException("database counts must not be negative");
        }
    }
}
