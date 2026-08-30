package com.example.donutflipscanner.market.scanner;

public record ScannerDataVersions(long listings, long completedSales, long statistics) {
    public ScannerDataVersions {
        if (listings < 0 || completedSales < 0 || statistics < 0) {
            throw new IllegalArgumentException("data versions must not be negative");
        }
    }
}
