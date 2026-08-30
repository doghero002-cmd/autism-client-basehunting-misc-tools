package com.example.donutflipscanner.database.entity;

public enum ListingState {
    ACTIVE,
    MISSING_ONCE,
    MISSING_REPEATEDLY,
    INACTIVE_UNKNOWN,
    SOLD_CONFIRMED,
    EXPIRED;

    public boolean active() {
        return this == ACTIVE || this == MISSING_ONCE || this == MISSING_REPEATEDLY;
    }
}
