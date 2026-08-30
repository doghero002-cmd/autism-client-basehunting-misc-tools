package com.example.donutflipscanner.automation.model;

import java.util.Objects;
import java.util.Optional;

public record TradeExecutionResult(
        String executionId,
        TradeExecutionState state,
        boolean successful,
        boolean purchaseConfirmed,
        boolean listingConfirmed,
        String message,
        Optional<RelistPlan> relistPlan
) {
    public TradeExecutionResult {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(state, "state");
        message = Objects.requireNonNullElse(message, "");
        relistPlan = Objects.requireNonNullElse(relistPlan, Optional.empty());
    }
}
