package com.example.donutflipscanner.balance;

/** Lifecycle state for a passive, read-only balance observation. */
public enum BalanceStatus {
    UNAVAILABLE,
    REFRESHING,
    AVAILABLE,
    ERROR
}
