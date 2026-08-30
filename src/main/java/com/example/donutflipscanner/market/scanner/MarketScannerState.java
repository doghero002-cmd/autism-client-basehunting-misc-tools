package com.example.donutflipscanner.market.scanner;

public enum MarketScannerState {
    STOPPED,
    STARTING,
    RUNNING,
    PAUSED,
    RATE_LIMITED,
    ERROR,
    STOPPING
}
