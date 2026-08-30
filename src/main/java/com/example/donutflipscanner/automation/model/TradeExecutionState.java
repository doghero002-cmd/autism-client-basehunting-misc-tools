package com.example.donutflipscanner.automation.model;

public enum TradeExecutionState {
    QUEUED,
    PRECHECK,
    WAITING_FOR_CONFIRMATION,
    OPENING_AUCTION_HOUSE,
    LOCATING_LISTING,
    VERIFYING_LISTING,
    PURCHASING,
    VERIFYING_PURCHASE,
    CALCULATING_RELIST_PRICE,
    LISTING_FOR_SALE,
    VERIFYING_LISTING_CREATED,
    COMPLETED,
    CANCELLED,
    FAILED,
    INTERRUPTED_REQUIRES_REVIEW;

    public boolean terminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED
                || this == INTERRUPTED_REQUIRES_REVIEW;
    }
}
